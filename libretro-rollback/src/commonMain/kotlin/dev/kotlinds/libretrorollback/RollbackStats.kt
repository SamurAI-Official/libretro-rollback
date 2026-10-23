package dev.kotlinds.libretrorollback

data class RollbackStats(
    /** Current engine frame. */
    val frame: Int = 0,
    /** Number of rollbacks performed. */
    val rollbacks: Int = 0,
    /** Deepest rollback (frames re-simulated). */
    val maxRollbackFrames: Int = 0,
    /** Frames whose remote input had to be predicted. */
    val predictedFrames: Long = 0,
    /** Frames whose remote input was already confirmed. */
    val confirmedFrames: Long = 0,
    val saves: Long = 0,
    val loads: Long = 0,
    val saveNanos: Long = 0,
    val loadNanos: Long = 0,
    val checksums: Long = 0,
    val checksumNanos: Long = 0,
    /** Bytes held by the snapshot ring (full states or keyframes + deltas). */
    val stateBytes: Long = 0,
    /** Deltas built by scanning the buffer (no core dirty-page API). */
    val deltaScans: Long = 0,
    /** Deltas built from a core dirty-page bitmap. */
    val dirtyHits: Long = 0,
    /** Time spent scanning the buffer for changed pages. */
    val scanNanos: Long = 0,
    /** Last frame the peer reported. */
    val remoteFrame: Int = -1,
    /** Radio packets this console transmitted and sent to the peer. */
    val linkTx: Long = 0,
    /** Radio packets the peer's console transmitted and this console's radio received. */
    val linkRx: Long = 0,
    /** Packets dropped because the radio's receive queue was full. */
    val linkDropped: Long = 0,
    /** Radio packets that arrived after the frame they were scheduled for (packet loss). */
    val linkLate: Long = 0,
    /** Link epochs: how many times a rollback invalidated radio output and the peer was told. */
    val linkEpoch: Int = 0,
    /** Batches re-sent after a rollback (their frames had already been sent once). */
    val linkResends: Long = 0,
) {
    val averageSaveMillis: Double get() = if (saves == 0L) 0.0 else saveNanos / 1_000_000.0 / saves
    val averageLoadMillis: Double get() = if (loads == 0L) 0.0 else loadNanos / 1_000_000.0 / loads
    val averageChecksumMillis: Double get() =
        if (checksums == 0L) 0.0 else checksumNanos / 1_000_000.0 / checksums
    val averageScanMillis: Double get() =
        if (saves == 0L) 0.0 else scanNanos / 1_000_000.0 / saves
}
