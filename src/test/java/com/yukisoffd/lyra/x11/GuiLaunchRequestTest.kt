package com.yukisoffd.lyra.x11

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GuiLaunchRequestTest {
    @Test fun mediaModesAreValidatedAndSavedDefaultsAreReused() {
        val saved = GuiOptions(audio = AudioBackend.PIPEWIRE, graphics = GraphicsBackend.SOFTWARE,displayBackend=DisplayBackend.NATIVE)
        val args = JSONObject().put("linux_id", "a")
        assertEquals(saved, GuiLaunchRequest.parse(args, saved).options)
        val explicit = GuiLaunchRequest.parse(args.put("audio", "off").put("graphics", "virgl"), saved).options
        assertEquals(AudioBackend.OFF, explicit.audio); assertEquals(GraphicsBackend.VIRGL, explicit.graphics)
        assertEquals(DisplayBackend.NATIVE,explicit.displayBackend)
        assertEquals(DisplayBackend.VNC,GuiLaunchRequest.parse(args.put("display_backend","vnc"),saved).options.displayBackend)
        assertThrows(IllegalStateException::class.java) { GuiLaunchRequest.parse(args.put("display_backend","remote"),saved) }
        assertThrows(IllegalStateException::class.java) { GuiLaunchRequest.parse(args.put("graphics", "turnip"), saved) }
    }
    @Test fun omittedCommandAndGeometryReuseTheEnvironmentProfile() {
        val saved = GuiOptions(1920, 1080, 30, "startlxde")
        val request = GuiLaunchRequest.parse(JSONObject().put("linux_id", "debian"), saved)
        assertEquals(saved, request.options)
        assertEquals("overlay", request.mode)
    }
    @Test fun previewsKeepTheExactApplicationCommandAndDoNotSelectADesktop() {
        val command = "cd /workspace/demo && python3 main.py --name 'hello world'"
        val request = GuiLaunchRequest.parse(JSONObject().put("linux_id", "debian").put("app_command", command), GuiOptions())
        assertTrue(request.options.appMode)
        assertEquals(command, request.options.desktopCommand)
    }
    @Test fun mutuallyExclusiveCommandsAndInvalidModesCannotLaunch() {
        val base = JSONObject().put("linux_id", "a").put("preset", "lxde").put("app_command", "python3 app.py")
        assertThrows(IllegalArgumentException::class.java) { GuiLaunchRequest.parse(base, GuiOptions()) }
        assertThrows(IllegalArgumentException::class.java) {
            GuiLaunchRequest.parse(JSONObject().put("linux_id", "a").put("mode", "network"), GuiOptions())
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuiLaunchRequest.parse(JSONObject().put("linux_id", "a").put("width", 50000), GuiOptions())
        }
    }
    @Test fun allDesktopPresetsResolveAndCustomCommandsArePreserved() {
        for (preset in DesktopPresets.entries) {
            val request = GuiLaunchRequest.parse(JSONObject().put("linux_id", "a").put("preset", preset.id), GuiOptions())
            assertEquals(preset.command, request.options.desktopCommand)
            assertFalse(request.options.appMode)
        }
        assertEquals("custom-session --flag", GuiLaunchRequest.parse(JSONObject().put("linux_id", "a").put("command", "custom-session --flag"), GuiOptions()).options.desktopCommand)
    }
}
