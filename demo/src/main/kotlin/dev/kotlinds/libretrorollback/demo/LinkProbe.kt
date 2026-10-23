package dev.kotlinds.libretrorollback.demo

import dev.kotlinds.libretrokmp.LibretroCore
import dev.kotlinds.libretrorollback.LinkRadio
import dev.kotlinds.libretrorollback.libretro.RollbackFrontend
import dev.kotlinds.libretrorollback.libretro.installLink
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs one console with its local wireless routed through the frontend and reports the radio
 * traffic, answering "does this game enter local wireless at all?".
 *
 * A DS only talks to the radio while a game has local multiplayer open (melonDS beacons only in MP
 * mode), so zero packets here means the ROM/state never reached a multiplayer screen — not that the
 * link is broken.
 */
internal fun linkProbe(args: List<String>) {
    val options = parse(args)
    val corePath = options["core"] ?: error("--core is required")
    val romPath = options["rom"] ?: error("--rom is required")
    val data = Path.of(options["data"] ?: "build/link-probe-data")
    val warmup = options["warmup"]?.toInt() ?: 300
    val frames = options["frames"]?.toInt() ?: 1_800

    Files.createDirectories(data.resolve("system"))
    Files.createDirectories(data.resolve("saves"))

    val frontend = RollbackFrontend(
        data.resolve("system").toString(),
        data.resolve("saves").toString(),
        coreOptionsFor(Path.of(corePath).fileName.toString()) + optionOverrides(options["options"]),
    )
    val codec = frontend.codec
    val core = LibretroCore(corePath, frontend)
    val radio = LinkRadio()
    try {
        core.loadGame(romPath)
        val installed = core.installLink(radio)
        println("core: ${core.systemInfo.libraryName} ${core.systemInfo.libraryVersion}")
        println("link ABI: ${if (installed) "installed" else "missing (stock core)"}")
        options["state"]?.let { statePath ->
            val bytes = Files.readAllBytes(Path.of(statePath))
            check(core.loadState(bytes)) { "core rejected state $statePath" }
        }

        val inputs = ByteArray(codec.inputSize * 2)
        var transmitted = 0L
        var frame = 0
        val sizes = mutableListOf<Int>()
        val deadline = System.nanoTime() + (options["seconds"]?.toLong() ?: 120L) * 1_000_000_000L
        while (frame < frames && System.nanoTime() < deadline) {
            frontend.inputs = if (frame < warmup) inputs else scriptedInput(codec, frame - warmup, false)
            frontend.render = false
            core.run()
            val packets = radio.drainTransmitted()
            if (packets.isNotEmpty()) {
                transmitted += packets.size
                sizes += packets.map { it.size }
                if (transmitted <= 10) {
                    val head = packets.first().take(16).joinToString(" ") { "%02X".format(it) }
                    println("frame $frame: radio tx ${packets.size} packet(s), first: $head")
                }
            }
            frame++
        }
        println("frames: $frame, radio packets transmitted: $transmitted, sizes: ${sizes.distinct().take(8)}")
        println("radio queue: received=${radio.received} dropped=${radio.dropped}")
    } finally {
        core.close()
    }
}
