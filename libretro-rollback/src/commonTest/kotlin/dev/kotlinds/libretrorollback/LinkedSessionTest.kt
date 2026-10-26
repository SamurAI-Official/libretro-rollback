package dev.kotlinds.libretrorollback

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Linked play: two peers, one console each, exchanging local wireless through the session.
 *
 * The test console mirrors NIFI's shape - the leader beacons, the follower acks - so the tests can
 * check the two things that matter: a radio packet reaches the peer's console at
 * `producedFrame + linkDelayFrames` (not whenever the network delivered it), and a lost packet is
 * recovered by the redundant re-sends.
 */
class LinkedSessionTest {

    private class LinkedCore(private val leader: Boolean, private val radio: LinkRadio, divergeAt: Int = -1) : RollbackCore {
        private val inner = FakeCore(divergeAt = divergeAt)
        private var frame = 0
        private var seq = 0
        private var pendingAck = false

        var beaconsSent = 0
            private set
        var acksSent = 0
            private set
        var beaconsSeen = 0
            private set
        var acksSeen = 0
            private set
        var firstBeaconFrame = -1
            private set
        var firstAckFrame = -1
            private set

        /**
         * Beacons (or acks) whose sequence number went backwards: a packet from a run that was rolled
         * back and replaced. Must stay zero.
         */
        var staleBeacons = 0
            private set
        var staleAcks = 0
            private set

        private var lastBeaconSeq = -1
        private var lastAckSeq = -1
        private var pendingAckSeq = 0
        val beaconFrames = mutableListOf<Int>()
        val ackFrames = mutableListOf<Int>()

        override val stateSize: Int get() = inner.stateSize
        override val fingerprint: CoreFingerprint get() = inner.fingerprint
        override fun saveState(into: ByteArray): Int = inner.saveState(into)
        override fun loadState(from: ByteArray, size: Int) = inner.loadState(from, size)

        override fun runFrame(inputs: ByteArray, render: Boolean) {
            val buffer = ByteArray(8)
            if (radio.poll(buffer) >= 1) {
                when (buffer[0].toInt() and 0xFF) {
                    BEACON -> {
                        val beaconSeq = buffer[1].toInt() and 0xFF
                        if (beaconSeq < lastBeaconSeq) staleBeacons++
                        lastBeaconSeq = beaconSeq
                        beaconsSeen++
                        if (firstBeaconFrame < 0) firstBeaconFrame = frame
                        pendingAck = true
                        pendingAckSeq = beaconSeq
                    }

                    ACK -> {
                        val ackSeq = buffer[1].toInt() and 0xFF
                        if (ackSeq < lastAckSeq) staleAcks++
                        lastAckSeq = ackSeq
                        acksSeen++
                        if (firstAckFrame < 0) firstAckFrame = frame
                    }
                }
            }
            if (leader && frame % 8 == 0) {
                seq = (seq + 1) and 0xFF
                if (radio.accept(byteArrayOf(BEACON.toByte(), seq.toByte()), 2) > 0) {
                    beaconsSent++
                    beaconFrames += frame
                }
            }
            if (!leader && pendingAck) {
                pendingAck = false
                if (radio.accept(byteArrayOf(ACK.toByte(), pendingAckSeq.toByte()), 2) > 0) {
                    acksSent++
                    ackFrames += frame
                }
            }
            inner.runFrame(inputs, render)
            frame++
        }

        fun snapshot(): IntArray = inner.snapshot()

        private companion object {
            const val BEACON = 0xB0
            const val ACK = 0xA0
        }
    }

