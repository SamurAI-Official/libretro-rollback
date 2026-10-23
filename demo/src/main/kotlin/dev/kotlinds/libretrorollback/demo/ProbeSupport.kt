package dev.kotlinds.libretrorollback.demo

import dev.kotlinds.libretrorollback.libretro.LibretroInputCodec
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal fun scriptedInput(codec: LibretroInputCodec, frame: Int, touch: Boolean): ByteArray {
    val inputs = ByteArray(codec.inputSize * 2)
    val buttons = when ((frame / 30) % 8) {
        0 -> 0
        1 -> LibretroInputCodec.A
        2 -> LibretroInputCodec.B
        3 -> LibretroInputCodec.LEFT
        4 -> LibretroInputCodec.RIGHT
        5 -> LibretroInputCodec.UP
        6 -> LibretroInputCodec.DOWN
        7 -> LibretroInputCodec.START
        else -> 0
    }
    val touchX = if (touch) 128 else -1
    val touchY = if (touch) 96 else -1
    codec.pack(buttons, touchX, touchY, touch && (frame / 45) % 2 == 0).copyInto(inputs, 0)
    return inputs
}

internal fun coreOptionsFor(coreFile: String): Map<String, String> = when {
    coreFile.contains("desmume", ignoreCase = true) -> mapOf(
        "desmume_cpu_mode" to "interpreter",
        "desmume_frameskip" to "0",
        "desmume_pointer_mouse" to "enabled",
        "desmume_pointer_type" to "touch",
        "desmume_firmware_language" to "English",
        "desmume_screens_layout" to "top/bottom",
        "desmume_screens_gap" to "0",
        "desmume_internal_resolution" to "256x192",
        "desmume_advanced_timing" to "disabled",
        "desmume_num_cores" to "1",
        "desmume_mic_mode" to "pattern",
        "desmume_use_external_bios" to "disabled",
    )

    coreFile.contains("melonds", ignoreCase = true) -> mapOf(
        "melonds_console_mode" to "DS",
        "melonds_boot_directly" to "enabled",
        "melonds_threaded_renderer" to "disabled",
        "melonds_screen_layout" to "Top/Bottom",
        "melonds_touch_mode" to "Touch",
        "melonds_language" to "English",
        "melonds_randomize_mac_address" to "disabled",
        "melonds_use_fw_settings" to "disabled",
        "melonds_mic_input" to "pattern",
    )

    else -> emptyMap()
}

/** Parses `--options key=value,key=value` (comma-separated core option overrides). */
internal fun optionOverrides(value: String?): Map<String, String> =
    value?.split(',')
        ?.mapNotNull { item ->
            val key = item.substringBefore('=', "").trim()
            val option = item.substringAfter('=', "").trim()
            if (key.isEmpty() || option.isEmpty()) null else key to option
        }
        ?.toMap()
        ?: emptyMap()

internal fun parse(args: List<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        if (arg.startsWith("--")) {
            val key = arg.removePrefix("--")
            val next = args.getOrNull(index + 1)
            if (next == null || next.startsWith("--")) {
                result[key] = "true"
                index++
            } else {
                result[key] = next
                index += 2
            }
        } else {
            index++
        }
    }
    return result
}

internal fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { stream ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
