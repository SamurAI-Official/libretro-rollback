package dev.kotlinds.libretrorollback.internal

internal object PacketType {
    const val HELLO = 1
    const val HELLO_ACK = 2
    const val START = 3
    const val INPUT = 4
    const val DESYNC = 5
    const val BYE = 6
    const val LINK = 7
}

internal data class Packet(
    val type: Int,
    val sessionId: Int,
    /**
     * The sender's session token, in every packet. Each peer pins the token it first saw in the
     * peer's HELLO and drops anything that does not carry it, so a third party that knows the
     * (guessable) session id cannot inject inputs: it would have to know the token too.
     */
    val token: Long,
    val player: Int,
    val players: Int,
    val inputSize: Int,
    val frame: Int,
    val ackFrame: Int,
    val checksum: Int,
    val payload: ByteArray,
)

internal data class Hello(
    val coreName: String,
    val coreVersion: String,
    val contentSha256: String,
    val optionsHash: Long,
    val stateSize: Int,
    val initialChecksum: Long,
    val inputDelay: Int,
    val maxPredictionFrames: Int,
    /** True when this peer routes its console's local wireless through the frontend. */
    val linkEnabled: Boolean = false,
    /** Frames between a radio packet being transmitted and the peer's console receiving it. */
    val linkDelay: Int = 0,
)

internal data class InputPayload(
    val startFrame: Int,
    val count: Int,
    val inputs: ByteArray,
)

/**
 * Radio traffic produced during one frame. The receiver delivers every packet of the batch at
 * `frame + linkDelay`, so both consoles see the same packet at the same emulated frame.
 */
internal data class LinkBatch(
    val frame: Int,
    val packets: List<ByteArray>,
)

/**
 * A link message: the sender's epoch, the first frame the epoch invalidated, and the batches it is
 * (re-)sending.
 *
 * [epoch] starts at 0 and is bumped whenever a rollback invalidates the radio output of some frames.
 * Batches are only ever sent with the sender's current epoch, so a receiver can drop what it still
 * had scheduled for frames from [invalidatedFrom] on — those packets belong to a run that no longer
 * exists. Batches for earlier frames stay valid.
 */
internal data class LinkPayload(
    val epoch: Int,
    val invalidatedFrom: Int,
    val batches: List<LinkBatch>,
)

internal object Protocol {
    const val MAGIC = 0x4B42424C // "LBRK" little-endian

    /**
     * 2: the header carries the sender's session token (see [Packet.token]), so a third party that
     *    only knows the session id cannot inject packets.
     */
    const val VERSION = 2
    const val HEADER_SIZE = 36

    fun encode(packet: Packet): ByteArray {
        val writer = ByteWriter(HEADER_SIZE + packet.payload.size)
        writer.u32(MAGIC)
        writer.u8(VERSION)
        writer.u8(packet.type)
        writer.u16(0)
        writer.u32(packet.sessionId)
        writer.u64(packet.token)
        writer.u8(packet.player)
        writer.u8(packet.players)
        writer.u8(packet.inputSize)
        writer.u8(0)
        writer.u32(packet.frame)
        writer.u32(packet.ackFrame)
        writer.u32(packet.checksum)
        writer.bytes(packet.payload)
        return writer.toByteArray()
    }

    fun decode(data: ByteArray, length: Int = data.size): Packet? {
        if (length < HEADER_SIZE) return null
        val reader = ByteReader(data, length)
        if (reader.u32() != MAGIC) return null
        if (reader.u8() != VERSION) return null
        val type = reader.u8()
        reader.u16()
        val sessionId = reader.u32()
        val token = reader.u64()
        val player = reader.u8()
        val players = reader.u8()
        val inputSize = reader.u8()
        reader.u8()
        val frame = reader.u32()
        val ackFrame = reader.u32()
        val checksum = reader.u32()
        return Packet(
            type = type,
            sessionId = sessionId,
            token = token,
            player = player,
            players = players,
            inputSize = inputSize,
            frame = frame,
            ackFrame = ackFrame,
            checksum = checksum,
            payload = reader.bytes(reader.remaining),
        )
    }
}
