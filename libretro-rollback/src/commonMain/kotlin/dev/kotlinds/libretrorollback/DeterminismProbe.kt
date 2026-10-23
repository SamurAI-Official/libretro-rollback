package dev.kotlinds.libretrorollback

data class ProbeResult(
    val stateSize: Int,
    val stateLength: Int,
    val saveMillis: Double,
    val loadMillis: Double,
    val rollback8Millis: Double,
    val frames: Int,
    /** First frame whose state differed between the two runs, or -1 when both runs matched. */
    val mismatchFrame: Int,
    val hashesA: LongArray,
    val hashesB: LongArray,
    val initialStateHash: Long = 0,
    val loadAStateHash: Long = 0,
    val loadBStateHash: Long = 0,
    val mismatchByteCount: Int = 0,
    val mismatchOffsets: List<Int> = emptyList(),
    val mismatchValuesA: List<Byte> = emptyList(),
    val mismatchValuesB: List<Byte> = emptyList(),
) {
    val deterministic: Boolean get() = mismatchFrame < 0
}

/**
 * The gate for rollback on a given core: save a state, run N frames, restore, run the same N frames
 * again, and compare every frame's state hash. If this fails, rollback cannot work on that core
 * without a core-level fix (RTC, mic, firmware, JIT, ...).
 *
 * The core must be loaded and past its boot sequence before [run] is called.
 */
class DeterminismProbe(
    private val core: RollbackCore,
    private val config: RollbackConfig = RollbackConfig(),
) {
    fun run(
        warmupFrames: Int = 300,
        frames: Int = 600,
        benchmarkIterations: Int = 30,
        script: (Int) -> ByteArray = { ByteArray(config.frameInputBytes) },
        customStateHash: (() -> Long)? = null,
    ): ProbeResult {
        val idleInput = ByteArray(config.frameInputBytes)
        repeat(warmupFrames) { core.runFrame(idleInput, render = false) }

        val initial = ByteArray(core.stateSize)
        val initialLength = core.saveState(initial)
        val scratch = ByteArray(core.stateSize)
        val initialStateHash = stateHash(initial, 0, initialLength)

        repeat(benchmarkIterations) { core.saveState(scratch) }
        val saveStart = nowNanos()
        repeat(benchmarkIterations) { core.saveState(scratch) }
        val saveMillis = (nowNanos() - saveStart) / 1_000_000.0 / benchmarkIterations

        val loadStart = nowNanos()
        repeat(benchmarkIterations) { core.loadState(initial, initialLength) }
        val loadMillis = (nowNanos() - loadStart) / 1_000_000.0 / benchmarkIterations

        val rollbackStart = nowNanos()
        core.loadState(initial, initialLength)
        repeat(config.maxPredictionFrames) { core.runFrame(idleInput, render = false) }
        val rollback8Millis = (nowNanos() - rollbackStart) / 1_000_000.0

        // The rollback benchmark left the core maxPredictionFrames ahead; start both runs from the
        // same state or run A would be compared with a different starting point.
        core.loadState(initial, initialLength)
        val loadALength = core.saveState(scratch)
        val loadAStateHash = stateHash(scratch, 0, loadALength)
        val hashesA = LongArray(frames)
        repeat(frames) { frame ->
            core.runFrame(script(frame), render = false)
            if (customStateHash != null) {
                hashesA[frame] = customStateHash()
            } else {
                val length = core.saveState(scratch)
                hashesA[frame] = stateHash(scratch, 0, length)
            }
        }

        core.loadState(initial, initialLength)
        val loadBLength = core.saveState(scratch)
        val loadBStateHash = stateHash(scratch, 0, loadBLength)
        val hashesB = LongArray(frames)
        repeat(frames) { frame ->
            core.runFrame(script(frame), render = false)
            if (customStateHash != null) {
                hashesB[frame] = customStateHash()
            } else {
                val length = core.saveState(scratch)
                hashesB[frame] = stateHash(scratch, 0, length)
            }
        }

        core.loadState(initial, initialLength)
        val mismatch = (0 until frames).firstOrNull { hashesA[it] != hashesB[it] } ?: -1

        val mismatchOffsets = mutableListOf<Int>()
        val mismatchValuesA = mutableListOf<Byte>()
        val mismatchValuesB = mutableListOf<Byte>()
        var mismatchByteCount = 0
        if (mismatch >= 0) {
            val stateA = ByteArray(core.stateSize)
            val stateB = ByteArray(core.stateSize)
            core.loadState(initial, initialLength)
            repeat(mismatch + 1) { core.runFrame(script(it), render = false) }
            val lengthA = core.saveState(stateA)
            core.loadState(initial, initialLength)
            repeat(mismatch + 1) { core.runFrame(script(it), render = false) }
            val lengthB = core.saveState(stateB)
            for (offset in 0 until minOf(lengthA, lengthB)) {
                if (stateA[offset] == stateB[offset]) continue
                mismatchByteCount++
                if (mismatchOffsets.size < 24) {
                    mismatchOffsets += offset
                    mismatchValuesA += stateA[offset]
                    mismatchValuesB += stateB[offset]
                }
            }
        }

        return ProbeResult(
            stateSize = core.stateSize,
            stateLength = initialLength,
            saveMillis = saveMillis,
            loadMillis = loadMillis,
            rollback8Millis = rollback8Millis,
            frames = frames,
            mismatchFrame = mismatch,
            hashesA = hashesA,
            hashesB = hashesB,
            initialStateHash = initialStateHash,
            loadAStateHash = loadAStateHash,
            loadBStateHash = loadBStateHash,
            mismatchByteCount = mismatchByteCount,
            mismatchOffsets = mismatchOffsets,
            mismatchValuesA = mismatchValuesA,
            mismatchValuesB = mismatchValuesB,
        )
    }
}
