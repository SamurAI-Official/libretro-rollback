package dev.kotlinds.libretrorollback

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkRadioTest {

    @Test
    fun deliverAndPollRoundTrip() {
        val radio = LinkRadio()
        assertTrue(radio.deliver(byteArrayOf(1, 2, 3)))
        assertEquals(1, radio.queued)
        assertEquals(1, radio.received)

        val buffer = ByteArray(8)
        assertEquals(3, radio.poll(buffer))
        assertContentEquals(byteArrayOf(1, 2, 3), buffer.copyOf(3))
        assertEquals(0, radio.queued)
    }

    @Test
    fun pollOnEmptyRadioReturnsZero() {
        val radio = LinkRadio()
        assertEquals(0, radio.poll(ByteArray(8)))
        assertEquals(0, radio.dropped)
    }

    @Test
    fun fullQueueDropsTheOldest() {
        val radio = LinkRadio(queueDepth = 2)
        radio.deliver(byteArrayOf(1))
        radio.deliver(byteArrayOf(2))
        radio.deliver(byteArrayOf(3))

        val buffer = ByteArray(4)
        assertEquals(1, radio.poll(buffer))
        assertContentEquals(byteArrayOf(2), buffer.copyOf(1))
        assertEquals(1, radio.poll(buffer))
        assertContentEquals(byteArrayOf(3), buffer.copyOf(1))
        assertEquals(1, radio.dropped)
    }

    @Test
    fun oversizePacketsAreRejected() {
        val radio = LinkRadio(maxPacketBytes = 4)
        assertFalse(radio.deliver(ByteArray(5)))
        assertEquals(0, radio.queued)
        assertEquals(1, radio.dropped)
    }

    @Test
    fun transmitIsCollectedOnce() {
        val radio = LinkRadio()
        assertEquals(2, radio.accept(byteArrayOf(0xB0.toByte(), 7), 2))
        assertEquals(1, radio.transmitted)

        val packets = radio.drainTransmitted()
        assertEquals(1, packets.size)
        assertContentEquals(byteArrayOf(0xB0.toByte(), 7), packets.first())
        assertTrue(radio.drainTransmitted().isEmpty())
    }
}
