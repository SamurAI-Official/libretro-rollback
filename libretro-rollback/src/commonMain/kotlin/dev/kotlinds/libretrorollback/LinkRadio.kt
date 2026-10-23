package dev.kotlinds.libretrorollback

/**
 * The local wireless radio of one console, routed through the frontend.
 *
 * A linked session runs **one console per process**: each peer emulates its own DS and the two
 * consoles talk over the emulated local wireless. The core hands its radio traffic to the frontend
 * instead of the usual LAN broadcast, which is what makes netplay possible — the packets are
 * delivered at an emulated frame boundary, so both consoles see the same radio timing. See
 * `docs/two-console.md`.
 *
 * Call order is asymmetric because the core drives the radio from inside `runFrame`:
 *
 * ```
 * session delivers the packets scheduled for frame N   ->  deliver()
 * core.runFrame(...)                                   ->  poll() / accept() from the core
 * session collects what the radio transmitted          ->  drainTransmitted()
 * ```
 *
 * All methods are called on the thread that owns the emulator.
 */
class LinkRadio(
    /** Hardware-like receive queue depth: a packet arriving with a full queue drops the oldest. */
    private val queueDepth: Int = 16,
    /** Largest packet the radio accepts (melonDS's MP limit is 2048 bytes). */
    val maxPacketBytes: Int = 2048,
) {
    private val inbound = ArrayDeque<ByteArray>()
    private val outbound = ArrayDeque<ByteArray>()

    /** Packets waiting for the radio to read them. */
    val queued: Int get() = inbound.size

    /** Packets handed to the radio by [deliver]. */
    var received: Long = 0
        private set

    /** Packets dropped because the receive queue was full. */
    var dropped: Long = 0
        private set

    /** Packets the radio transmitted. */
    var transmitted: Long = 0
        private set

    /** Queues a packet for the radio to read during the next frame. Returns false when it was dropped. */
    fun deliver(packet: ByteArray, length: Int = packet.size): Boolean {
        if (length <= 0 || length > maxPacketBytes) {
            dropped++
            return false
        }
        while (inbound.size >= queueDepth) {
            inbound.removeFirst()
            dropped++
        }
        inbound.addLast(if (length == packet.size) packet.copyOf() else packet.copyOf(length))
        received++
        return true
    }

    /**
     * Called by the core when the radio polls for a packet. Non-blocking on purpose: the emulator
     * thread must never wait for the network, the radio protocol retransmits instead.
     */
    fun poll(into: ByteArray): Int {
        val packet = inbound.removeFirstOrNull() ?: return 0
        val length = minOf(packet.size, into.size)
        packet.copyInto(into, 0, 0, length)
        return length
    }

    /** Called by the core when the radio transmits. Returns the number of bytes accepted. */
    fun accept(data: ByteArray, length: Int): Int {
        if (length <= 0 || length > maxPacketBytes || length > data.size) return 0
        outbound.addLast(data.copyOf(length))
        transmitted++
        return length
    }

    /** Packets transmitted during the frame that just ran, oldest first. */
    fun drainTransmitted(): List<ByteArray> {
        if (outbound.isEmpty()) return emptyList()
        val packets = outbound.toList()
        outbound.clear()
        return packets
    }

    /** Drops everything (called when a session closes or a state is restored). */
    fun reset() {
        inbound.clear()
        outbound.clear()
    }
}
