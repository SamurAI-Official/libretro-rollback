package dev.kotlinds.libretrorollback

import dev.kotlinds.libretrorollback.internal.DeltaStateRing
import dev.kotlinds.libretrorollback.internal.Hello
import dev.kotlinds.libretrorollback.internal.InputQueue
import dev.kotlinds.libretrorollback.internal.LinkBatch
import dev.kotlinds.libretrorollback.internal.LinkPayload
import dev.kotlinds.libretrorollback.internal.Packet
import dev.kotlinds.libretrorollback.internal.PacketType
import dev.kotlinds.libretrorollback.internal.Protocol
import dev.kotlinds.libretrorollback.internal.ProtocolMessages
import dev.kotlinds.libretrorollback.internal.SnapshotRing
import dev.kotlinds.libretrorollback.internal.StateRing
import kotlin.random.Random

/**
 * GGPO-style rollback session for two peers.
 *
 * Call order per frame, on the thread that owns the emulator:
 *
 * ```
 * session.idle(frameBudgetMs)
 * session.addLocalInput(input)      // inputSize bytes
 * session.synchronizeInput(inputs)  // players * inputSize bytes
 * core.runFrame(inputs, render = true)
 * session.advanceFrame()            // saves the frame, may re-simulate
 * ```
 *
 * [tick] does all of that in one call when the caller does not need separate control.
 *
 * ## Linked play (two consoles)
 *
 * Attach a [LinkRadio] before [start] to route the console's local wireless through the session:
 *
 * ```
 * session.attachLink(radio)
 * session.start(localPlayer, endpoint)
 * session.prepareLink()             // hands the radio the packets scheduled for this frame
 * core.runFrame(inputs, render = true)
 * session.flushLink()               // sends what the radio transmitted
 * ```
 *
 * A radio packet is delivered to the peer's console at `producedFrame + linkDelayFrames`, so both
 * consoles see the same packet at the same emulated frame instead of whenever the network happened
 * to deliver it. Linked play works in [Mode.LOCKSTEP] and, with `linkDelayFrames` larger than
 * `maxPredictionFrames`, in [Mode.ROLLBACK]: a re-simulated frame replays the inbound packets it saw
 * from a per-frame log, holds back its own transmissions, and finally re-sends them under a new link
 * epoch so the peer drops what it had scheduled from the discarded run.
 */
