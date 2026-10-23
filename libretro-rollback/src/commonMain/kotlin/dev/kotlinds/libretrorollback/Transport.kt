package dev.kotlinds.libretrorollback

data class Endpoint(val host: String, val port: Int)

data class Datagram(val data: ByteArray, val length: Int, val from: Endpoint)

/**
 * Packet transport. [receive] is non-blocking and is called from the thread that owns the emulator;
 * a UDP implementation may collect packets on a background thread and hand them over here.
 */
interface Transport {
    fun send(data: ByteArray, offset: Int, length: Int, to: Endpoint)
    fun receive(): List<Datagram>
    fun close()
}
