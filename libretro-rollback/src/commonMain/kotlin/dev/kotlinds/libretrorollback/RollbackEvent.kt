package dev.kotlinds.libretrorollback

sealed interface RollbackEvent {
    /** The handshake finished and frames can run. */
    data object Synchronized : RollbackEvent

    /** The peer is incompatible (core, content, options or initial state). */
    data class HandshakeFailed(val reason: String) : RollbackEvent

    /** Local and remote state checksums differ at [frame]. The session stops. */
    data class Desync(val frame: Int, val localChecksum: Long, val remoteChecksum: Long) : RollbackEvent

    /** No packet from [player] within the configured timeout. */
    data class Disconnected(val player: Int, val reason: String) : RollbackEvent

    /** The engine suggests sleeping [framesAhead] frames to let the peer catch up. */
    data class Timesync(val framesAhead: Int) : RollbackEvent

    /** The local input queue is full: the peer is too far behind. */
    data class PredictionThreshold(val frame: Int) : RollbackEvent

    /**
     * Linked play only: the rollback would have re-simulated frames whose radio packets the peer's
     * console has already received, and a transmitted packet cannot be unsent. The two consoles
     * cannot be brought back together, so the session stops. Raise
     * [RollbackConfig.linkDelayFrames] (or lower [RollbackConfig.maxPredictionFrames]) to prevent it.
     */
    data class LinkRollbackRefused(
        val frame: Int,
        val replayFrames: Int,
        val linkDelayFrames: Int,
    ) : RollbackEvent

    /**
     * A rollback just re-simulated [replayFrames] frames ending at [toFrame]. Hosts should drop
     * audio/video produced by the discarded frames and resync their audio buffer here.
     */
    data class Rollback(val toFrame: Int, val replayFrames: Int) : RollbackEvent
}
