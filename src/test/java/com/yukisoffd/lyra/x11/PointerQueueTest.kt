package com.yukisoffd.lyra.x11

import org.junit.Assert.assertEquals
import org.junit.Test

class PointerQueueTest {
    @Test fun pendingMotionDoesNotCrossButtonOrWheelEvents() {
        val commands = ArrayDeque<(Unit) -> Unit>()
        val sent = mutableListOf<Triple<Int, Int, Int>>()
        val queue = PointerQueue<Unit>({ commands.addLast(it) }) { _, x, y, mask ->
            sent += Triple(x, y, mask)
        }

        queue.pointer(10, 20, 0, motionOnly = true)
        queue.pointer(11, 20, 0, motionOnly = true)
        queue.pointer(12, 20, 1, motionOnly = false)
        queue.pointer(13, 20, 1, motionOnly = true)
        queue.pointer(14, 20, 1, motionOnly = true)
        queue.ordered { sent += Triple(14, 20, 9) }
        queue.pointer(15, 20, 1, motionOnly = true)
        queue.pointer(16, 20, 0, motionOnly = false)
        queue.pointer(17, 20, 0, motionOnly = true)
        commands.forEach { it(Unit) }

        assertEquals(
            listOf(11 to 0, 12 to 1, 14 to 1, 14 to 9, 15 to 1, 16 to 0, 17 to 0)
                .map { (x, mask) -> Triple(x, 20, mask) },
            sent,
        )
    }
}