    /**
     * Single-threaded frontend loop for a linked pair. A real frontend runs one peer per thread; here
     * one thread plays both so a test decides exactly which frame a packet becomes visible on, which
     * is the property under test.
     */
    private class Harness(
        val config: RollbackConfig,
        latencyFrames: Int = 0,
        dropEveryA: Int = 0,
        dropEveryB: Int = 0,
        divergeB: Int = -1,
    ) {
        val events = mutableListOf<RollbackEvent>()
        val radioA = LinkRadio(config.linkQueueDepth)
        val radioB = LinkRadio(config.linkQueueDepth)
        val coreA = LinkedCore(leader = true, radio = radioA)
        val coreB = LinkedCore(leader = false, radio = radioB, divergeAt = divergeB)
        val transportA = SimulatedTransport(
            Endpoint("a", 7000),
            latencyFrames = latencyFrames,
            dropEvery = dropEveryA,
        )
        val transportB = SimulatedTransport(
            Endpoint("b", 7001),
            latencyFrames = latencyFrames,
            dropEvery = dropEveryB,
        )
        val sessionA = RollbackSession(coreA, transportA, config) { events += it }
        val sessionB = RollbackSession(coreB, transportB, config) { events += it }

        private val inputsA = ByteArray(config.frameInputBytes)
        private val inputsB = ByteArray(config.frameInputBytes)
        private val localA = ByteArray(config.inputSize)
        private val localB = ByteArray(config.inputSize)
        private var steps = 0

        init {
            transportA.attach(transportB)
            transportB.attach(transportA)
        }

        /** Inputs change over time so a predicted frame is often wrong and a rollback really happens. */
        private fun nextInputs() {
            steps++
            localA[0] = ((steps / 3) % 4).toByte()
            localB[0] = ((steps / 5) % 3).toByte()
        }

        fun start(linkOnB: Boolean = true) {
            sessionA.attachLink(radioA)
            if (linkOnB) sessionB.attachLink(radioB)
            sessionA.start(0, transportB.endpoint)
            sessionB.start(1, transportA.endpoint)
        }

        fun handshake(): Boolean {
            repeat(16) {
                sessionA.idle(0)
                sessionB.idle(0)
                transportA.tick()
                transportB.tick()
            }
            return sessionA.isSynchronized && sessionB.isSynchronized
        }

        /**
         * One frame on both consoles. In lockstep both peers wait for the peer's input (pumping the
         * transports while they wait); in rollback they run with predictions and let the session
         * re-simulate, which is what exercises the linked rollback path.
         */
        fun step(): Boolean {
            sessionA.idle(0)
            sessionB.idle(0)
            if (!sessionA.isSynchronized || !sessionB.isSynchronized) return false
            nextInputs()
            sessionA.addLocalInput(localA)
            sessionB.addLocalInput(localB)
            if (config.mode == Mode.LOCKSTEP) {
                if (!await(sessionA, inputsA, localA) || !await(sessionB, inputsB, localB)) return false
            } else {
                sessionA.synchronizeInput(inputsA)
                sessionB.synchronizeInput(inputsB)
            }
            sessionA.prepareLink()
            sessionB.prepareLink()
            coreA.runFrame(inputsA, render = true)
            coreB.runFrame(inputsB, render = true)
            sessionA.flushLink()
            sessionB.flushLink()
            sessionA.advanceFrame()
            sessionB.advanceFrame()
            transportA.tick()
            transportB.tick()
            return true
        }

        /**
         * Runs [frames] frames on both consoles. A step that stalls (a dropped input packet) is
         * retried, exactly like a real frontend keeps looping until the input arrives.
         */
        fun run(frames: Int): Int {
            var completed = 0
            var attempts = 0
            while (completed < frames && attempts < frames * 20) {
                attempts++
                if (step()) completed++ else stalls++
            }
            return completed
        }

        var stalls = 0
            private set

        /** Used by the assertions so a stall reports why instead of just a frame count. */
        fun diagnostics(): String = listOf(
            "framesA=" + sessionA.stats.frame,
            "framesB=" + sessionB.stats.frame,
            "syncA=" + sessionA.isSynchronized,
            "syncB=" + sessionB.isSynchronized,
            "remoteA=" + sessionA.stats.remoteFrame,
            "remoteB=" + sessionB.stats.remoteFrame,
            "stalls=$stalls",
            "events=" + events.distinct(),
        ).joinToString(" ")

        /**
         * Waits for the peer's input for the current frame. While waiting it keeps re-sending the
         * redundant inputs (as [RollbackSession.tick] does) and pumps the transports, which is what a
         * real frontend gets for free from the network thread.
         */
        private fun await(session: RollbackSession, into: ByteArray, local: ByteArray): Boolean {
            var spins = 0
            while (session.isSynchronized && !session.synchronizeInput(into)) {
                session.idle(0)
                session.addLocalInput(local)
                transportA.tick()
                transportB.tick()
                if (++spins > 10_000) return false
            }
            return session.isSynchronized
        }

        fun close() {
            sessionA.close()
            sessionB.close()
        }

        fun summary(): String = listOf(
            "beacons=" + coreA.beaconsSent,
            "beaconsSeen=" + coreB.beaconsSeen,
            "acks=" + coreB.acksSent,
            "acksSeen=" + coreA.acksSeen,
            "firstBeacon=" + coreB.firstBeaconFrame,
            "firstAck=" + coreA.firstAckFrame,
            "staleBeacons=" + coreB.staleBeacons,
            "staleAcks=" + coreA.staleAcks,
            "late=" + (sessionA.stats.linkLate + sessionB.stats.linkLate),
            "rollbacks=" + sessionA.stats.rollbacks,
            "epochs=" + (sessionA.stats.linkEpoch + sessionB.stats.linkEpoch),
            "coreA=" + coreA.snapshot().joinToString(),
            "coreB=" + coreB.snapshot().joinToString(),
        ).joinToString(" ")
    }

