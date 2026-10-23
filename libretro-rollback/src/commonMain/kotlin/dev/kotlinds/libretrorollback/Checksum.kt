package dev.kotlinds.libretrorollback

/**
 * FNV-1a 64, the checksum GGPO uses for desync detection. Cheap enough to run on every snapshot.
 */
object Checksum {
    private const val OFFSET_BASIS: Long = -3750763034362895579L // 0xcbf29ce484222325
    private const val PRIME: Long = 1099511628211L // 0x100000001b3

    fun fnv1a64(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long {
        var hash = OFFSET_BASIS
        var i = offset
        val end = offset + length
        while (i < end) {
            hash = hash xor (data[i].toLong() and 0xFFL)
            hash *= PRIME
            i++
        }
        return hash
    }
}
