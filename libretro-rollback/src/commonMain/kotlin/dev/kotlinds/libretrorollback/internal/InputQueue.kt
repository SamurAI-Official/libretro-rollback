package dev.kotlinds.libretrorollback.internal

/**
 * Per-player input ring, modelled on GGPO's `InputQueue`.
 *
 * Inputs are stored by frame. A frame that has not arrived yet is *predicted* as a copy of the last
 * known input. When the real input arrives, [firstIncorrectFrame] tells the session which frame to
 * roll back to. The local queue also applies the configured input delay and pads gaps (the delay was
 * raised mid-session) so the input stream stays contiguous.
 */
internal class InputQueue(
    private val inputSize: Int,
    private val capacity: Int,
    private val frameDelay: Int = 0,
    private val padGaps: Boolean = frameDelay > 0,
) {
    private data class Entry(val frame: Int, val input: ByteArray)

    private val entries = ArrayDeque<Entry>()
    private val predictions = mutableMapOf<Int, ByteArray>()

    private var lastConfirmedInput = ByteArray(inputSize)
    private var firstIncorrect = -1

    /**
     * Highest frame with a real input and no missing frame before it. A frame is only *final* once
     * every frame up to it is confirmed: a state built from a predicted input cannot be compared
     * with the peer's until the real input arrives (and, if it differs, the rollback has run).
     */
    var confirmedThrough: Int = -1
        private set

    /** Last frame stored, or -1. */
    fun lastAddedFrame(): Int = entries.lastOrNull()?.frame ?: -1

    /** Input stored for [frame], or null. */
    fun inputAt(frame: Int): ByteArray? = entries.firstOrNull { it.frame == frame }?.input

    /**
     * Adds [input] for [frame] (the engine frame) and returns the frame it was stored at after the
     * local input delay. Returns -1 when the input was too old to store.
     */
    fun addInput(frame: Int, input: ByteArray): Int {
        require(input.size == inputSize) { "input must be $inputSize bytes, was ${input.size}" }
        val delayed = frame + frameDelay
        if (entries.any { it.frame == delayed }) return delayed
        if (entries.isNotEmpty() && delayed <= entries.last().frame) return -1

        if (padGaps) {
            var next = if (entries.isEmpty()) 0 else entries.last().frame + 1
            while (next < delayed) {
                insert(Entry(next, lastConfirmedInput.copyOf()))
                next++
            }
        }

        insert(Entry(delayed, input.copyOf()))
        lastConfirmedInput = input.copyOf()

        val predicted = predictions.remove(delayed)
        if (predicted != null && !predicted.contentEquals(input) &&
            (firstIncorrect == -1 || delayed < firstIncorrect)
        ) {
            firstIncorrect = delayed
        }

        while (entries.size > capacity) entries.removeFirst()
        return delayed
    }

    /**
     * Copies the input for [frame] into [out] at [offset]. Returns true when the input is confirmed,
     * false when it is a prediction.
     */
    fun getInput(frame: Int, out: ByteArray, offset: Int): Boolean {
        val exact = entries.firstOrNull { it.frame == frame }
        if (exact != null) {
            exact.input.copyInto(out, offset)
            return true
        }

        val predicted = predictions.getOrPut(frame) {
            entries.lastOrNull { it.frame < frame }?.input?.copyOf() ?: ByteArray(inputSize)
        }
        predicted.copyInto(out, offset)
        return false
    }

    /** First frame whose real input differed from what we predicted, or -1. */
    fun firstIncorrectFrame(): Int = firstIncorrect

    /** Called after loading a state at [frame]: everything from there on must be predicted again. */
    fun resetPrediction(frame: Int) {
        val iterator = predictions.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() >= frame) iterator.remove()
        }
        if (firstIncorrect >= frame) firstIncorrect = -1
    }

    /** Drops inputs older than [frame]; the last known input is kept for prediction. */
    fun discardConfirmedFrames(frame: Int) {
        while (entries.isNotEmpty() && entries.first().frame < frame) {
            lastConfirmedInput = entries.removeFirst().input
        }
    }

    private fun insert(entry: Entry) {
        var index = entries.size
        while (index > 0 && entries[index - 1].frame > entry.frame) index--
        entries.add(index, entry)

        if (entry.frame == confirmedThrough + 1) {
            confirmedThrough = entry.frame
            while (entries.any { it.frame == confirmedThrough + 1 }) confirmedThrough++
        }
    }
}