class RollbackSession(
    private val core: RollbackCore,
    private val transport: Transport,
    private val config: RollbackConfig = RollbackConfig(),
    private val events: (RollbackEvent) -> Unit = {},
) : AutoCloseable {

    private enum class State { IDLE, HANDSHAKING, SYNCHRONIZED, CLOSED }

    private val sessionId: Int = Random.nextInt().let { if (it == 0) 1 else it }

    /**
     * Random token this peer puts in every packet (see [Packet.token]). The peer pins it after the
     * first HELLO; anything without it is dropped, so a third party that only learned the session id
     * (which is in every packet and therefore easier to obtain) cannot inject inputs.
     */
    private val sessionToken: Long = Random.nextLong().let { if (it == 0L) 1L else it }
    private var peerToken = 0L
    private val ring: SnapshotRing = when (config.snapshotStrategy) {
        SnapshotStrategy.FULL -> StateRing(core, config)
        SnapshotStrategy.DELTA -> DeltaStateRing(core, config)
    }
    private var queues: Array<InputQueue> = emptyArray()
    private val frameInputs = ByteArray(config.frameInputBytes)
    private val localHistory = ArrayDeque<Pair<Int, ByteArray>>()

    private var state = State.IDLE
    private var localPlayer = -1
    private var remote: Endpoint? = null
    private var frame = 0
    private var initialChecksum = 0L

    private var peerPlayer = -1
    private var peerSessionId = 0
    private var peerHello = false
    private var peerStart = false
    private var sentStart = false
    private var lastHelloMillis = 0L
    private var lastPacketMillis = 0L
    private var remoteFrame = 0

    private var rollbacks = 0
    private var maxRollbackFrames = 0
    private var predictedFrames = 0L
    private var confirmedFrames = 0L
    private var closed = false

    /**
     * `(frame, checksum)` reported by the peer, waiting to be compared with the local state.
     *
     * The comparison cannot happen when the packet arrives: the receiver may still be simulating
     * frames with predicted inputs, and the packet that would correct them has not been applied yet.
     * Verifying after the rollback (in [advanceFrame]) compares settled states instead, which is what
     * makes desync detection meaningful rather than a false positive on every deep prediction.
     */
    private val pendingChecksums = ArrayDeque<Pair<Int, Int>>()

    // ---- linked play ----
    private var link: LinkRadio? = null
    private val linkHistory = ArrayDeque<LinkBatch>()
    private val pendingLink = ArrayDeque<ScheduledLink>()
    private var lastScheduledLinkFrame = -1
    private var linkTx = 0L
    private var linkRx = 0L
    private var linkLate = 0L

    /** Radio output of the frames being re-simulated, replayed instead of the live stream. */
    private val correctedBatches = mutableListOf<LinkBatch>()

    /** What the radio received at each frame, so a rollback can replay the inbound stream verbatim. */
    private val linkLog = LinkedHashMap<Int, List<ByteArray>>()

    /** Bumped whenever a rollback invalidates radio output; the peer drops older epochs. */
    private var linkEpoch = 0
    private var peerLinkEpoch = 0

    /** First frame the current epoch invalidated (the oldest frame a rollback re-simulated). */
    private var linkInvalidatedFrom = Int.MAX_VALUE
    private var linkResends = 0L

    private class ScheduledLink(
        val batchFrame: Int,
        val deliveryFrame: Int,
        val packets: List<ByteArray>,
        val epoch: Int,
    )

    val isSynchronized: Boolean get() = state == State.SYNCHRONIZED

    val stats: RollbackStats
        get() = RollbackStats(
            frame = frame,
            rollbacks = rollbacks,
            maxRollbackFrames = maxRollbackFrames,
            predictedFrames = predictedFrames,
            confirmedFrames = confirmedFrames,
            saves = ring.saves,
            loads = ring.loads,
            saveNanos = ring.saveNanos,
            loadNanos = ring.loadNanos,
            checksums = ring.checksums,
            checksumNanos = ring.checksumNanos,
            stateBytes = ring.stateBytes,
            deltaScans = ring.deltaScans,
            dirtyHits = ring.dirtyHits,
            scanNanos = ring.scanNanos,
            remoteFrame = remoteFrame,
            linkTx = linkTx,
            linkRx = linkRx,
            linkDropped = link?.dropped ?: 0,
            linkLate = linkLate,
            linkEpoch = linkEpoch,
            linkResends = linkResends,
        )

    /** True when this session routes a console's local wireless through the frontend. */
    val isLinked: Boolean get() = link != null

    /**
     * Routes the console's local wireless through this session. Call before [start].
     *
     * In [Mode.LOCKSTEP] any link delay works. In [Mode.ROLLBACK] the delay must be larger than
     * [RollbackConfig.maxPredictionFrames]: a rollback re-simulates frames, and their radio packets
     * cannot be unsent, so the re-simulation is only safe while the peer's console has not delivered
     * them yet (it delivers at `producedFrame + linkDelayFrames`).
     */
    fun attachLink(radio: LinkRadio) {
        check(state == State.IDLE) { "attach the link radio before start()" }
        require(config.mode == Mode.LOCKSTEP || config.linkRollbackSafe) {
            "rollback with a link needs linkDelayFrames (${config.linkDelayFrames}) > " +
                "maxPredictionFrames (${config.maxPredictionFrames}): a re-simulated frame would " +
                "otherwise transmit radio packets the peer's console already received"
        }
        link = radio
    }

    /**
     * Hands the radio the packets scheduled for the current frame. Call before `core.runFrame`.
     *
     * Packets the peer's console transmitted `config.linkDelayFrames` frames ago are delivered here,
     * so the emulated radio sees them at a fixed point in emulated time on both sides. What was
     * delivered is remembered for [config.linkLogWindow] frames, so a rollback can replay it.
     */
    fun prepareLink() {
        while (true) {
            val next = pendingLink.firstOrNull() ?: break
            if (next.deliveryFrame > frame) break
            pendingLink.removeFirst()
            deliverLink(next.packets)
        }
    }

    private fun deliverLink(packets: List<ByteArray>) {
        val radio = link ?: return
        if (packets.isEmpty()) return
        for (packet in packets) {
            radio.deliver(packet)
            linkRx++
        }
        linkLog[frame] = packets
        while (linkLog.size > config.linkLogWindow) {
            val oldest = linkLog.keys.first()
            linkLog.remove(oldest)
        }
    }

    /**
     * Sends the packets the radio transmitted during the frame that just ran, tagged with that
     * frame. Call after `core.runFrame`.
     */
    fun flushLink() {
        val radio = link ?: return
        val packets = radio.drainTransmitted()
        while (linkHistory.isNotEmpty() && linkHistory.first().frame <= frame - config.linkRedundancy) {
            linkHistory.removeFirst()
        }
        if (packets.isNotEmpty()) {
            linkHistory.addLast(LinkBatch(frame, packets))
            linkTx += packets.size
        }
        if (linkHistory.isNotEmpty()) sendLink()
    }

    /** Starts the handshake. Both peers must already be at the same frame-boundary state. */
    fun start(localPlayer: Int, remote: Endpoint) {
        check(state == State.IDLE) { "session already started" }
        require(localPlayer in 0 until config.players) { "localPlayer out of range" }
        this.localPlayer = localPlayer
        this.remote = remote
        this.queues = Array(config.players) { index ->
            InputQueue(
                inputSize = config.inputSize,
                capacity = config.snapshotSlots,
                frameDelay = if (index == localPlayer) config.inputDelay else 0,
            )
        }
        this.initialChecksum = ring.save(0)
        this.state = State.HANDSHAKING
        this.lastPacketMillis = nowMillis()
        this.linkHistory.clear()
        this.pendingLink.clear()
        this.correctedBatches.clear()
        this.linkLog.clear()
        this.lastScheduledLinkFrame = -1
        this.linkEpoch = 0
        this.peerLinkEpoch = 0
        this.linkInvalidatedFrom = Int.MAX_VALUE
        this.link?.reset()
        sendHello()
    }

    /**
     * Pumps the transport. [millis] is the remaining frame budget: the engine sleeps only when it is
     * ahead of the peer or the caller asked for it.
     */
    fun idle(millis: Int = 0) {
        if (state == State.CLOSED) return
        drain()

        val now = nowMillis()
        if (state == State.HANDSHAKING && now - lastHelloMillis >= HELLO_INTERVAL_MS) sendHello()

        if (state == State.SYNCHRONIZED) {
            if (config.disconnectTimeoutMs > 0 && now - lastPacketMillis > config.disconnectTimeoutMs) {
                failDisconnected(peerPlayer, "no packet for ${now - lastPacketMillis} ms")
                return
            }
            val ahead = frame - remoteFrame
            if (frame > config.maxPredictionFrames && ahead > config.maxPredictionFrames) {
                events(RollbackEvent.Timesync(ahead))
                sleepMillis(1)
                return
            }
        }

        if (millis > 0) sleepMillis(millis.toLong())
    }

    /** Adds the local input for the current frame. Call once per frame, before [synchronizeInput]. */
    fun addLocalInput(input: ByteArray) {
        check(state == State.SYNCHRONIZED) { "not synchronized" }
        require(input.size == config.inputSize) { "input must be ${config.inputSize} bytes" }
        val queue = queues[localPlayer]
        val delayed = queue.addInput(frame, input)
        if (delayed < 0) {
            events(RollbackEvent.PredictionThreshold(frame))
            return
        }

        var next = (localHistory.lastOrNull()?.first ?: -1) + 1
        while (next <= delayed) {
            val stored = queue.inputAt(next) ?: input
            localHistory.addLast(next to stored.copyOf())
            next++
        }
        while (localHistory.size > 32) localHistory.removeFirst()
        sendInputs()
    }

    /**
     * Fills [out] with `players * inputSize` bytes: confirmed remote input, or a prediction.
     * Returns true when every player's input for this frame is confirmed. In [Mode.LOCKSTEP] the
     * caller must pump [idle] and retry until it returns true.
     */
    fun synchronizeInput(out: ByteArray): Boolean {
        require(out.size >= config.frameInputBytes) { "buffer must hold ${config.frameInputBytes} bytes" }
        var allConfirmed = true
        for (player in 0 until config.players) {
            val confirmed = queues[player].getInput(frame, out, player * config.inputSize)
            if (confirmed) confirmedFrames++ else {
                predictedFrames++
                allConfirmed = false
            }
        }
        return allConfirmed
    }

    /** Saves the frame that just ran and re-simulates if a prediction was wrong. */
    fun advanceFrame() {
        check(state == State.SYNCHRONIZED) { "not synchronized" }
        frame++
        if (frame % config.keyframeInterval == 0) ring.save(frame)
        checkSimulation()
        if (state == State.SYNCHRONIZED) verifyChecksums()
    }

    /**
     * True when both peers simulate the same console, so a per-frame state checksum is meaningful.
     * Linked play runs two consoles whose RAM legitimately differs (different player data), so its
     * checksums are not exchanged; see [RollbackConfig.linkChecksums] and the integrity note in the
     * README (linked-play integrity needs the relay/replay tier, not a same-simulation checksum).
     */
    private val canExchangeChecksums: Boolean get() = link == null || config.linkChecksums

    /** The checksum to put in a packet for [frame], or 0 when there is no shared simulation to compare. */
    private fun stateChecksum(frame: Int): Int =
        if (canExchangeChecksums) ring.checksum(frame)?.toInt() ?: 0 else 0

    /**
     * Compares the checksums the peer reported with the local state, once the local simulation of the
     * frame is *settled*: every input the peer sent for it has arrived, and any correction has already
     * been replayed (this runs after [checkSimulation]). Comparing earlier would compare a state built
     * from predicted inputs and report a desync on every deep prediction.
     *
     * A frame the ring no longer holds cannot be compared and is dropped; a frame the local side has
     * not reached yet stays queued.
     */
    private fun verifyChecksums() {
        if (!canExchangeChecksums) return
        while (true) {
            val entry = pendingChecksums.firstOrNull() ?: break
            val (peerFrame, peerChecksum) = entry
            if (peerFrame > frame) break
            if (!isFrameSettled(peerFrame)) break
            pendingChecksums.removeFirst()
            val local = ring.checksum(peerFrame) ?: continue
            if (local.toInt() != peerChecksum) {
                events(RollbackEvent.Desync(peerFrame, local, peerChecksum.toUInt().toLong()))
                state = State.CLOSED
                return
            }
        }
    }

    private fun isFrameSettled(peerFrame: Int): Boolean {
        for (queue in queues) {
            val incorrect = queue.firstIncorrectFrame()
            if (incorrect in 0 until peerFrame) return false
            if (queue.confirmedThrough >= peerFrame) continue
            // An input lost beyond the redundancy window never arrives: the prediction is what this
            // console is running with, so a mismatch here is a real divergence, not a false alarm.
            if (peerFrame > frame - config.linkRedundancy - config.maxPredictionFrames) return false
        }
        return true
    }

    /** Convenience for callers that do not need the low-level calls. */
    fun tick(localInput: ByteArray, render: Boolean = true): Boolean {
        if (state != State.SYNCHRONIZED) {
            idle(1)
            return false
        }
        idle(0)
        if (state != State.SYNCHRONIZED) return false
        addLocalInput(localInput)
        if (config.mode == Mode.LOCKSTEP) {
            var lastResend = nowMillis()
            while (state == State.SYNCHRONIZED && !synchronizeInput(frameInputs)) {
                idle(1)
                if (nowMillis() - lastResend >= 50) {
                    sendInputs()
                    lastResend = nowMillis()
                }
            }
            if (state != State.SYNCHRONIZED) return false
        } else {
            synchronizeInput(frameInputs)
        }
        prepareLink()
        core.runFrame(frameInputs, render)
        flushLink()
        advanceFrame()
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        if (state == State.SYNCHRONIZED || state == State.HANDSHAKING) {
            remote?.let { endpoint ->
                val packet = ProtocolMessages.bye(
                    sessionId, sessionToken, localPlayer, config.players, config.inputSize, "closed",
                )
                transport.send(packet, 0, packet.size, endpoint)
            }
        }
        state = State.CLOSED
        link?.reset()
        transport.close()
    }

    // ---- handshake ----

    private fun sendHello() {
        val endpoint = remote ?: return
        val fingerprint = core.fingerprint
        val hello = Hello(
            coreName = fingerprint.coreName,
            coreVersion = fingerprint.coreVersion,
            contentSha256 = fingerprint.contentSha256,
            optionsHash = fingerprint.optionsHash,
            stateSize = core.stateSize,
            initialChecksum = initialChecksum,
            inputDelay = config.inputDelay,
            maxPredictionFrames = config.maxPredictionFrames,
            linkEnabled = link != null,
            linkDelay = if (link != null) config.linkDelayFrames else 0,
        )
        val packet = ProtocolMessages.hello(sessionId, sessionToken, localPlayer, config.players, config.inputSize, hello)
        transport.send(packet, 0, packet.size, endpoint)
        lastHelloMillis = nowMillis()
    }

    private fun sendSimple(type: Int) {
        val endpoint = remote ?: return
        val packet = ProtocolMessages.simple(
            type, sessionId, sessionToken, localPlayer, config.players, config.inputSize,
        )
        transport.send(packet, 0, packet.size, endpoint)
    }

    private fun sendStart() {
        sentStart = true
        sendSimple(PacketType.START)
    }

    private fun onHello(packet: Packet) {
        val hello = ProtocolMessages.decodeHello(packet.payload) ?: return
        // A HELLO replayed later (or forged) cannot take over the session: once the peer's token is
        // pinned, only that token is accepted, and a HELLO with another one is ignored.
        if (peerHello && packet.token != peerToken) return
        peerSessionId = packet.sessionId
        if (!peerHello) {
            val reason = validate(hello)
            if (reason != null) {
                events(RollbackEvent.HandshakeFailed(reason))
                state = State.CLOSED
                return
            }
            peerHello = true
            peerPlayer = packet.player
            peerToken = packet.token
        }
        sendSimple(PacketType.HELLO_ACK)
        sendStart()
    }

    private fun onHelloAck() {
        if (peerHello && !peerStart) sendStart()
    }

    private fun onStart() {
        peerStart = true
        if (peerHello && sentStart && state == State.HANDSHAKING) {
            state = State.SYNCHRONIZED
            remoteFrame = frame
            lastPacketMillis = nowMillis()
            events(RollbackEvent.Synchronized)
        }
    }

    private fun validate(hello: Hello): String? {
        if (hello.stateSize != core.stateSize) {
            return "state size differs: local ${core.stateSize}, remote ${hello.stateSize}"
        }
        if (hello.initialChecksum != initialChecksum) {
            return "initial state differs (local ${initialChecksum.toULong().toString(16)}, " +
                "remote ${hello.initialChecksum.toULong().toString(16)})"
        }
        val fingerprint = core.fingerprint
        if (fingerprint.coreName.isNotEmpty() && hello.coreName != fingerprint.coreName) {
            return "core differs: local ${fingerprint.coreName}, remote ${hello.coreName}"
        }
        if (fingerprint.coreVersion.isNotEmpty() && hello.coreVersion != fingerprint.coreVersion) {
            return "core version differs: local ${fingerprint.coreVersion}, remote ${hello.coreVersion}"
        }
        if (fingerprint.contentSha256.isNotEmpty() && hello.contentSha256 != fingerprint.contentSha256) {
            return "content differs"
        }
        if (fingerprint.optionsHash != 0L && hello.optionsHash != fingerprint.optionsHash) {
            return "core options differ"
        }
        if (hello.inputDelay != config.inputDelay) {
            return "input delay differs: local ${config.inputDelay}, remote ${hello.inputDelay}"
        }
        if (hello.maxPredictionFrames != config.maxPredictionFrames) {
            return "prediction limit differs"
        }
        if (hello.linkEnabled != (link != null)) {
            return if (link != null) {
                "the peer did not enable linked play"
            } else {
                "the peer enabled linked play"
            }
        }
        if (link != null && hello.linkDelay != config.linkDelayFrames) {
            return "link delay differs: local ${config.linkDelayFrames}, remote ${hello.linkDelay}"
        }
        return null
    }

    // ---- packets ----

    private fun drain() {
        for (datagram in transport.receive()) {
            // Only the peer we started the session with is heard, whatever the packet carries: a
            // third party that can reach the port cannot pretend to be it (see [Packet.token] for
            // the second lock).
            if (!isFromPeer(datagram.from)) continue
            val packet = Protocol.decode(datagram.data, datagram.length) ?: continue
            if (packet.type != PacketType.HELLO && packet.token != peerToken) continue
            if (packet.type != PacketType.HELLO &&
                packet.sessionId != sessionId &&
                packet.sessionId != peerSessionId
            ) continue
            if (packet.players != config.players || packet.inputSize != config.inputSize) continue
            lastPacketMillis = nowMillis()
            when (packet.type) {
                PacketType.HELLO -> onHello(packet)
                PacketType.HELLO_ACK -> onHelloAck()
                PacketType.START -> onStart()
                PacketType.INPUT -> onInput(packet)
                PacketType.LINK -> onLink(packet)
                PacketType.DESYNC -> onDesync(packet)
                PacketType.BYE -> failDisconnected(
                    packet.player,
                    ProtocolMessages.decodeReason(packet.payload) ?: "peer left",
                )
            }
        }
    }

    /**
     * True when [from] is the endpoint the session was started with. The port always has to match;
     * the host is compared when the configured peer is an IP literal, and left out when it is a name
     * (the resolved address the transport reports may differ from the name). The session token is what
     * actually authenticates the peer.
     */
    private fun isFromPeer(from: Endpoint): Boolean {
        val remote = remote ?: return false
        if (from.port != remote.port) return false
        if (remote.host.any { it.isLetter() }) return true
        return from.host == remote.host
    }

    private fun onInput(packet: Packet) {
        if (state != State.SYNCHRONIZED) return
        if (packet.player == localPlayer || packet.player !in 0 until config.players) return
        if (canExchangeChecksums && packet.checksum != 0 && packet.ackFrame >= 0) {
            pendingChecksums.addLast(packet.ackFrame to packet.checksum)
            trimPendingChecksums()
        }
        val payload = ProtocolMessages.decodeInput(packet.payload, config.inputSize) ?: return
        for (index in 0 until payload.count) {
            val inputFrame = payload.startFrame + index
            val input = payload.inputs.copyOfRange(
                index * config.inputSize,
                (index + 1) * config.inputSize,
            )
            queues[packet.player].addInput(inputFrame, input)
        }
        remoteFrame = maxOf(remoteFrame, payload.startFrame + payload.count - 1)
    }

    /**
     * Local wireless from the peer. Every batch is scheduled for `batch.frame + linkDelayFrames`;
     * batches already scheduled (redundant re-sends) are ignored, and batches whose frame has
     * already passed are counted as late. Nothing here waits: the DS's radio protocol retransmits,
     * and blocking the emulator thread for the network would stall both consoles.
     *
     * A higher [LinkPayload.epoch] means the peer rolled back: everything it still has scheduled came
     * from a run that no longer exists, so it is dropped and the corrected batches are accepted.
     */
    private fun onLink(packet: Packet) {
        if (state != State.SYNCHRONIZED) return
        if (packet.player == localPlayer) return
        val payload = ProtocolMessages.decodeLink(packet.payload) ?: return
        if (payload.epoch < peerLinkEpoch) return
        if (canExchangeChecksums && packet.checksum != 0 && packet.ackFrame >= 0) {
            pendingChecksums.addLast(packet.ackFrame to packet.checksum)
            trimPendingChecksums()
        }
        if (payload.epoch > peerLinkEpoch) {
            peerLinkEpoch = payload.epoch
            // Only the frames the sender re-simulated are stale; earlier ones keep their schedule.
            pendingLink.removeAll { it.epoch < peerLinkEpoch && it.batchFrame >= payload.invalidatedFrom }
            lastScheduledLinkFrame = minOf(lastScheduledLinkFrame, payload.invalidatedFrom - 1)
        }
        for (batch in payload.batches) {
            if (batch.frame <= lastScheduledLinkFrame) continue
            val delivery = batch.frame + config.linkDelayFrames
            if (delivery < frame) {
                linkLate++
                continue
            }
            lastScheduledLinkFrame = batch.frame
            pendingLink.addLast(ScheduledLink(batch.frame, delivery, batch.packets, payload.epoch))
        }
    }

    /**
     * The queue only needs to hold checksums for frames the ring can still verify; anything older is
     * dropped (its frame was overwritten before the peer reported it, which happens when the peer is
     * much slower than the ring window).
     */
    private fun trimPendingChecksums() {
        val oldest = frame - config.snapshotWindow
        while (pendingChecksums.isNotEmpty() && pendingChecksums.first().first < oldest) {
            pendingChecksums.removeFirst()
        }
    }

    private fun onDesync(packet: Packet) {
        val (otherFrame, localChecksum, remoteChecksum) =
            ProtocolMessages.decodeDesync(packet.payload) ?: return
        events(RollbackEvent.Desync(otherFrame, localChecksum, remoteChecksum))
        state = State.CLOSED
    }

    /**
     * Highest frame whose *starting* state is final on this side: every input up to the previous frame
     * has arrived and no correction is pending. Only such a frame may be reported to the peer for
     * desync detection — a checksum of a frame still being predicted would be retracted by the
     * rollback that later corrects it, and the peer would report a desync that never happened.
     */
    private fun settledFrame(): Int {
        var settled = frame
        for (queue in queues) {
            settled = minOf(settled, queue.confirmedThrough + 1)
            // A frame whose input turned out to be wrong is still waiting for its rollback (this runs
            // before checkSimulation): the state from it on is provisional.
            val incorrect = queue.firstIncorrectFrame()
            if (incorrect >= 0) settled = minOf(settled, incorrect)
        }
        // An input lost beyond the redundancy window never arrives: the prediction is what this
        // console is running with, so the frame counts as settled and a mismatch is a real divergence.
        val giveUp = frame - config.linkRedundancy - config.maxPredictionFrames
        if (settled < giveUp) settled = giveUp
        return settled.coerceIn(0, frame)
    }

    private fun sendInputs() {
        val endpoint = remote ?: return
        if (localHistory.isEmpty()) return
        val count = minOf(REDUNDANT_INPUTS, localHistory.size)
        val first = localHistory[localHistory.size - count].first
        val inputs = ByteArray(count * config.inputSize)
        for (index in 0 until count) {
            localHistory[localHistory.size - count + index].second.copyInto(inputs, index * config.inputSize)
        }
        val settled = settledFrame()
        val packet = ProtocolMessages.input(
            sessionId = sessionId,
            token = sessionToken,
            player = localPlayer,
            players = config.players,
            inputSize = config.inputSize,
            frame = frame,
            ackFrame = settled,
            checksum = stateChecksum(settled),
            startFrame = first,
            inputs = inputs,
            count = count,
        )
        transport.send(packet, 0, packet.size, endpoint)
    }

    private fun sendLink() {
        val endpoint = remote ?: return
        // Newest batches last, oldest dropped first when they do not fit: the peer can ignore the
        // oldest without losing anything, because it schedules deliveries in frame order.
        val batches = ArrayList<LinkBatch>(linkHistory.size)
        var bytes = 0
        for (index in linkHistory.indices.reversed()) {
            val batch = linkHistory[index]
            val size = 5 + batch.packets.sumOf { packet -> 2 + packet.size }
            if (batches.isNotEmpty() && bytes + size > config.linkMaxPayloadBytes) break
            bytes += size
            batches.add(0, batch)
        }
        if (batches.isEmpty()) return
        val settled = settledFrame()
        val packet = ProtocolMessages.link(
            sessionId = sessionId,
            token = sessionToken,
            player = localPlayer,
            players = config.players,
            inputSize = config.inputSize,
            frame = frame,
            ackFrame = settled,
            checksum = stateChecksum(settled),
            epoch = linkEpoch,
            invalidatedFrom = linkInvalidatedFrom,
            batches = batches,
        )
        transport.send(packet, 0, packet.size, endpoint)
    }

    // ---- rollback ----

    private fun checkSimulation() {
        if (config.mode == Mode.LOCKSTEP) return
        val seekTo = queues
            .mapNotNull { queue -> queue.firstIncorrectFrame().takeIf { it >= 0 } }
            .minOrNull() ?: return
        val target = frame
        if (seekTo >= target) return

        val restored = ring.loadAtOrBefore(seekTo)
        if (restored == null) {
            events(RollbackEvent.Desync(seekTo, ring.checksum(seekTo) ?: 0L, 0L))
            state = State.CLOSED
            return
        }

        // Linked play: a radio packet cannot be unsent. The frames about to be re-simulated produced
        // packets that the peer's console may already have received (it delivers them at
        // `producedFrame + linkDelayFrames`), and then the two consoles could not be brought back
        // together. Refuse instead of diverging silently.
        if (link != null && restored + config.linkDelayFrames <= remoteFrame) {
            events(RollbackEvent.LinkRollbackRefused(restored, target - restored, config.linkDelayFrames))
            state = State.CLOSED
            return
        }

        val replay = target - restored
        frame = restored
        queues.forEach { it.resetPrediction(restored) }
        if (link != null) beginLinkReplay(target)
        repeat(replay) {
            synchronizeInput(frameInputs)
            if (link != null) replayLink()
            core.runFrame(frameInputs, render = false)
            if (link != null) collectLinkReplay()
            frame++
            val save = frame == target ||
                (config.saveDuringRollback && frame % config.keyframeInterval == 0)
            if (save) ring.save(frame)
        }
        if (link != null) finishLinkReplay(restored)
        rollbacks++
        if (replay > maxRollbackFrames) maxRollbackFrames = replay
        events(RollbackEvent.Rollback(target, replay))
    }

    // ---- linked play during a rollback ----

    /**
     * Prepares the re-simulation of `[frame, target]`. Deliveries already scheduled for those frames
     * are dropped: they are replayed from the log instead, exactly as the radio saw them the first
     * time. Transmissions are collected rather than sent, so the peer only ever sees the final run.
     */
    private fun beginLinkReplay(target: Int) {
        val radio = link ?: return
        pendingLink.removeAll { it.deliveryFrame <= target }
        correctedBatches.clear()
        radio.reset()
    }

    /** Hands the radio what it received at this frame during the original run. */
    private fun replayLink() {
        val radio = link ?: return
        val packets = linkLog[frame] ?: return
        for (packet in packets) radio.deliver(packet)
    }

    private fun collectLinkReplay() {
        val radio = link ?: return
        val packets = radio.drainTransmitted()
        if (packets.isNotEmpty()) correctedBatches.add(LinkBatch(frame, packets))
    }

    /**
     * Publishes the re-simulated radio output: the batches sent for frames from [restored] on belong
     * to a run that no longer exists, so they are replaced and the peer is told to drop what it still
     * has scheduled by bumping the link epoch.
     */
    private fun finishLinkReplay(restored: Int) {
        linkEpoch++
        // The oldest frame this epoch invalidated: the receiver keeps its schedule for older frames.
        linkInvalidatedFrom = minOf(linkInvalidatedFrom, restored)
        linkHistory.removeAll { it.frame >= restored }
        linkHistory.addAll(correctedBatches)
        linkResends += correctedBatches.size
        correctedBatches.clear()
        sendLink()
    }

    private fun failDisconnected(player: Int, reason: String) {
        events(RollbackEvent.Disconnected(player, reason))
        state = State.CLOSED
    }


    private companion object {
        const val HELLO_INTERVAL_MS = 100L
        const val REDUNDANT_INPUTS = 8
    }
}