    private fun linkedConfig(
        delay: Int = 4,
        redundancy: Int = 4,
        mode: Mode = Mode.LOCKSTEP,
        maxPredictionFrames: Int = 8,
    ): RollbackConfig = RollbackConfig(
        inputSize = 4,
        // A local input delay is what makes lockstep work: each peer produces its input for a frame
        // a couple of frames early, so neither has to wait for an input the peer has not produced yet.
        inputDelay = 2,
        mode = mode,
        maxPredictionFrames = maxPredictionFrames,
        linkDelayFrames = delay,
        linkRedundancy = redundancy,
    )

    @Test
    fun radioPacketsReachThePeerAfterTheLinkDelay() {
        val config = linkedConfig(delay = 4)
        val harness = Harness(config)
        harness.start()
        assertTrue(harness.handshake(), "the linked pair did not synchronize")
        // 210 frames, not 200: the last ack is transmitted four frames after the last beacon and
        // needs another link delay to arrive, so the tail has to be inside the run.
        assertEquals(210, harness.run(210), harness.diagnostics())

        val firstBeacon = harness.coreA.beaconFrames.first()
        assertEquals(
            firstBeacon + config.linkDelayFrames,
            harness.coreB.firstBeaconFrame,
            "the follower's console must see the beacon ${config.linkDelayFrames} frames after it was transmitted",
        )
        assertTrue(harness.coreB.beaconsSeen > 0, "the follower never received a beacon")
        assertTrue(harness.coreA.acksSeen > 0, "the leader never received an ack")
        assertTrue(harness.sessionA.stats.linkTx > 0, "no radio packets were sent")
        assertTrue(harness.sessionB.stats.linkRx > 0, "no radio packets were delivered")
        assertContentEquals(harness.coreA.snapshot(), harness.coreB.snapshot())
        harness.close()
    }

    @Test
    fun lostRadioPacketsAreRecoveredByRedundancy() {
        val config = linkedConfig(delay = 4)
        val harness = Harness(config, dropEveryA = 3, dropEveryB = 5)
        harness.start()
        assertTrue(harness.handshake(), "the linked pair did not synchronize")
        val frames = 200
        assertEquals(frames, harness.run(frames), harness.diagnostics())

        // A packet transmitted too close to the end of the run has not reached the peer yet, so only
        // the ones with a full link delay to spare must have arrived.
        val deliverableBeacons = harness.coreA.beaconFrames.count { it + config.linkDelayFrames < frames }
        assertEquals(
            deliverableBeacons,
            harness.coreB.beaconsSeen,
            "every beacon must reach the follower despite the loss",
        )
        val deliverableAcks = harness.coreB.ackFrames.count { it + config.linkDelayFrames < frames }
        assertEquals(
            deliverableAcks,
            harness.coreA.acksSeen,
            "every ack must reach the leader despite the loss",
        )
        assertTrue(deliverableBeacons > 0, "the scenario should have delivered beacons")
        assertContentEquals(harness.coreA.snapshot(), harness.coreB.snapshot())
        harness.close()
    }

    @Test
    fun linkedSessionIsDeterministic() {
        fun run(): String {
            val harness = Harness(linkedConfig(delay = 3), dropEveryA = 3)
            harness.start()
            assertTrue(harness.handshake())
            harness.run(120)
            val summary = harness.summary()
            harness.close()
            return summary
        }

        assertEquals(run(), run())
    }

    @Test
    fun rollbackWithALinkNeedsADelayLargerThanThePredictionLimit() {
        // A rollback re-simulates frames whose radio packets cannot be unsent, so the link delay has
        // to be long enough that the peer's console has not delivered them yet.
        val unsafe = RollbackConfig(
            inputSize = 4,
            mode = Mode.ROLLBACK,
            maxPredictionFrames = 8,
            linkDelayFrames = 4,
        )
        val session = RollbackSession(FakeCore(), SimulatedTransport(Endpoint("a", 7000)), unsafe)
        assertFailsWith<IllegalArgumentException> { session.attachLink(LinkRadio()) }

        // Lockstep never re-simulates, so any delay works there.
        val lockstep = RollbackConfig(inputSize = 4, mode = Mode.LOCKSTEP, linkDelayFrames = 1)
        RollbackSession(FakeCore(), SimulatedTransport(Endpoint("b", 7001)), lockstep)
            .attachLink(LinkRadio())
    }

