package dev.kotlinds.libretrorollback.demo

import dev.kotlinds.libretrokmp.LibretroCore
import dev.kotlinds.libretrokmp.MemoryType
import dev.kotlinds.libretrorollback.stateHash
import dev.kotlinds.libretrorollback.DeterminismProbe
import dev.kotlinds.libretrorollback.libretro.LibretroRollbackCore
import dev.kotlinds.libretrorollback.libretro.RollbackFrontend
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
        usage()
        return
    }
    when (args[0]) {
        "probe" -> probe(args.drop(1))
        "link-probe" -> linkProbe(args.drop(1))
        "host" -> session(args.drop(1), localPlayer = 0)
        "join" -> session(args.drop(1), localPlayer = 1)
        else -> {
            System.err.println("unknown command: ${args[0]}")
            usage()
            exitProcess(2)
        }
    }
}

private fun usage() {
    println(
        """
        libretro-rollback demo

          probe --core <core.dylib> --rom <game.nds> [options]
          link-probe --core <core.dylib> --rom <game.nds> [options]
          host  --core <core.dylib> --rom <game.nds> --peer <host:port> [options]
          join  --core <core.dylib> --rom <game.nds> --peer <host:port> [options]

        link-probe options:
          --data <dir>       system/save directory (default: build/link-probe-data)
          --warmup <frames>  frames of idle input before the scripted input starts (default: 300)
          --frames <frames>  frames to run (default: 1800)
          --seconds <s>      wall-clock budget (default: 120)
          --state <file>     load a savestate before running
          --options "k=v"    override core options (comma-separated)

        Probe options:
          --data <dir>       system/save directory (default: build/probe-data)
          --warmup <frames>  frames to run before measuring (default: 300)
          --frames <frames>  frames per determinism run (default: 600)
          --repeats <n>      save/load benchmark iterations (default: 30)
          --touch            include touch input in the scripted input
          --hash-ram         compare main RAM instead of the whole savestate (ignores padding)
          --save-state <f>   write the post-warmup state to a file
          --state <f>        load a state after warmup instead of using the boot state
          --options "k=v,k=v" override core options (comma-separated)
          --final-frames <n> frames in the long final-state test (default: 300)
          --final-trials <n> repetitions of the final-state test (default: 1)
          --final-only       run only the final-state test
          --dump-final <dir> write each final state to <dir>/final-N.state

        Session options:
          --bind <port>      local UDP port (default: 7000 for host, 7001 for join)
          --peer <host:port> peer UDP endpoint (required)
          --delay <frames>   local input delay (default: 2)
          --prediction <n>   max prediction frames (default: 8)
          --drop <n>         drop every Nth outgoing packet (loss simulation)
          --mode <mode>      rollback or lockstep (default: rollback)
          --snapshots <s>    full or delta snapshots (default: full)
          --keyframe <n>     keyframe distance for delta snapshots (default: 30)
          --no-dirty         ignore the core's dirty-page API and scan instead
          --link             route the console's local wireless through the session (implies lockstep
                             unless --mode is given; rollback needs --link-delay > --prediction)
          --link-delay <n>   frames a radio packet takes to reach the peer's console (default: 3,
                             or --prediction + 3 when --mode rollback)
          --state <file>     savestate both peers load after warmup
          --frames <n>       stop after n frames
        """.trimIndent(),
    )
}

