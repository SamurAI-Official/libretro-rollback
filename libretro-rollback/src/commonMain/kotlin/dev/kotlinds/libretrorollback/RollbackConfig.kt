package dev.kotlinds.libretrorollback

enum class Mode {
    /** Predict the peer's input and re-simulate when a prediction was wrong. Needs a deterministic core. */
    ROLLBACK,

    /** Wait for the peer's input before running a frame. Deterministic even when save/load is unreliable. */
    LOCKSTEP,
}

/** How the state ring stores snapshots. */
enum class SnapshotStrategy {
    /** One full serialized state per frame (GGPO's default). Simple, memory-hungry. */
    FULL,

    /**
     * A full keyframe every [RollbackConfig.deltaKeyframeInterval] frames and 4 KiB page deltas in
     * between. `retro_serialize` / `retro_unserialize` are still called every frame (libretro is
     * opaque), but the ring keeps only the pages that changed, so memory drops sharply for
     * DS-sized states. Loading reconstructs the full buffer from the keyframe plus the deltas before
     * calling `retro_unserialize`.
     *
     * A core-level dirty-page API would remove the per-frame page scan; this strategy works with any
     * core today.
     */
    DELTA,
}

/**
 * @param players number of local + remote players (2 for the first version).
 * @param inputSize opaque bytes per player. The engine only copies and predicts them; the adapter
 *   decides what they mean (buttons, touch, analog).
 * @param maxPredictionFrames how far ahead the engine may run before it must wait for the peer.
 * @param inputDelay local input delay in frames. Higher hides jitter, lower feels more responsive.
 * @param mode rollback or delay-based lockstep.
 * @param checksumEveryFrames how often a state checksum is kept for desync detection (0 = never).
 * @param keyframeInterval save a state every N frames. 1 saves every frame (GGPO's default);
 *   larger values trade rollback replay time for fewer snapshots.
 * @param snapshotStrategy full states or keyframes + page deltas.
 * @param deltaKeyframeInterval keyframe distance when [snapshotStrategy] is [SnapshotStrategy.DELTA].
 *   Rollbacks reconstruct at most this many deltas.
 * @param disconnectTimeoutMs no packet for this long => peer disconnected.
 * @param maxStateBytes failure guard: the ring refuses to allocate more than this.
 * @param linkDelayFrames frames between the local radio transmitting a packet and the peer's
 *   console receiving it. Both peers must use the same value; it is the link's equivalent of
 *   [inputDelay] and hides network jitter from the emulated radio.
 * @param linkRedundancy how many recent frames of radio traffic every link packet repeats, so a
 *   lost packet is recovered by the next one.
 * @param linkQueueDepth radio receive queue depth; a packet arriving with a full queue drops the
 *   oldest, like the hardware.
 * @param linkMaxPayloadBytes largest link packet. Batches that do not fit are left to the next one.
 */
data class RollbackConfig(
    val players: Int = 2,
    val inputSize: Int = 16,
    val maxPredictionFrames: Int = 8,
    val inputDelay: Int = 2,
    val mode: Mode = Mode.ROLLBACK,
    val checksumEveryFrames: Int = 1,
    val keyframeInterval: Int = 1,
    val snapshotStrategy: SnapshotStrategy = SnapshotStrategy.FULL,
    val deltaKeyframeInterval: Int = 30,
    /**
     * Compare per-frame state checksums in linked play (off by default).
     *
     * Linked play runs two consoles, one per peer: their RAM legitimately differs (each game holds its
     * own player's data), so the same-simulation comparison of the two-controller topology does not
     * apply and would report a desync as soon as a real wireless session starts. Leave it off, and
     * turn it on only for a deterministic echo test where both peers really run the same state.
     *
     * With it off, a linked session has no state-desync detection: nothing in a peer-to-peer protocol
     * can prove that the other console was not modified locally. That is what the relay/replay tier is
     * for (see the README, "Integrity"), not a checksum over two different machines.
     */
    val linkChecksums: Boolean = false,

    /**
     * Save a snapshot for every replayed frame during a rollback (the default). The frames being
     * replayed were simulated with inputs that turned out to be wrong, so their snapshots and
     * checksums describe a run that no longer exists: keeping them would let a later rollback restore
     * a stale state and make desync verification compare stale checksums. Re-saving costs one save per
     * replayed frame, and rollbacks replay few frames. Set false to keep the old GGPO-style "late
     * save" behavior (fewer saves, stale snapshots for the replayed frames).
     */
    val saveDuringRollback: Boolean = true,
    val disconnectTimeoutMs: Int = 5_000,
    val maxStateBytes: Long = 256L * 1024 * 1024,
    val linkDelayFrames: Int = 3,
    val linkRedundancy: Int = 4,
    val linkQueueDepth: Int = 16,
    val linkMaxPayloadBytes: Int = 1100,
) {
    init {
        require(players in 1..4) { "players must be 1..4" }
        require(inputSize in 1..256) { "inputSize must be 1..256" }
        require(maxPredictionFrames in 1..32) { "maxPredictionFrames must be 1..32" }
        require(inputDelay in 0..32) { "inputDelay must be 0..32" }
        require(checksumEveryFrames >= 0) { "checksumEveryFrames must be >= 0" }
        require(keyframeInterval >= 1) { "keyframeInterval must be >= 1" }
        require(deltaKeyframeInterval >= 1) { "deltaKeyframeInterval must be >= 1" }
        require(disconnectTimeoutMs >= 0) { "disconnectTimeoutMs must be >= 0" }
        require(linkDelayFrames in 0..32) { "linkDelayFrames must be 0..32" }
        require(linkRedundancy in 1..16) { "linkRedundancy must be 1..16" }
        require(linkQueueDepth in 1..64) { "linkQueueDepth must be 1..64" }
        require(linkMaxPayloadBytes >= 64) { "linkMaxPayloadBytes must be >= 64" }
    }

    /** Bytes for all players in one frame. */
    val frameInputBytes: Int get() = players * inputSize

    /** Number of snapshots the full-state ring keeps. */
    val snapshotSlots: Int get() = maxPredictionFrames + keyframeInterval + 2

    /** Frames of snapshots the delta ring keeps before dropping the oldest keyframe. */
    val snapshotWindow: Int get() = maxPredictionFrames + deltaKeyframeInterval + 2

    /**
     * Frames of *delivered* radio traffic a linked session keeps, so a rollback can hand the radio
     * exactly what it saw during the original run. It has to cover the deepest re-simulation, which
     * is bounded by the snapshot window.
     */
    val linkLogWindow: Int get() = snapshotWindow

    /**
     * Rollback with a link is only safe while the invalidated radio packets have not reached the
     * peer's console yet (it delivers them [linkDelayFrames] after they were produced). A static
     * guarantee that the deepest possible rollback stays inside that window.
     */
    val linkRollbackSafe: Boolean get() = linkDelayFrames > maxPredictionFrames
}
