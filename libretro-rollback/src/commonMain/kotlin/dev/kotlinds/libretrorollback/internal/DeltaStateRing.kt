package dev.kotlinds.libretrorollback.internal

import dev.kotlinds.libretrorollback.RollbackConfig
import dev.kotlinds.libretrorollback.RollbackCore
import dev.kotlinds.libretrorollback.nowNanos
import dev.kotlinds.libretrorollback.stateHash

/**
 * Incremental snapshot ring: a full keyframe every [RollbackConfig.deltaKeyframeInterval] frames and
 * a 4 KiB page delta for every other frame.
 *
 * `retro_serialize` / `retro_unserialize` are still called every frame (libretro is opaque), but the
 * ring stores only the pages that changed. Loading reconstructs the full buffer from the newest
 * keyframe plus the deltas up to the target frame and then calls `retro_unserialize`.
 *
 * A core-level dirty-page API would remove the per-frame page scan; this strategy works with any
 * core today.
 */
internal class DeltaStateRing(
    private val core: RollbackCore,
    private val config: RollbackConfig,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : SnapshotRing {

    private class Keyframe(val frame: Int, val length: Int, val data: ByteArray)

    private class Delta(
        val frame: Int,
        val length: Int,
        val pageIndices: IntArray,
        val pageData: ByteArray,
    ) {
        val bytes: Long get() = pageData.size.toLong() + pageIndices.size * 4L
    }

    private val keyframes = ArrayDeque<Keyframe>()
    private val deltas = ArrayDeque<Delta>()
    private val checksumsByFrame = HashMap<Int, Long>()

    private var current = ByteArray(0)
    private var scratch = ByteArray(0)
    private var reconstruct = ByteArray(0)
    private var currentLength = 0
    private var lastFrame = -1
    private var bytesUsed = 0L
    private val dirtyBitmap = ByteArray((core.stateSize + pageSize - 1) / pageSize)

    /** Deltas built by scanning the buffer (no core dirty-page API). */
    override var deltaScans: Long = 0
        private set

    /** Deltas built from the core's dirty-page bitmap. */
    override var dirtyHits: Long = 0
        private set

    /** Time spent scanning the buffer for changed pages. */
    override var scanNanos: Long = 0
        private set

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

    /** Bytes currently held by keyframes and deltas (diagnostics). */
    val bytes: Long get() = bytesUsed

    override val stateBytes: Long get() = bytesUsed + 3L * core.stateSize

    init {
        require(core.stateSize > 0) { "core.stateSize must be > 0" }
        require(pageSize > 0 && pageSize and (pageSize - 1) == 0) { "pageSize must be a power of two" }
        val base = core.stateSize.toLong() * 3
        require(base <= config.maxStateBytes) {
            "delta ring needs $base bytes of working buffers (limit ${config.maxStateBytes})"
        }
        current = ByteArray(core.stateSize)
        scratch = ByteArray(core.stateSize)
        reconstruct = ByteArray(core.stateSize)
    }

    override fun save(frame: Int): Long {
        // A rollback re-saves frames the ring still holds; their keyframes, deltas and checksums
        // describe a run that no longer exists. Dropping them keeps reconstruction exact (a duplicate
        // delta would break the frame chain) and checksums fresh.
        while (keyframes.isNotEmpty() && keyframes.last().frame >= frame) {
            bytesUsed -= keyframes.removeLast().length
        }
        while (deltas.isNotEmpty() && deltas.last().frame >= frame) {
            bytesUsed -= deltas.removeLast().bytes
        }
        val staleChecksums = checksumsByFrame.keys.filter { it >= frame }
        for (key in staleChecksums) checksumsByFrame.remove(key)

        scratch.fill(0)
        val start = nowNanos()
        val maxLength = core.saveState(scratch)
        saveNanos += nowNanos() - start
        saves++

        // A core with a dirty-page API also tells us how many pages the state actually uses, which
        // is less than retro_serialize_size() (the buffer is sized for the worst case). Use that
        // length so the dirty bitmap lines up with the delta pages and keyframes stay small.
        val reported = core.dirtyPages(dirtyBitmap)
        val length = if (reported > 0) {
            minOf(reported.toLong() * pageSize, scratch.size.toLong()).toInt()
        } else {
            maxLength
        }

        val checksum = computeChecksum(frame, length)

        val keyframe = keyframes.isEmpty() || currentLength != length ||
            frame % config.deltaKeyframeInterval == 0
        if (keyframe) {
            keyframes.addLast(Keyframe(frame, length, scratch.copyOf(length)))
            bytesUsed += length
            // Older deltas stay until trim(): frames between the previous keyframe and this one are
            // still inside the rollback window and must remain reconstructible.
        } else {
            val delta = buildDelta(frame, length, reported)
            deltas.addLast(delta)
            bytesUsed += delta.bytes
        }
        lastFrame = frame

        // The new state becomes the reference for the next delta; the old scratch is reused.
        val swap = current
        current = scratch
        scratch = swap
        currentLength = length

        trim()
        return checksum
    }

    private fun computeChecksum(frame: Int, length: Int): Long {
        if (config.checksumEveryFrames == 0) return 0L
        val start = nowNanos()
        val value = core.stateChecksum() ?: stateHash(scratch, 0, length)
        checksumNanos += nowNanos() - start
        checksums++
        checksumsByFrame[frame] = value
        return value
    }

    private fun buildDelta(frame: Int, length: Int, reported: Int): Delta {
        val pages = (length + pageSize - 1) / pageSize
        val indices = IntArray(pages)
        var count = 0
        if (reported >= pages && pages > 0) {
            dirtyHits++
            for (page in 0 until pages) {
                if (dirtyBitmap[page] != 0.toByte()) indices[count++] = page
            }
        } else {
            deltaScans++
            val scanStart = nowNanos()
            for (page in 0 until pages) {
                if (pageDiffers(page, length)) indices[count++] = page
            }
            scanNanos += nowNanos() - scanStart
        }
        val data = ByteArray(count * pageSize)
        for (index in 0 until count) {
            val offset = indices[index] * pageSize
            val copyLength = minOf(pageSize, length - offset)
            scratch.copyInto(data, index * pageSize, offset, offset + copyLength)
        }
        return Delta(frame, length, indices.copyOf(count), data)
    }

    private fun pageDiffers(page: Int, length: Int): Boolean {
        val offset = page * pageSize
        val end = minOf(offset + pageSize, length)
        var index = offset
        while (index + 8 <= end) {
            if (readLong(current, index) != readLong(scratch, index)) return true
            index += 8
        }
        while (index < end) {
            if (current[index] != scratch[index]) return true
            index++
        }
        return false
    }

    private fun readLong(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (byte in 0 until 8) {
            value = value or ((data[offset + byte].toLong() and 0xFF) shl (8 * byte))
        }
        return value
    }

    override fun load(frame: Int): Boolean {
        val keyframe = keyframes.lastOrNull { it.frame <= frame } ?: return false
        if (reconstruct.size < keyframe.length) reconstruct = ByteArray(keyframe.length)
        keyframe.data.copyInto(reconstruct, 0, 0, keyframe.length)

        var applied = keyframe.frame
        for (delta in deltas) {
            if (delta.frame <= keyframe.frame) continue
            if (delta.frame > frame) break
            if (delta.frame != applied + 1) return false
            applyDelta(delta)
            applied = delta.frame
        }
        if (applied != frame) return false

        val start = nowNanos()
        core.loadState(reconstruct, keyframe.length)
        loadNanos += nowNanos() - start
        loads++

        // The next save diffs against the state we just restored.
        if (current.size < keyframe.length) current = ByteArray(keyframe.length)
        reconstruct.copyInto(current, 0, 0, keyframe.length)
        currentLength = keyframe.length
        return true
    }

    private fun applyDelta(delta: Delta) {
        for (index in delta.pageIndices.indices) {
            val offset = delta.pageIndices[index] * pageSize
            val copyLength = minOf(pageSize, delta.length - offset)
            delta.pageData.copyInto(reconstruct, offset, index * pageSize, index * pageSize + copyLength)
        }
    }

    override fun loadAtOrBefore(frame: Int): Int? = if (load(frame)) frame else null

    override fun checksum(frame: Int): Long? = checksumsByFrame[frame]

    override fun clear() {
        keyframes.clear()
        deltas.clear()
        checksumsByFrame.clear()
        currentLength = 0
        lastFrame = -1
        bytesUsed = 0L
    }

    /**
     * Drops snapshots older than the rollback window and, if needed, the oldest keyframe until the
     * ring fits [RollbackConfig.maxStateBytes].
     */
    private fun trim() {
        val oldest = lastFrame - config.snapshotWindow
        while (keyframes.size > 1 && keyframes[1].frame <= oldest) {
            bytesUsed -= keyframes.removeFirst().length
        }
        while (deltas.isNotEmpty() && deltas.first().frame < keyframes.first().frame) {
            bytesUsed -= deltas.removeFirst().bytes
        }
        while (bytesUsed > config.maxStateBytes && keyframes.size > 1) {
            bytesUsed -= keyframes.removeFirst().length
            val start = keyframes.first().frame
            while (deltas.isNotEmpty() && deltas.first().frame < start) {
                bytesUsed -= deltas.removeFirst().bytes
            }
        }
        val minFrame = keyframes.first().frame
        val iterator = checksumsByFrame.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() < minFrame) iterator.remove()
        }
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 4096
    }
}