private fun probe(args: List<String>) {
    val options = parse(args)
    val corePath = options["core"] ?: error("--core is required")
    val romPath = options["rom"] ?: error("--rom is required")
    val data = Path.of(options["data"] ?: "build/probe-data")
    val warmup = options["warmup"]?.toInt() ?: 300
    val frames = options["frames"]?.toInt() ?: 600
    val repeats = options["repeats"]?.toInt() ?: 30
    val touch = options.containsKey("touch")
    val hashRam = options.containsKey("hash-ram")

    val system = data.resolve("system")
    val saves = data.resolve("saves")
    Files.createDirectories(system)
    Files.createDirectories(saves)

    val overrides = optionOverrides(options["options"])
    val coreOptions = coreOptionsFor(Path.of(corePath).fileName.toString()) + overrides
    val frontend = RollbackFrontend(system.toString(), saves.toString(), coreOptions)
    val codec = frontend.codec
    val core = LibretroCore(corePath, frontend)
    try {
        println("loading $romPath on $corePath")
        core.loadGame(romPath)
        val info = core.systemInfo
        println("core: ${info.libraryName} ${info.libraryVersion}")
        println("timing: ${core.avInfo.timing.fps} fps, ${core.avInfo.timing.sampleRate} Hz")

        val idle = ByteArray(codec.inputSize * 2)
        repeat(warmup) {
            frontend.inputs = idle
            frontend.render = false
            core.run()
        }
        println("warmup: $warmup frames")
        options["state"]?.let { statePath ->
            val bytes = Files.readAllBytes(Path.of(statePath))
            check(core.loadState(bytes)) { "core rejected state $statePath" }
            println("loaded state $statePath (${bytes.size} bytes)")
        }
        options["save-state"]?.let { statePath ->
            val state = core.saveState() ?: error("core cannot save states")
            Files.write(Path.of(statePath), state)
            println("saved state $statePath (${state.size} bytes)")
        }

        val rollback = LibretroRollbackCore.create(core, frontend, sha256(Path.of(romPath)))
        println("state: ${rollback.stateSize} bytes max")

        val first = ByteArray(rollback.stateSize)
        val firstLength = rollback.saveState(first)
        val second = ByteArray(rollback.stateSize)
        val secondLength = rollback.saveState(second)
        val saveDiff = (0 until minOf(firstLength, secondLength)).firstOrNull { first[it] != second[it] } ?: -1
        println("save twice: ${if (saveDiff < 0) "identical" else "DIFF at byte $saveDiff"} ($firstLength / $secondLength)")

        rollback.loadState(first, firstLength)
        val afterLoad = ByteArray(rollback.stateSize)
        val afterLoadLength = rollback.saveState(afterLoad)
        val loadDiff = (0 until minOf(firstLength, afterLoadLength)).firstOrNull { first[it] != afterLoad[it] } ?: -1
        println("after load: ${if (loadDiff < 0) "identical" else "DIFF at byte $loadDiff"} ($afterLoadLength)")

        val idleInput = ByteArray(codec.inputSize * 2)
        frontend.inputs = idleInput
        frontend.render = false
        rollback.runFrame(idleInput, render = false)
        val once = ByteArray(rollback.stateSize)
        val onceLength = rollback.saveState(once)
        rollback.loadState(first, firstLength)
        rollback.runFrame(idleInput, render = false)
        val twice = ByteArray(rollback.stateSize)
        val twiceLength = rollback.saveState(twice)
        val frameDiff = (0 until minOf(onceLength, twiceLength)).firstOrNull { once[it] != twice[it] } ?: -1
        println("one frame after load: ${if (frameDiff < 0) "identical" else "DIFF at byte $frameDiff"}")

        val interleavedA = LongArray(12)
        val interleavedB = LongArray(12)
        for (frame in interleavedA.indices) {
            rollback.loadState(first, firstLength)
            repeat(frame + 1) { rollback.runFrame(scriptedInput(codec, it, touch), false) }
            val stateA = ByteArray(rollback.stateSize)
            val lengthA = rollback.saveState(stateA)
            interleavedA[frame] = stateHash(stateA, 0, lengthA)

            rollback.loadState(first, firstLength)
            repeat(frame + 1) { rollback.runFrame(scriptedInput(codec, it, touch), false) }
            val stateB = ByteArray(rollback.stateSize)
            val lengthB = rollback.saveState(stateB)
            interleavedB[frame] = stateHash(stateB, 0, lengthB)
        }
        val interleavedDiff = interleavedA.indices.firstOrNull { interleavedA[it] != interleavedB[it] } ?: -1
        println("interleaved runs: ${if (interleavedDiff < 0) "identical" else "DIFF at frame $interleavedDiff"}")

        // Same frames, but only one save at the end: if this matches while the per-frame probe
        // fails, saving itself perturbs the core (or the core is only nondeterministic while saves
        // interleave with emulation). Repeating the run detects flaky divergences.
        val finalFrames = options["final-frames"]?.toInt() ?: 300
        val finalTrials = options["final-trials"]?.toInt() ?: 1
        val dumpFinal = options["dump-final"]?.let { Path.of(it) }
        if (dumpFinal != null) Files.createDirectories(dumpFinal)
        var reference: ByteArray? = null
        var finalDiff = -1
        var finalTrial = -1
        repeat(finalTrials) { trial ->
            rollback.loadState(first, firstLength)
            repeat(finalFrames) { rollback.runFrame(scriptedInput(codec, it, touch), false) }
            val bytes = if (hashRam) {
                core.readMemory(MemoryType.SYSTEM_RAM) ?: ByteArray(0)
            } else {
                val state = ByteArray(rollback.stateSize)
                val length = rollback.saveState(state)
                state.copyOf(length)
            }
            if (dumpFinal != null) {
                Files.write(dumpFinal.resolve("final-$trial.state"), bytes)
            }
            val ref = reference
            if (ref == null) {
                reference = bytes
            } else if (finalDiff < 0 && !ref.contentEquals(bytes)) {
                finalDiff = (0 until minOf(ref.size, bytes.size)).firstOrNull { ref[it] != bytes[it] } ?: 0
                finalTrial = trial
            }
        }
        val finalText = if (finalDiff < 0) "identical" else "DIFF at byte $finalDiff (trial $finalTrial)"
        println("$finalFrames-frame final state: $finalText (trials=$finalTrials)")

        if (options.containsKey("final-only")) return

        val ramStateHash: (() -> Long)? = if (hashRam) {
            { core.readMemory(MemoryType.SYSTEM_RAM)?.let { stateHash(it, 0, it.size) } ?: 0L }
        } else {
            null
        }
        val result = DeterminismProbe(rollback).run(
            warmupFrames = 0,
            frames = frames,
            benchmarkIterations = repeats,
            script = { frame -> scriptedInput(codec, frame, touch) },
            customStateHash = ramStateHash,
        )

        println()
        println("=== probe result ===")
        println("state length:         ${result.stateLength} bytes")
        println("save:                 %.3f ms".format(result.saveMillis))
        println("load:                 %.3f ms".format(result.loadMillis))
        println("load + 8 frames:      %.3f ms".format(result.rollback8Millis))
        println("determinism:          ${if (result.deterministic) "OK" else "FAILED at frame ${result.mismatchFrame}"}")
        println("initial hash:         ${result.initialStateHash.toULong().toString(16)}")
        println("after load A:         ${result.loadAStateHash.toULong().toString(16)}")
        println("after load B:         ${result.loadBStateHash.toULong().toString(16)}")
        println("frames compared:      ${result.frames}")
        println("video frames dropped: ${frontend.droppedFrames}")
        if (!result.deterministic) {
            val frame = result.mismatchFrame
            println()
            println("first mismatch at frame $frame")
            println("  run A: ${result.hashesA[frame].toULong().toString(16)}")
            println("  run B: ${result.hashesB[frame].toULong().toString(16)}")
            println("  differing bytes: ${result.mismatchByteCount}")
            for (index in result.mismatchOffsets.indices) {
                val offset = result.mismatchOffsets[index]
                val a = result.mismatchValuesA[index].toInt() and 0xFF
                val b = result.mismatchValuesB[index].toInt() and 0xFF
                println("    @0x${offset.toString(16)}: %02X -> %02X".format(a, b))
            }
            println("The core is not rollback-safe with these options. Try a fixed RTC, the interpreter,")
            println("a fixed firmware and a fixed mic mode, or use lockstep mode for this game.")
        }
    } finally {
        core.close()
    }
}
