package dev.kotlinds.libretrorollback.libretro

import dev.kotlinds.libretrokmp.LibretroFrontend
import dev.kotlinds.libretrokmp.LogLevel
import dev.kotlinds.libretrokmp.VideoFrame

/**
 * Frontend for a core driven by [dev.kotlinds.libretrorollback.RollbackSession].
 *
 * It hands the core the inputs of the frame being simulated and drops video/audio while the engine
 * re-simulates. The application keeps the last real frame; re-simulated frames must never reach the
 * screen or the sound device.
 */
class RollbackFrontend(
    override val systemDirectory: String,
    override val saveDirectory: String,
    val options: Map<String, String> = emptyMap(),
    val codec: LibretroInputCodec = LibretroInputCodec(),
) : LibretroFrontend {

    var inputs: ByteArray = ByteArray(0)
    var render: Boolean = true

    var presentedFrames: Long = 0
        private set
    var droppedFrames: Long = 0
        private set
    var presentedAudioFrames: Long = 0
        private set

    var logger: ((LogLevel, String) -> Unit)? = null

    /** FNV-1a of the sorted core options, exchanged in the handshake. */
    val optionsHash: Long by lazy {
        val joined = options.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
        dev.kotlinds.libretrorollback.Checksum.fnv1a64(joined.encodeToByteArray())
    }

    override fun variable(key: String): String? = options[key]

    override fun onVideoFrame(frame: VideoFrame) {
        if (render) presentedFrames++ else droppedFrames++
    }

    override fun onAudio(samples: ShortArray, frames: Int) {
        if (render) presentedAudioFrames += frames
    }

    override fun onLog(level: LogLevel, message: String) {
        logger?.invoke(level, message)
    }

    override fun inputState(port: Int, device: Int, index: Int, id: Int): Short =
        codec.read(inputs, port, device, index, id)
}
