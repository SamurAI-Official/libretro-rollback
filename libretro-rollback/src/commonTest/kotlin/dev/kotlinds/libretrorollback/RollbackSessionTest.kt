package dev.kotlinds.libretrorollback

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RollbackSessionTest {

    private class Pair(
        val coreA: FakeCore,
        val coreB: FakeCore,
        val transportA: SimulatedTransport,
        val transportB: SimulatedTransport,
        val sessionA: RollbackSession,
        val sessionB: RollbackSession,
    )

    private fun pair(
        latency: Int,
        config: RollbackConfig = RollbackConfig(inputSize = 4, inputDelay = 2, maxPredictionFrames = 8),
        coreA: FakeCore = FakeCore(),
        coreB: FakeCore = FakeCore(),
        eventsA: (RollbackEvent) -> Unit = {},
        eventsB: (RollbackEvent) -> Unit = {},
    ): Pair {
        val transportA = SimulatedTransport(Endpoint("a", 7000), latencyFrames = latency)
        val transportB = SimulatedTransport(Endpoint("b", 7001), latencyFrames = latency)
        transportA.attach(transportB)
        transportB.attach(transportA)
        val sessionA = RollbackSession(coreA, transportA, config, eventsA)
        val sessionB = RollbackSession(coreB, transportB, config, eventsB)
        sessionA.start(0, transportB.endpoint)
        sessionB.start(1, transportA.endpoint)
        return Pair(coreA, coreB, transportA, transportB, sessionA, sessionB)
    }

    private fun Pair.handshake() {
        repeat(12) {
            sessionA.idle(0)
            sessionB.idle(0)
            transportA.tick()
            transportB.tick()
        }
        assertTrue(sessionA.isSynchronized, "A did not synchronize")
        assertTrue(sessionB.isSynchronized, "B did not synchronize")
    }

    private fun Pair.runFrames(frames: Int) {
        val inputA = ByteArray(4) { 1 }
        val inputB = ByteArray(4) { 2 }
        repeat(frames) {
            sessionA.tick(inputA)
            sessionB.tick(inputB)
            transportA.tick()
            transportB.tick()
        }
        // Let the last inputs arrive and the last rollbacks replay.
        repeat(16) {
            sessionA.tick(inputA)
            sessionB.tick(inputB)
            transportA.tick()
            transportB.tick()
        }
    }

    @Test
    fun lockstepWithoutLatencyStaysInSync() {
        val pair = pair(latency = 0)
        pair.handshake()
        pair.runFrames(1000)
        assertContentEquals(pair.coreA.snapshot(), pair.coreB.snapshot())
        assertEquals(0, pair.sessionA.stats.rollbacks)
    }

    @Test
    fun predictionRollsBackAndConverges() {
        val events = mutableListOf<RollbackEvent>()
        val pair = pair(
            latency = 3,
            eventsA = { events += it },
            eventsB = { events += it },
        )
        pair.handshake()
        pair.runFrames(1000)
        assertContentEquals(pair.coreA.snapshot(), pair.coreB.snapshot())
        assertTrue(pair.sessionA.stats.rollbacks > 0, "expected at least one rollback")
        assertTrue(pair.sessionA.stats.maxRollbackFrames <= 8)
        assertTrue(events.any { it is RollbackEvent.Rollback }, "expected a rollback event")
    }

    @Test
    fun packetLossStillConverges() {
        val pair = pair(latency = 2)
        pair.transportA.dropEvery = 5
        pair.transportB.dropEvery = 7
        pair.handshake()
        pair.runFrames(1000)
        assertContentEquals(pair.coreA.snapshot(), pair.coreB.snapshot())
    }

    @Test
    fun divergenceIsReportedAsDesync() {
        val events = mutableListOf<RollbackEvent>()
        val pair = pair(
            latency = 0,
            coreA = FakeCore(divergeAt = 40),
            eventsA = { events += it },
            eventsB = { events += it },
        )
        pair.handshake()
        pair.runFrames(100)
        assertTrue(events.any { it is RollbackEvent.Desync }, "expected a desync event, got $events")
    }

    @Test
    fun handshakeRejectsDifferentInitialState() {
        val events = mutableListOf<RollbackEvent>()
        val pair = pair(
            latency = 0,
            coreB = FakeCore(initialValue = 7),
            eventsA = { events += it },
        )
        repeat(12) {
            pair.sessionA.idle(0)
            pair.sessionB.idle(0)
            pair.transportA.tick()
            pair.transportB.tick()
        }
        assertTrue(
            events.any { it is RollbackEvent.HandshakeFailed },
            "expected a handshake failure, got $events",
        )
    }

    @Test
    fun predictionWithDeltaSnapshotsConverges() {
        val config = RollbackConfig(
            inputSize = 4,
            inputDelay = 2,
            maxPredictionFrames = 8,
            snapshotStrategy = SnapshotStrategy.DELTA,
            deltaKeyframeInterval = 4,
        )
        val events = mutableListOf<RollbackEvent>()
        val pair = pair(
            latency = 3,
            config = config,
            eventsA = { events += it },
            eventsB = { events += it },
        )
        pair.handshake()
        pair.runFrames(1000)
        assertContentEquals(pair.coreA.snapshot(), pair.coreB.snapshot())
        assertTrue(pair.sessionA.stats.rollbacks > 0, "expected at least one rollback")
        assertTrue(events.any { it is RollbackEvent.Rollback }, "expected a rollback event")
    }

    @Test
    fun checksumIsStableForSameBytes() {
        val bytes = ByteArray(32) { it.toByte() }
        assertEquals(Checksum.fnv1a64(bytes), Checksum.fnv1a64(bytes.copyOf()))
    }
}
