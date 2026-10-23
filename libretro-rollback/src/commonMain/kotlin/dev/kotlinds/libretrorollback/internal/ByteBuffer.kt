package dev.kotlinds.libretrorollback.internal

internal class ByteWriter(initialCapacity: Int = 64) {
    private var buffer = ByteArray(initialCapacity.coerceAtLeast(16))
    private var position = 0

    private fun ensure(count: Int) {
        if (position + count <= buffer.size) return
        var next = buffer.size * 2
        while (next < position + count) next *= 2
        buffer = buffer.copyOf(next)
    }

    fun u8(value: Int) {
        ensure(1)
        buffer[position++] = value.toByte()
    }

    fun u16(value: Int) {
        u8(value and 0xFF)
        u8((value ushr 8) and 0xFF)
    }

    fun u32(value: Int) {
        u16(value and 0xFFFF)
        u16((value ushr 16) and 0xFFFF)
    }

    fun u64(value: Long) {
        u32((value and 0xFFFFFFFFL).toInt())
        u32(((value ushr 32) and 0xFFFFFFFFL).toInt())
    }

    fun bytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(buffer, position)
        position += value.size
    }

    fun str(value: String) {
        val bytes = value.encodeToByteArray()
        u16(bytes.size)
        bytes(bytes)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(position)
}

internal class ByteReader(private val data: ByteArray, private val length: Int) {
    private var position = 0

    val remaining: Int get() = length - position

    private fun require(count: Int) {
        if (position + count > length) throw IndexOutOfBoundsException("packet truncated")
    }

    fun u8(): Int {
        require(1)
        return data[position++].toInt() and 0xFF
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun u32(): Int = u16() or (u16() shl 16)

    fun u64(): Long {
        val low = u32().toLong() and 0xFFFFFFFFL
        val high = u32().toLong() and 0xFFFFFFFFL
        return low or (high shl 32)
    }

    fun bytes(count: Int): ByteArray {
        require(count)
        val out = data.copyOfRange(position, position + count)
        position += count
        return out
    }

    fun str(): String {
        val size = u16()
        return bytes(size).decodeToString()
    }
}
