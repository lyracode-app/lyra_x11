package com.yukisoffd.lyra.x11

import org.junit.Assert.*
import org.junit.Test

class GuestMediaBootstrapTest {
    @Test fun nestedGeneratedScriptsKeepHereDocumentTerminatorsAtColumnZero() {
        for (graphics in GraphicsBackend.entries) for (virgl in listOf(true, false)) {
            val media = GuestMedia("/run/lyra/0123456789abcdef", virgl)
            val script = GuestBootstrap.command(100, GuiOptions(graphics = graphics), "b8992841-0b1a-448f-a5da-b110dc0e64fd", media)
            val ends = script.lines().filter { it.trim() == "EOF" }
            assertEquals(6, ends.size)
            assertTrue(ends.all { it == "EOF" })
            assertTrue(script.contains("use_system_clock_for_timing=yes"))
            assertFalse(script.contains("tcp:"))
            assertEquals(graphics.requiresVulkan, script.contains("vulkan.request"))
            assertEquals(virgl && graphics == GraphicsBackend.VIRGL_ANGLE, script.contains("--virgl-angle"))
            assertFalse(script.contains("vulkan-graphics.sh"))
        }
    }

    @Test fun isolatedProbeUsesTheSameX11DisplayAndSessionAuthority() {
        val id = "b8992841-0b1a-448f-a5da-b110dc0e64fd"
        val media = GuestMedia("/run/lyra/b89928410b1a448f", true)
        val nativeAuthority = "${media.directory}/xs/auth"
        for (authority in listOf(nativeAuthority, null)) {
            val command = GuestBootstrap.graphicsProbeCommand(100, id, media, GraphicsBackend.VENUS, authority)
            assertTrue(command.contains("DISPLAY=:100 XAUTHORITY='${authority ?: "${GuestBootstrap.directory(id)}/Xauthority"}'"))
            assertTrue(command.contains("exec /bin/sh '${media.directory}/vulkan-graphics.sh' venus"))
        }
    }
}
