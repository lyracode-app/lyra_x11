package com.yukisoffd.lyra.x11.nativebackend

import org.junit.Assert.*
import org.junit.Test

class FramePacerTest {
    @Test fun sixtyFpsDoesNotDropToFortyFiveOnNinetyHzOrFortyEightOn144Hz() {
        for(refresh in listOf(60,90,120,144)) {
            val pacer=FramePacer(60)
            val frames=(0 until refresh*10).count { pacer.frame(it*1_000_000_000L/refresh) }
            assertEquals("refresh=$refresh",600,frames)
        }
    }
    @Test fun stalledAndReattachedViewsDoNotQueueStaleFrames() {
        val pacer=FramePacer(60)
        assertTrue(pacer.frame(0));assertTrue(pacer.frame(1_000_000_000))
        assertFalse(pacer.frame(1_000_000_001))
        pacer.reset();assertTrue(pacer.frame(1_000_000_002))
    }
}
