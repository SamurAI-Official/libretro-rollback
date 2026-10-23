package dev.kotlinds.libretrorollback.internal

/**
 * A ring of snapshots the session can save into and restore from.
 *
 * [StateRing] keeps one full serialized state per frame; [DeltaStateRing] keeps a keyframe plus
 * 4 KiB page deltas. Both call `RollbackCore.saveState` / `loadState`; only the storage differs.
 */
internal interface SnapshotRing {
    /** Saves the current state as [frame] and returns its checksum (0 when checksums are disabled). */
    fun save(frame: Int): Long

    /** Restores exactly [frame]. Returns false when that frame is no longer in the ring. */
    fun load(frame: Int): Boolean

    /** Restores the newest state at or before [frame]; returns the frame restored, or null. */
    fun loadAtOrBefore(frame: Int): Int?

    fun checksum(frame: Int): Long?

    /** Bytes currently held by snapshots (and working buffers for the delta ring). */
    val stateBytes: Long

    val saves: Long
    val loads: Long
    val saveNanos: Long
    val loadNanos: Long
    val checksums: Long
    val checksumNanos: Long

    /** Deltas built by scanning the buffer (no core dirty-page API). */
    val deltaScans: Long

    /** Deltas built from a core dirty-page bitmap. */
    val dirtyHits: Long

    /** Time spent scanning the buffer for changed pages. */
    val scanNanos: Long

    fun clear()
}
