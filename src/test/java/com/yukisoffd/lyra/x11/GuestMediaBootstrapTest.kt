package com.yukisoffd.lyra.x11

import org.junit.Assert.*
import org.junit.Test

class GuestMediaBootstrapTest {
    @Test fun nestedGeneratedScriptsKeepHereDocumentTerminatorsAtColumnZero() {
        for (virgl in listOf(true, false)) {
            val media = GuestMedia("/run/lyra/0123456789abcdef", virgl)
            val script = GuestBootstrap.command(100, GuiOptions(), "b8992841-0b1a-448f-a5da-b110dc0e64fd", media)
            val ends = script.lines().filter { it.trim() == "EOF" }
            assertEquals(6, ends.size)
            assertTrue(ends.all { it == "EOF" })
            assertTrue(script.contains("use_system_clock_for_timing=yes"))
            assertFalse(script.contains("tcp:"))
        }
    }
}
