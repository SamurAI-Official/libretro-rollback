package dev.kotlinds.libretrorollback

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * UDP transport for the JVM. A background thread receives packets into a queue; [receive] drains it
 * on the thread that owns the emulator, as libretro requires.
 */
class UdpTransport(
    bindPort: Int,
    /** Drop every Nth outgoing packet; 0 disables loss simulation. */
    private val dropEvery: Int = 0,
) : Transport {
    private val socket = DatagramSocket(bindPort)
    private val inbox = ConcurrentLinkedQueue<Datagram>()
    private val addresses = mutableMapOf<String, InetAddress>()
    private var sent = 0

    private val receiver = thread(name = "libretro-rollback-udp", isDaemon = true) {
        val buffer = ByteArray(65_536)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                break
            }
            val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            inbox.add(
                Datagram(
                    data = data,
                    length = data.size,
                    from = Endpoint(packet.address.hostAddress, packet.port),
                ),
            )
        }
    }

    override fun send(data: ByteArray, offset: Int, length: Int, to: Endpoint) {
        sent++
        if (dropEvery > 0 && sent % dropEvery == 0) return
        val address = addresses.getOrPut(to.host) { InetAddress.getByName(to.host) }
        socket.send(DatagramPacket(data, offset, length, address, to.port))
    }

    override fun receive(): List<Datagram> {
        if (inbox.isEmpty()) return emptyList()
        val result = mutableListOf<Datagram>()
        while (true) result += inbox.poll() ?: break
        return result
    }

    override fun close() {
        socket.close()
        receiver.join(200)
    }
}
