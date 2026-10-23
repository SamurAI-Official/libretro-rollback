package dev.kotlinds.libretrorollback.libretro

import dev.kotlinds.libretrokmp.Device
import dev.kotlinds.libretrokmp.JoypadButton
import dev.kotlinds.libretrokmp.PointerId

/**
 * Packs one player's libretro input into the engine's opaque blob.
 *
 * Layout (16 bytes, little-endian):
 * ```
 * 0 u32 buttons   (bit N = libretro joypad id N)
 * 4 i16 touchX
 * 6 i16 touchY
 * 8 u8  touchPressed
 * 9..15 reserved
 * ```
 */
class LibretroInputCodec(
    private val screenWidth: Int = 256,
    private val screenHeight: Int = 192,
    private val layout: Layout = Layout.TOP_BOTTOM,
) {
    enum class Layout { TOP_BOTTOM, BOTTOM_TOP, SINGLE }

    val inputSize: Int = 16

    fun buttons(inputs: ByteArray, port: Int): Int = readInt(inputs, port * inputSize)

    fun touchX(inputs: ByteArray, port: Int): Int = readShort(inputs, port * inputSize + 4)

    fun touchY(inputs: ByteArray, port: Int): Int = readShort(inputs, port * inputSize + 6)

    fun touchPressed(inputs: ByteArray, port: Int): Boolean = inputs.getOrNull(port * inputSize + 8) != 0.toByte()

    fun pack(buttons: Int, touchX: Int = -1, touchY: Int = -1, touchPressed: Boolean = false): ByteArray {
        val out = ByteArray(inputSize)
        writeInt(out, 0, buttons)
        writeShort(out, 4, touchX)
        writeShort(out, 6, touchY)
        out[8] = if (touchPressed) 1 else 0
        return out
    }

    /** The value the core asks for through `retro_set_input_state`. */
    fun read(inputs: ByteArray, port: Int, device: Int, index: Int, id: Int): Short {
        val offset = port * inputSize
        if (offset + inputSize > inputs.size) return 0
        return when (device) {
            Device.JOYPAD -> {
                val buttons = readInt(inputs, offset)
                if (id in 0..31 && (buttons and (1 shl id)) != 0) 1 else 0
            }

            Device.POINTER -> {
                if (!touchPressed(inputs, port)) return 0
                val x = touchX(inputs, port)
                val y = touchY(inputs, port)
                when (id) {
                    PointerId.X -> pointerX(x)
                    PointerId.Y -> pointerY(y)
                    PointerId.PRESSED -> 1
                    else -> 0
                }
            }

            else -> 0
        }
    }

    private fun pointerX(x: Int): Short =
        ((x * 2 + 1) * 0x7FFF / screenWidth - 0x7FFF).toShort()

    private fun pointerY(y: Int): Short {
        val totalHeight = screenHeight * when (layout) {
            Layout.TOP_BOTTOM, Layout.BOTTOM_TOP -> 2
            Layout.SINGLE -> 1
        }
        val screenY = when (layout) {
            Layout.TOP_BOTTOM -> y + screenHeight
            Layout.BOTTOM_TOP, Layout.SINGLE -> y
        }
        return (((screenY * 2 + 1) * 0x7FFF / totalHeight) - 0x7FFF).toShort()
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)).let {
            if (it and 0x8000 != 0) it - 0x10000 else it
        }

    private fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
        data[offset + 2] = (value ushr 16).toByte()
        data[offset + 3] = (value ushr 24).toByte()
    }

    private fun writeShort(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
    }

    companion object {
        /** Bit for a libretro joypad id, e.g. `button(JoypadButton.A)`. */
        fun button(id: Int): Int = 1 shl id

        val A: Int get() = button(JoypadButton.A)
        val B: Int get() = button(JoypadButton.B)
        val X: Int get() = button(JoypadButton.X)
        val Y: Int get() = button(JoypadButton.Y)
        val L: Int get() = button(JoypadButton.L)
        val R: Int get() = button(JoypadButton.R)
        val START: Int get() = button(JoypadButton.START)
        val SELECT: Int get() = button(JoypadButton.SELECT)
        val UP: Int get() = button(JoypadButton.UP)
        val DOWN: Int get() = button(JoypadButton.DOWN)
        val LEFT: Int get() = button(JoypadButton.LEFT)
        val RIGHT: Int get() = button(JoypadButton.RIGHT)
    }
}
