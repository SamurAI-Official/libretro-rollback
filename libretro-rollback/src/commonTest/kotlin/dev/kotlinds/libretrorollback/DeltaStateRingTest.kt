package dev.kotlinds.libretrorollback

import dev.kotlinds.libretrorollback.internal.DeltaStateRing
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeltaStateRingTest {

    /** Core whose state is a byte array with a few bytes changing on every page each frame. */
    private class PagedCore(
        override val stateSize: Int = 32 * 1024,
        private val touchedPages: Int = 4,
        private val reportDirty: Boolean = false,
    ) : RollbackCore {
        private val state = ByteArray(stateSize)
        private val dirty = ByteArray((stateSize + 4095) / 4096)
        private var frame = 0

        override val fingerprint: CoreFingerprint = CoreFingerprint()

        override fun saveState(into: ByteArray): Int {
            state.copyInto(into)
            return stateSize
        }

        override fun loadState(from: ByteArray, size: Int) {
            from.copyInto(state, 0, 0, size)
        }

        override fun runFrame(inputs: ByteArray, render: Boolean) {
            for (page in 0 until touchedPages) {
                val offset = (page * (stateSize / touchedPages)) % stateSize
                state[offset] = (state[offset] + 1).toByte()
                dirty[offset / 4096] = 1
            }
            state[0] = frame++.toByte()
            dirty[0] = 1
        }

        override fun dirtyPages(out: ByteArray): Int {
            if (!reportDirty) return 0
            if (out.size < dirty.size) return 0
            dirty.copyInto(out)
            dirty.fill(0)
            return dirty.size
        }
    }

    @Test
    fun deltasReconstructEveryFrame() {
        val core = PagedCore()
        val config = RollbackConfig(
            snapshotStrategy = SnapshotStrategy.DELTA,
            deltaKeyframeInterval = 40,
        )
        val ring = DeltaStateRing(core, config)

        val reference = mutableListOf<ByteArray>()
        for (frame in 0..40) {
            if (frame > 0) core.runFrame(ByteArray(0), render = false)
            val snapshot = ByteArray(core.stateSize)
            core.saveState(snapshot)
            reference += snapshot
            ring.save(frame)
        }

        for (frame in reference.indices) {
            assertTrue(ring.load(frame), "frame $frame not in the ring")
            val restored = ByteArray(core.stateSize)
            core.saveState(restored)
            assertContentEquals(reference[frame], restored, "frame $frame differs")
        }

        // Deltas must be much smaller than full states.
        assertTrue(
            ring.bytes < core.stateSize.toLong() * reference.size,
            "delta ring used ${ring.bytes} bytes for ${reference.size} frames",
        )
    }

    @Test
    fun keyframesBoundTheReconstruction() {
        val core = PagedCore()
        val config = RollbackConfig(
            snapshotStrategy = SnapshotStrategy.DELTA,
            deltaKeyframeInterval = 8,
            maxPredictionFrames = 4,
        )
        val ring = DeltaStateRing(core, config)
        val reference = mutableMapOf<Int, ByteArray>()
        for (frame in 0..40) {
            if (frame > 0) core.runFrame(ByteArray(0), render = false)
            val snapshot = ByteArray(core.stateSize)
            core.saveState(snapshot)
            reference[frame] = snapshot
            ring.save(frame)
        }

        // Every frame in the window must still be reconstructible.
        for (frame in 30..40) {
            assertTrue(ring.load(frame), "frame $frame not in the ring")
            val restored = ByteArray(core.stateSize)
            core.saveState(restored)
            assertContentEquals(reference[frame]!!, restored)
        }
    }

    @Test
    fun coreDirtyPagesSkipTheScan() {
        val core = PagedCore(reportDirty = true)
        val config = RollbackConfig(
            snapshotStrategy = SnapshotStrategy.DELTA,
            deltaKeyframeInterval = 8,
        )
        val ring = DeltaStateRing(core, config)
        val reference = mutableMapOf<Int, ByteArray>()
        for (frame in 0..40) {
            if (frame > 0) core.runFrame(ByteArray(0), render = false)
            val snapshot = ByteArray(core.stateSize)
            core.saveState(snapshot)
            reference[frame] = snapshot
            ring.save(frame)
        }

        assertTrue(ring.dirtyHits > 0, "expected dirty-page deltas")
        assertEquals(0, ring.deltaScans)

        for (frame in 30..40) {
            assertTrue(ring.load(frame), "frame $frame not in the ring")
            val restored = ByteArray(core.stateSize)
            core.saveState(restored)
            assertContentEquals(reference[frame]!!, restored)
        }
    }

    @Test
    fun memoryGuardRejectsTinyLimits() {
        val core = PagedCore(stateSize = 64 * 1024)
        assertFailsWith<IllegalArgumentException> {
            DeltaStateRing(
                core,
                RollbackConfig(snapshotStrategy = SnapshotStrategy.DELTA, maxStateBytes = 1024),
            )
        }
    }
}
