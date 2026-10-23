package dev.kotlinds.libretrorollback.libretro

import dev.kotlinds.libretrokmp.CoreLink
import dev.kotlinds.libretrokmp.LinkChannel
import dev.kotlinds.libretrorollback.LinkRadio

/**
 * Bridges a core's local wireless radio to a [LinkRadio].
 *
 * The core calls these methods from inside `retro_run` (the thread that owns the emulator); the
 * session fills the radio's receive queue before the frame and drains what the radio transmitted
 * after it. See libretro-rollback's `docs/two-console.md`.
 */
class LibretroLinkChannel(private val radio: LinkRadio) : LinkChannel {

    override fun onLinkSend(data: ByteArray, length: Int): Int = radio.accept(data, length)

    override fun onLinkReceive(data: ByteArray): Int = radio.poll(data)
}

/**
 * Routes [radio] through a core that exports the link ABI.
 *
 * Returns false for a stock core (no `t6_link_set_callbacks` symbol), which keeps its own LAN
 * transport — two instances on one machine then still see each other, but only in real time and
 * without the frame-scheduled delivery netplay needs.
 */
fun CoreLink.installLink(radio: LinkRadio): Boolean = installLink(LibretroLinkChannel(radio))
