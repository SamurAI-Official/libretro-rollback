package dev.kotlinds.libretrorollback

/**
 * In-process packet transport with a virtual clock, so tests can simulate latency, jitter and loss
 * without sleeping. [tick] advances the clock by one frame and delivers due packets to the peer.
 */
class SimulatedTransport(
    val endpoint: Endpoint,
    var latencyFrames: Int = 0,
    var dropEvery: Int = 0,
) : Transport {
    private data class Pending(val at: Int, val data: ByteArray, val from: Endpoint)

    private var clock = 0
    private var sent = 0
    private val pending = mutableListOf<Pending>()
    private val inbox = ArrayDeque<Datagram>()

    /** Every packet this transport sent, in order: tests inspect them or replay them. */
    val sentPackets = mutableListOf<ByteArray>()

    fun attach(peer: SimulatedTransport) {
        other = peer
    }

    private var other: SimulatedTransport? = null

    override fun send(data: ByteArray, offset: Int, length: Int, to: Endpoint) {
        sent++
        sentPackets.add(data.copyOfRange(offset, offset + length))
        if (dropEvery > 0 && sent % dropEvery == 0) return
        val copy = data.copyOfRange(offset, offset + length)
        if (latencyFrames == 0) {
            other?.inbox?.addLast(Datagram(copy, copy.size, endpoint))
        } else {
            pending.add(Pending(clock + latencyFrames, copy, endpoint))
        }
    }

    /**
     * Puts [data] in the inbox as if [from] had sent it, without going through a peer. Tests use it to
     * play the part of a third party, or of the peer itself.
     */
    fun inject(data: ByteArray, from: Endpoint) {
        inbox.addLast(Datagram(data.copyOf(), data.size, from))
    }

    override fun receive(): List<Datagram> {
        val result = inbox.toList()
        inbox.clear()
        return result
    }

    fun tick() {
        clock++
        val due = pending.filter { it.at <= clock }
        pending.removeAll(due)
        val peer = other ?: return
        for (item in due) peer.inbox.addLast(Datagram(item.data, item.data.size, item.from))
    }

    override fun close() {
        pending.clear()
        inbox.clear()
    }
}
