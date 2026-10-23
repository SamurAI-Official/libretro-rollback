package dev.kotlinds.libretrorollback

/**
 * Deterministic test core. The state is a tiny integer machine; [divergeAt] deliberately makes one
 * core disagree at a frame so desync detection can be tested.
 */
class FakeCore(
    override val stateSize: Int = 64,
    private val divergeAt: Int = -1,
    initialValue: Int = 0,
) : RollbackCore {
    private var value = initialValue
    private var frame = 0
    private var lastInputHash = 0

    override val fingerprint: CoreFingerprint = CoreFingerprint()

    override fun saveState(into: ByteArray): Int {
        writeInt(into, 0, value)
        writeInt(into, 4, frame)
        writeInt(into, 8, lastInputHash)
        return 12
    }

    override fun loadState(from: ByteArray, size: Int) {
        value = readInt(from, 0)
        frame = readInt(from, 4)
        lastInputHash = readInt(from, 8)
    }

    override fun runFrame(inputs: ByteArray, render: Boolean) {
        val inputHash = Checksum.fnv1a64(inputs).toInt()
        value = value * 31 + inputHash + frame
        if (frame == divergeAt) value += 1
        lastInputHash = inputHash
        frame++
    }

    fun snapshot(): IntArray = intArrayOf(value, frame, lastInputHash)

    private fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
        data[offset + 2] = (value ushr 16).toByte()
        data[offset + 3] = (value ushr 24).toByte()
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
