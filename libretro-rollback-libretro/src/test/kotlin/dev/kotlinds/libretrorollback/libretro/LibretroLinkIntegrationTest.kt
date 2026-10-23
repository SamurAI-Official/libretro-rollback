package dev.kotlinds.libretrorollback.libretro

import dev.kotlinds.libretrokmp.LibretroCore
import dev.kotlinds.libretrorollback.LinkRadio
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Link ABI checks with real cores. Runs only when `POKEMON_ROM` and the core paths are set, the same
 * gate the other ROM tests in this workspace use.
 *
 * These cover the frontend side of linked play: the symbol lookup, the JNA callback registration and
 * the fact that the core keeps running with its radio routed through the frontend. Real radio
 * traffic needs a ROM/state that is inside a local wireless screen, which is what
 * `demo link-probe` is for.
 */
class LibretroLinkIntegrationTest {

    private fun open(corePath: String, romPath: String): Pair<LibretroCore, RollbackFrontend> {
        val data = Files.createTempDirectory("libretro-link-test")
        val frontend = RollbackFrontend(
            data.resolve("system").toString(),
            data.resolve("saves").toString(),
            mapOf(
                "melonds_boot_directly" to "enabled",
                "melonds_console_mode" to "DS",
                "melonds_threaded_renderer" to "disabled",
                "melonds_randomize_mac_address" to "disabled",
                "melonds_use_fw_settings" to "disabled",
                "desmume_cpu_mode" to "interpreter",
                "desmume_frameskip" to "0",
                "desmume_num_cores" to "1",
                "desmume_use_external_bios" to "disabled",
            ),
        )
        Files.createDirectories(data.resolve("system"))
        Files.createDirectories(data.resolve("saves"))
        val core = LibretroCore(corePath, frontend)
        core.loadGame(romPath)
        return core to frontend
    }

    private fun run(core: LibretroCore, frontend: RollbackFrontend, frames: Int) {
        val inputs = ByteArray(frontend.codec.inputSize * 2)
        repeat(frames) {
            frontend.inputs = inputs
            frontend.render = false
            core.run()
        }
    }

    @Test
    fun patchedCoreAcceptsTheLinkCallbacks() {
        val rom = System.getenv("POKEMON_ROM") ?: return
        val corePath = System.getenv("POKEMON_MELONDS_ROLLBACK_CORE") ?: return
        val (core, frontend) = open(corePath, rom)
        try {
            val radio = LinkRadio()
            assertTrue(core.installLink(radio), "the patched melonDS core must export t6_link_set_callbacks")

            // Round trip through the core's own radio routing (MP_SendPacket / MP_RecvPacket): the
            // probe the core transmits has to come back to it through the frontend callbacks.
            radio.deliver(byteArrayOf(0x54, 0x36, 0x4C, 0x4B))
            assertEquals(3, core.linkSelfTest(), "the link callbacks did not round-trip through the core")
            assertEquals(1, radio.transmitted)
            assertEquals(1, radio.received)

            run(core, frontend, 60)

            core.removeLink()
            assertEquals(0, core.linkSelfTest(), "the link ABI must be inactive after removeLink()")
            run(core, frontend, 30)
        } finally {
            core.close()
        }
    }

    @Test
    fun coreWithoutThePatchReportsNoLinkAbi() {
        val rom = System.getenv("POKEMON_ROM") ?: return
        val corePath = System.getenv("POKEMON_DESMUME_ROLLBACK_CORE") ?: return
        val (core, frontend) = open(corePath, rom)
        try {
            assertFalse(core.installLink(LinkRadio()), "a core without the link patch must report no ABI")
            run(core, frontend, 30)
        } finally {
            core.close()
        }
    }
}
