package com.yukisoffd.lyra.x11.media

import org.junit.Assert.*
import org.junit.Test

class PcmQueueTest {
    @Test fun arbitrarilyFragmentedReadsPreserveStereoFrameBoundaries() {
        val queue = PcmQueue(32)
        val output = ByteArray(32)
        queue.offer(byteArrayOf(1, 2, 3), 3)
        assertEquals(0, queue.read(output))
        queue.offer(byteArrayOf(4, 5, 6), 3)
        assertEquals(4, queue.read(output))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.copyOf(4))
        queue.offer(byteArrayOf(7, 8), 2)
        assertEquals(4, queue.read(output))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), output.copyOf(4))
    }
    @Test fun stalledPlaybackDropsOldestCompleteFrames() {
        val queue = PcmQueue(16)
        queue.offer(ByteArray(44) { it.toByte() }, 44)
        assertEquals(16, queue.queuedBytes())
        assertEquals(28L, queue.droppedBytes)
        val bytes = ByteArray(16)
        assertEquals(16, queue.read(bytes))
        assertArrayEquals(ByteArray(16) { (it + 28).toByte() }, bytes)
    }
    @Test fun flushDiscardsThePartialFrameWithoutShiftingTheContinuousStream() {
        val queue = PcmQueue(16)
        queue.offer(byteArrayOf(1, 2, 3, 4, 5), 5); queue.clear()
        queue.offer(byteArrayOf(6, 7, 8, 9, 10, 11, 12), 7)
        val bytes = ByteArray(4); assertEquals(4, queue.read(bytes))
        assertArrayEquals(byteArrayOf(9, 10, 11, 12), bytes)
        assertEquals(8L, queue.droppedBytes)
    }
}
