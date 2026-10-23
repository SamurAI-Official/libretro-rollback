package dev.kotlinds.libretrorollback

/**
 * Everything the rollback engine needs from an emulator.
 *
 * The engine treats the emulated machine as a black box: a fixed-size snapshot, a way to restore it,
 * and one call that advances exactly one frame with the given inputs. That is exactly the contract a
 * libretro core offers (`retro_serialize` / `retro_unserialize` / `retro_run`).
 *
 * All methods are called on the thread that owns the emulator. libretro cores are not thread-safe.
 */
interface RollbackCore {
    /** Maximum number of bytes [saveState] can write. Must be > 0. */
    val stateSize: Int

    /**
     * Serializes the state at the current frame boundary into [into] and returns the number of bytes
     * written. [into] is a reusable ring-buffer slot: implementations must not retain it.
     */
    fun saveState(into: ByteArray): Int

    /** Restores the state contained in the first [size] bytes of [from]. */
    fun loadState(from: ByteArray, size: Int)

    /**
     * Runs exactly one frame.
     *
     * [inputs] is `players * inputSize` bytes, one opaque input blob per player. When [render] is
     * false the frame is a re-simulation: the core still runs, but the adapter must not present video
     * or audio for it.
     */
    fun runFrame(inputs: ByteArray, render: Boolean)

    /** Identity of core + content + options, exchanged during the handshake. */
    val fingerprint: CoreFingerprint

    /**
     * Optional checksum of the emulated state, used for desync detection.
     *
     * The default (`null`) hashes the serialized bytes. A core whose savestate contains padding or
     * uninitialized bytes should override this with a hash of the meaningful memory (for a DS, the
     * 4 MiB main RAM). The checksum must change whenever the game state changes.
     */
    fun stateChecksum(): Long? = null

    /**
     * Optional core-level dirty-page bitmap for the serialized state: one byte per 4 KiB page of
     * the buffer [saveState] writes, non-zero when that page changed since the previous save.
     *
     * A core that tracks this (a patched core exporting a dirty-page function) lets the delta
     * snapshot ring skip its per-frame page scan. `out` holds `ceil(stateSize / 4096)` bytes;
     * returns the number of pages the core filled, or 0 when it has no dirty-page support (the
     * engine then scans).
     */
    fun dirtyPages(out: ByteArray): Int = 0
}

/**
 * What both peers must agree on before a session starts. Empty fields are not compared, which lets a
 * caller start with a bare [RollbackCore] (tests, homebrew) without inventing a fingerprint.
 */
data class CoreFingerprint(
    val coreName: String = "",
    val coreVersion: String = "",
    val contentSha256: String = "",
    val optionsHash: Long = 0L,
)
