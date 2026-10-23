package dev.kotlinds.libretrorollback

internal actual fun nowMillis(): Long = System.nanoTime() / 1_000_000L

internal actual fun nowNanos(): Long = System.nanoTime()

internal actual fun sleepMillis(millis: Long) {
    if (millis > 0) Thread.sleep(millis)
}

actual fun stateHash(data: ByteArray, offset: Int, length: Int): Long {
    val crc = java.util.zip.CRC32C()
    crc.update(data, offset, length)
    return crc.value
}