    @Test
    fun linkedRollbackKeepsTheTwoConsolesTogether() {
        val config = linkedConfig(delay = 5, mode = Mode.ROLLBACK, maxPredictionFrames = 4)
        // Latency above the input delay: the peer's input for a frame is not in yet, so the engine
        // predicts it, gets it wrong and rolls back.
        val harness = Harness(config, latencyFrames = 4)
        harness.start()
        assertTrue(harness.handshake(), "the linked pair did not synchronize")
        assertEquals(300, harness.run(300), harness.diagnostics())

        assertTrue(harness.sessionA.stats.rollbacks > 0, "expected rollbacks, got ${harness.sessionA.stats}")
        assertTrue(harness.sessionA.stats.linkEpoch > 0, "a rollback must invalidate the link output")
        assertEquals(
            emptyList(),
            harness.events.filter { it is RollbackEvent.Desync || it is RollbackEvent.LinkRollbackRefused },
            "the linked rollback must not desync or be refused",
        )
        assertEquals(0, harness.coreB.staleBeacons, "the follower saw a beacon from a discarded run")
        assertEquals(0, harness.coreA.staleAcks, "the leader saw an ack from a discarded run")
        assertTrue(harness.coreB.beaconsSeen > 0, "the follower never received a beacon")
        assertTrue(harness.sessionA.isSynchronized && harness.sessionB.isSynchronized)
        harness.close()
    }

    @Test
    fun linkedRollbackIsDeterministic() {
        fun run(): String {
            val harness = Harness(
                linkedConfig(delay = 5, mode = Mode.ROLLBACK, maxPredictionFrames = 4),
                latencyFrames = 4,
            )
            harness.start()
            assertTrue(harness.handshake())
            harness.run(150)
            val summary = harness.summary()
            harness.close()
            return summary
        }

        val first = run()
        assertTrue(first.contains("rollbacks=") && !first.contains("rollbacks=0"), "expected rollbacks: $first")
        assertEquals(first, run())
    }

    @Test
    fun handshakeRejectsAPeerWithoutLinkedPlay() {
        val harness = Harness(linkedConfig(delay = 2))
        harness.start(linkOnB = false)
        assertTrue(!harness.handshake(), "the handshake must fail when only one peer links its radio")
        val failure = harness.events.filterIsInstance<RollbackEvent.HandshakeFailed>().firstOrNull()
        assertTrue(failure != null, "expected a handshake failure, got ${harness.events}")
        assertTrue(
            failure.reason.contains("linked play"),
            "the reason should mention linked play: ${failure.reason}",
        )
        harness.close()
    }

    /**
     * A real linked session runs two *different* consoles: their RAM legitimately differs (each game
     * holds its own player's data), so the same-simulation checksum comparison of the two-controller
     * topology must not apply. Before [RollbackConfig.linkChecksums], a real wireless session would
     * have been reported as a desync as soon as the two consoles disagreed — something the deterministic
     * echo cores of the other tests cannot show.
     */
    @Test
    fun twoDifferentConsolesDoNotReportAFalseDesync() {
        val config = linkedConfig(delay = 4)
        val harness = Harness(config, divergeB = 40)
        harness.start()
        assertTrue(harness.handshake(), "the linked pair did not synchronize")
        assertEquals(200, harness.run(200), harness.diagnostics())
        assertEquals(
            emptyList(),
            harness.events.filter { it is RollbackEvent.Desync },
            "two different consoles must not be compared frame by frame: ${harness.diagnostics()}",
        )
        assertTrue(harness.sessionA.isSynchronized && harness.sessionB.isSynchronized)
        harness.close()
    }

    /** With `linkChecksums` on, the same divergence *is* reported: that is what the flag is for. */
    @Test
    fun linkChecksumsStillCatchDivergentConsolesWhenEnabled() {
        val config = linkedConfig(delay = 4).copy(linkChecksums = true)
        val harness = Harness(config, divergeB = 40)
        harness.start()
        assertTrue(harness.handshake(), "the linked pair did not synchronize")
        harness.run(200)
        assertTrue(
            harness.events.any { it is RollbackEvent.Desync },
            "expected a desync with linkChecksums on, got ${harness.events.distinct()}",
        )
        harness.close()
    }

}
