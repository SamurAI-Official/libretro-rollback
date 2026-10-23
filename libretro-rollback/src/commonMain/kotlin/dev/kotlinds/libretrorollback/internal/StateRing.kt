package dev.kotlinds.libretrorollback.internal

import dev.kotlinds.libretrorollback.RollbackConfig
import dev.kotlinds.libretrorollback.RollbackCore
import dev.kotlinds.libretrorollback.nowNanos
import dev.kotlinds.libretrorollback.stateHash

/**
 * Ring of serialized states, one per frame (or one per keyframe when [RollbackConfig.keyframeInterval]
 * is larger than 1). Buffers are allocated once and reused, like GGPO's `SavedFrame` ring.
 */
internal class StateRing(
    private val core: RollbackCore,
    private val config: RollbackConfig,
    slots: Int = config.snapshotSlots,
) : SnapshotRing {
    private class Slot {
        var frame: Int = -1
        var length: Int = 0
        var data: ByteArray = ByteArray(0)
        var checksum: Long = 0L
    }

    private val slots = Array(slots) { Slot() }
    private var head = 0

    override var saves: Long = 0
        private set
    override var loads: Long = 0
        private set
    override var saveNanos: Long = 0
        private set
    override var loadNanos: Long = 0
        private set
    override var checksums: Long = 0
        private set
    override var checksumNanos: Long = 0
        private set

    override val stateBytes: Long get() = slots.sumOf { it.data.size.toLong() }
    override val deltaScans: Long get() = 0
    override val dirtyHits: Long get() = 0
    override val scanNanos: Long get() = 0

    init {
        require(core.stateSize > 0) { "core.stateSize must be > 0" }
        val bytes = core.stateSize.toLong() * slots
        require(bytes <= config.maxStateBytes) {
            "state ring would allocate $bytes bytes (limit ${config.maxStateBytes}); " +
                "reduce maxPredictionFrames or raise maxStateBytes"
        }
    }

    /** Saves the current state as [frame] and returns its checksum (0 when checksums are disabled). */
    override fun save(frame: Int): Long {
        // A rollback re-saves frames the ring still holds. Their old snapshots describe a run that no
        // longer exists, so they are dropped first: keeping them would let loadAtOrBefore() restore a
        // stale state and checksum() report a stale checksum.
        for (slot in slots) {
            if (slot.frame >= frame) {
                slot.frame = -1
                slot.length = 0
                slot.checksum = 0L
            }
        }

        val slot = slots[head]
        if (slot.data.size < core.stateSize) slot.data = ByteArray(core.stateSize)
        val start = nowNanos()
        val length = core.saveState(slot.data)
        saveNanos += nowNanos() - start
        saves++
        slot.frame = frame
        slot.length = length
        slot.checksum = if (config.checksumEveryFrames > 0) {
            val checksumStart = nowNanos()
            // A core-specific checksum (e.g. main RAM) avoids false desyncs from savestate padding.
            val value = core.stateChecksum() ?: stateHash(slot.data, 0, length)
            checksumNanos += nowNanos() - checksumStart
            checksums++
            value
        } else {
            0L
        }
        head = (head + 1) % slots.size
        return slot.checksum
    }

    /** Restores exactly [frame]. Returns false when that frame is no longer in the ring. */
    override fun load(frame: Int): Boolean {
        val slot = slots.firstOrNull { it.frame == frame } ?: return false
        val start = nowNanos()
        core.loadState(slot.data, slot.length)
        loadNanos += nowNanos() - start
        loads++
        return true
    }

    /** Restores the newest state at or before [frame]; returns the frame restored, or null. */
    override fun loadAtOrBefore(frame: Int): Int? {
        val slot = slots.filter { it.frame in 0..frame }.maxByOrNull { it.frame } ?: return null
        val start = nowNanos()
        core.loadState(slot.data, slot.length)
        loadNanos += nowNanos() - start
        loads++
        return slot.frame
    }

    override fun checksum(frame: Int): Long? = slots.firstOrNull { it.frame == frame && it.frame >= 0 }?.checksum

    override fun clear() {
        for (slot in slots) {
            slot.frame = -1
            slot.length = 0
            slot.checksum = 0L
        }
        head = 0
    }
}
