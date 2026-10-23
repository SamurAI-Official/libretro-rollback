package dev.kotlinds.libretrorollback

internal expect fun nowMillis(): Long

internal expect fun nowNanos(): Long

internal expect fun sleepMillis(millis: Long)

/**
 * Fast checksum of a memory region, used for state snapshots and desync detection.
 *
 * The JVM implementation uses CRC32C (a hardware-accelerated intrinsic); other targets fall back
 * to a portable hash. It only needs to be stable within one session, not cryptographic.
 */
expect fun stateHash(data: ByteArray, offset: Int, length: Int): Long
