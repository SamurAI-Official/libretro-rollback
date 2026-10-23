package dev.kotlinds.libretrorollback.libretro

import dev.kotlinds.libretrokmp.Device
import dev.kotlinds.libretrokmp.JoypadButton
import dev.kotlinds.libretrokmp.PointerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LibretroInputCodecTest {
    private val codec = LibretroInputCodec()

    @Test
    fun joypadButtonsSurvivePacking() {
        val input = codec.pack(LibretroInputCodec.A or LibretroInputCodec.LEFT)
        assertEquals(1, codec.read(input, 0, Device.JOYPAD, 0, JoypadButton.A))
        assertEquals(1, codec.read(input, 0, Device.JOYPAD, 0, JoypadButton.LEFT))
        assertEquals(0, codec.read(input, 0, Device.JOYPAD, 0, JoypadButton.B))
    }

    @Test
    fun touchIsOnlyReportedWhenPressed() {
        val released = codec.pack(0, 128, 96, touchPressed = false)
        assertEquals(0, codec.read(released, 0, Device.POINTER, 0, PointerId.X).toInt())
        assertEquals(0, codec.read(released, 0, Device.POINTER, 0, PointerId.PRESSED).toInt())

        val pressed = codec.pack(0, 10, 20, touchPressed = true)
        assertEquals(1, codec.read(pressed, 0, Device.POINTER, 0, PointerId.PRESSED).toInt())
        assertNotEquals(0, codec.read(pressed, 0, Device.POINTER, 0, PointerId.X).toInt())
        assertNotEquals(0, codec.read(pressed, 0, Device.POINTER, 0, PointerId.Y).toInt())
    }

    @Test
    fun playersUseSeparateSlots() {
        val inputs = ByteArray(codec.inputSize * 2)
        codec.pack(LibretroInputCodec.B).copyInto(inputs, 0)
        codec.pack(LibretroInputCodec.START).copyInto(inputs, codec.inputSize)
        assertEquals(1, codec.read(inputs, 0, Device.JOYPAD, 0, JoypadButton.B))
        assertEquals(0, codec.read(inputs, 0, Device.JOYPAD, 0, JoypadButton.START))
        assertEquals(1, codec.read(inputs, 1, Device.JOYPAD, 0, JoypadButton.START))
    }
}
