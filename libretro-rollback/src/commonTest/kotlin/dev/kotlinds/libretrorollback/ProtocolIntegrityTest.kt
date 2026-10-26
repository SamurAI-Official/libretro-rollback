package dev.kotlinds.libretrorollback

import dev.kotlinds.libretrorollback.internal.Hello
import dev.kotlinds.libretrorollback.internal.PacketType
import dev.kotlinds.libretrorollback.internal.ProtocolMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire is the attack surface. Only the endpoint the session was started with is heard, and only
 * the token that endpoint first sent; a packet that fails either check is dropped before it reaches
 * the input queue. These tests play a third party (or a replayed packet) without a second machine.
 *
 * The endpoints are numeric on purpose: with a name, only the port can be compared (the transport
 * reports the resolved address), and the token is what authenticates the peer.
 */
class ProtocolIntegrityTest {

    private class Pair(
        val transportA: SimulatedTransport,
        val transportB: SimulatedTransport,
        val sessionA: RollbackSession,
        val sessionB: RollbackSession,
    )

    private fun pair(): Pair {
        val config = RollbackConfig(inputSize = 4, inputDelay = 2, maxPredictionFrames = 8, mode = Mode.LOCKSTEP)
        val transportA = SimulatedTransport(Endpoint("127.0.0.1", 7000))
        val transportB = SimulatedTransport(Endpoint("127.0.0.1", 7001))
        transportA.attach(transportB)
        transportB.attach(transportA)
        val sessionA = RollbackSession(FakeCore(), transportA, config)
        val sessionB = RollbackSession(FakeCore(), transportB, config)
        sessionA.start(0, transportB.endpoint)
        sessionB.start(1, transportA.endpoint)
        return Pair(transportA, transportB, sessionA, sessionB)
    }

    private fun Pair.handshake() {
        repeat(12) {
            sessionA.idle(0)
            sessionB.idle(0)
            transportA.tick()
            transportB.tick()
        }
        assertTrue(sessionA.isSynchronized && sessionB.isSynchronized, "the pair did not synchronize")
    }

    /** Reads a little-endian u64 at [offset] (the header's token field). */
    private fun u64At(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) value = value or ((data[offset + index].toLong() and 0xFFL) shl (8 * index))
        return value
    }

    private fun u32At(data: ByteArray, offset: Int): Int {
        var value = 0
        for (index in 0 until 4) value = value or ((data[offset + index].toInt() and 0xFF) shl (8 * index))
        return value
    }

    private data class Identity(val sessionId: Int, val token: Long)

    /** The peer's session id and token, read from a packet it really sent (its HELLO). */
    private fun Pair.peerIdentity(): Identity {
        val hello = transportB.sentPackets.first()
        assertEquals(PacketType.HELLO, hello[5].toInt() and 0xFF, "the first packet must be a HELLO")
        return Identity(u32At(hello, 8), u64At(hello, 12))
    }

    private fun inputPacket(
        sessionId: Int,
        token: Long,
        startFrame: Int,
        inputs: ByteArray = ByteArray(4),
        count: Int = 1,
    ): ByteArray = ProtocolMessages.input(
        sessionId = sessionId,
        token = token,
        player = 1,
        players = 2,
        inputSize = 4,
        frame = 0,
        ackFrame = -1,
        checksum = 0,
        startFrame = startFrame,
        inputs = inputs,
        count = count,
    )

    @Test
    fun aPacketFromAnotherEndpointIsIgnored() {
        val pair = pair()
        pair.handshake()
        val identity = pair.peerIdentity()
        val before = pair.sessionA.stats.remoteFrame

        // Same port, another address: the port alone is not what identifies the peer.
        pair.transportA.inject(
            inputPacket(identity.sessionId, identity.token, startFrame = before + 100),
            Endpoint("127.0.0.3", 7001),
        )
        pair.sessionA.idle(0)
        assertEquals(before, pair.sessionA.stats.remoteFrame, "a packet from another endpoint must be ignored")
    }

    @Test
    fun aPacketWithTheRightEndpointAndTokenIsAccepted() {
        val pair = pair()
        pair.handshake()
        val identity = pair.peerIdentity()
        val before = pair.sessionA.stats.remoteFrame

        // The control for the tests below: the same craft, from the peer's own endpoint, is applied.
        pair.transportA.inject(
            inputPacket(identity.sessionId, identity.token, startFrame = before + 100),
            pair.transportB.endpoint,
        )
        pair.sessionA.idle(0)
        assertEquals(before + 100, pair.sessionA.stats.remoteFrame, "the crafted packet should be applied")
    }

    @Test
    fun aPacketWithTheWrongTokenIsIgnored() {
        val pair = pair()
        pair.handshake()
        val identity = pair.peerIdentity()
        val before = pair.sessionA.stats.remoteFrame

        // Knowing the session id (it travels in every packet) is not enough: the token is pinned.
        pair.transportA.inject(
            inputPacket(identity.sessionId, identity.token + 1, startFrame = before + 100),
            pair.transportB.endpoint,
        )
        pair.sessionA.idle(0)
        assertEquals(before, pair.sessionA.stats.remoteFrame, "a packet with another token must be ignored")
    }

    @Test
    fun anInputPayloadThatLiesAboutItsSizeIsIgnored() {
        val pair = pair()
        pair.handshake()
        val identity = pair.peerIdentity()
        val before = pair.sessionA.stats.remoteFrame

        // count * inputSize bytes are announced but the payload is too short: decodeInput refuses it.
        pair.transportA.inject(
            inputPacket(identity.sessionId, identity.token, startFrame = before + 100, inputs = ByteArray(4), count = 4),
            pair.transportB.endpoint,
        )
        pair.sessionA.idle(0)
        assertEquals(before, pair.sessionA.stats.remoteFrame, "a truncated input payload must be ignored")
        assertTrue(pair.sessionA.isSynchronized, "a malformed packet must not close the session")
    }

    @Test
    fun aReplayedHelloCannotTakeOverTheSession() {
        val pair = pair()
        pair.handshake()

        // A HELLO with another token, from the peer's endpoint: ignored, the session keeps its peer.
        val forged = ProtocolMessages.hello(
            sessionId = 42,
            token = 7L,
            player = 1,
            players = 2,
            inputSize = 4,
            hello = Hello(
                coreName = "forged",
                coreVersion = "0",
                contentSha256 = "nope",
                optionsHash = 1L,
                stateSize = 64,
                initialChecksum = 0L,
                inputDelay = 2,
                maxPredictionFrames = 8,
            ),
        )
        pair.transportA.inject(forged, pair.transportB.endpoint)
        pair.sessionA.idle(0)
        assertTrue(pair.sessionA.isSynchronized, "a forged HELLO must not end the session")
    }
}
