package dev.kotlinds.libretrorollback.demo

import dev.kotlinds.libretrokmp.LibretroCore
import dev.kotlinds.libretrorollback.Endpoint
import dev.kotlinds.libretrorollback.LinkRadio
import dev.kotlinds.libretrorollback.Mode
import dev.kotlinds.libretrorollback.RollbackConfig
import dev.kotlinds.libretrorollback.RollbackEvent
import dev.kotlinds.libretrorollback.SnapshotStrategy
import dev.kotlinds.libretrorollback.RollbackSession
import dev.kotlinds.libretrorollback.libretro.LibretroRollbackCore
import dev.kotlinds.libretrorollback.libretro.RollbackFrontend
import dev.kotlinds.libretrorollback.libretro.installLink
import dev.kotlinds.libretrorollback.UdpTransport
import java.nio.file.Files
import java.nio.file.Path

internal fun session(args: List<String>, localPlayer: Int) {
    val options = parse(args)
    val corePath = options["core"] ?: error("--core is required")
    val romPath = options["rom"] ?: error("--rom is required")
    val bindPort = options["bind"]?.toInt() ?: (7000 + localPlayer)
    val peer = options["peer"] ?: error("--peer host:port is required")
    val peerHost = peer.substringBeforeLast(':')
    val peerPort = peer.substringAfterLast(':').toInt()
    val warmup = options["warmup"]?.toInt() ?: 300
    val maxFrames = options["frames"]?.toInt() ?: Int.MAX_VALUE
    val mode = if (options["mode"]?.equals("lockstep", ignoreCase = true) == true) Mode.LOCKSTEP else Mode.ROLLBACK
    val linked = options.containsKey("link")
    // Lockstep is the safe default for linked play; --mode rollback is allowed when the link delay is
    // longer than the prediction limit, because a re-simulated frame's radio packets cannot be unsent.
    val effectiveMode = if (linked && options["mode"] == null) Mode.LOCKSTEP else mode
    val prediction = options["prediction"]?.toInt() ?: 8
    val linkDelay = options["link-delay"]?.toInt()
        ?: if (effectiveMode == Mode.ROLLBACK) prediction + 3 else 3

    val data = Path.of(options["data"] ?: "build/session-p$localPlayer")
    val system = data.resolve("system")
    val saves = data.resolve("saves")
    Files.createDirectories(system)
    Files.createDirectories(saves)

    val overrides = optionOverrides(options["options"])
    val frontend = RollbackFrontend(
        system.toString(),
        saves.toString(),
        coreOptionsFor(Path.of(corePath).fileName.toString()) + overrides,
    )
    val codec = frontend.codec
    val core = LibretroCore(corePath, frontend)
    val transport = UdpTransport(bindPort, options["drop"]?.toInt() ?: 0)
    val radio = if (linked) LinkRadio() else null
    try {
        core.loadGame(romPath)
        println("core: ${core.systemInfo.libraryName} ${core.systemInfo.libraryVersion}")
        if (radio != null) {
            val installed = core.installLink(radio)
            println("linked play: ${if (installed) "radio routed through the frontend" else "core has no link ABI"}")
        }
        println("warming up $warmup frames")
        val idle = ByteArray(codec.inputSize * 2)
        repeat(warmup) {
            frontend.inputs = idle
            frontend.render = false
            core.run()
        }
        options["state"]?.let { statePath ->
            val bytes = Files.readAllBytes(Path.of(statePath))
            check(core.loadState(bytes)) { "core rejected state $statePath" }
            println("loaded state $statePath (${bytes.size} bytes)")
        }

        val rollback = LibretroRollbackCore.create(
            core,
            frontend,
            sha256(Path.of(romPath)),
            enableDirtyPages = !options.containsKey("no-dirty"),
        )
        val snapshotStrategy = if (options["snapshots"]?.equals("delta", ignoreCase = true) == true) {
            SnapshotStrategy.DELTA
        } else {
            SnapshotStrategy.FULL
        }
        val config = RollbackConfig(
            inputSize = codec.inputSize,
            inputDelay = options["delay"]?.toInt() ?: 2,
            maxPredictionFrames = prediction,
            mode = effectiveMode,
            snapshotStrategy = snapshotStrategy,
            deltaKeyframeInterval = options["keyframe"]?.toInt() ?: 30,
            maxStateBytes = 512L * 1024 * 1024,
            linkDelayFrames = linkDelay,
        )
        val session = RollbackSession(rollback, transport, config) { event ->
            when (event) {
                is RollbackEvent.Synchronized -> println("synchronized")
                is RollbackEvent.HandshakeFailed -> println("handshake failed: ${event.reason}")
                is RollbackEvent.Desync ->
                    println("DESYNC at frame ${event.frame} (local ${event.localChecksum.toULong().toString(16)}, " +
                        "remote ${event.remoteChecksum.toULong().toString(16)})")
                is RollbackEvent.Disconnected -> println("disconnected: player ${event.player} (${event.reason})")
                is RollbackEvent.LinkRollbackRefused ->
                    println("linked rollback refused at frame ${event.frame} (${event.replayFrames} frames, " +
                        "link delay ${event.linkDelayFrames}): the peer's console already received those packets")
                is RollbackEvent.Timesync -> {}
                is RollbackEvent.PredictionThreshold -> println("prediction threshold at frame ${event.frame}")
                is RollbackEvent.Rollback -> println("rollback: replayed ${event.replayFrames} frames to ${event.toFrame}")
            }
        }
        println("player ${localPlayer + 1}, bind $bindPort, peer $peerHost:$peerPort, $effectiveMode, state ${rollback.stateSize} bytes")
        if (radio != null) {
            println("link: delay ${config.linkDelayFrames} frames, prediction limit ${config.maxPredictionFrames}, " +
                "rollback-safe ${config.linkRollbackSafe}")
        }
        radio?.let { session.attachLink(it) }
        session.start(localPlayer, Endpoint(peerHost, peerPort))

        val frameNanos = (1_000_000_000.0 / core.avInfo.timing.fps).toLong()
        var next = System.nanoTime()
        var frames = 0
        val deadline = System.nanoTime() + (options["seconds"]?.toLong() ?: 30L) * 1_000_000_000L
        val localInput = codec.pack(0)
        while (frames < maxFrames && System.nanoTime() < deadline) {
            val ran = session.tick(localInput, render = true)
            if (!ran) {
                Thread.sleep(1)
                continue
            }
            frames++
            if (frames % 60 == 0) {
                val stats = session.stats
                val line = "save=%.2fms load=%.2fms checksum=%.2fms scan=%.2fms state=%.1fMiB " +
                    "scans=${stats.deltaScans} dirty=${stats.dirtyHits}"
                println(
                    "frame ${stats.frame}: rollbacks=${stats.rollbacks} maxDepth=${stats.maxRollbackFrames} " +
                        "predicted=${stats.predictedFrames} remote=${stats.remoteFrame} " +
                        "link=${stats.linkTx}/${stats.linkRx} late=${stats.linkLate} " +
                        line.format(
                            stats.averageSaveMillis,
                            stats.averageLoadMillis,
                            stats.averageChecksumMillis,
                            stats.averageScanMillis,
                            stats.stateBytes / (1024.0 * 1024.0),
                        ),
                )
            }
            next += frameNanos
            val sleep = next - System.nanoTime()
            if (sleep > 0) {
                Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
            } else {
                next = System.nanoTime()
            }
            if (!session.isSynchronized && frames > 0) break
        }
        println("stopped after $frames frames: ${session.stats}")
        session.close()
    } finally {
        transport.close()
        core.close()
    }
}
