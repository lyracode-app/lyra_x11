package com.yukisoffd.lyra.x11

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class GuiPreferencesTest {
    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "putInt", "putString" -> { values[arguments!![0] as String] = arguments[1]; proxy }
                "apply" -> null
                "commit" -> true
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, arguments ->
            when (method.name) {
                "getInt", "getString" -> values[arguments!![0] as String] ?: arguments[1]
                "edit" -> editor
                else -> error(method.name)
            }
        } as SharedPreferences
    }
    @Test fun profilesSurviveNewStoreInstancesAndRemainPerEnvironment() {
        val backing = preferences()
        val store = GuiPreferences(backing)
        store.save("debian", GuiOptions(1920, 1080, 30, "startlxde", audio = AudioBackend.PIPEWIRE, graphics = GraphicsBackend.SOFTWARE))
        store.save("alpine", GuiOptions(1024, 768, 15, "openbox-session"))
        val reopened = GuiPreferences(backing)
        assertEquals(GuiOptions(1920, 1080, 30, "startlxde", audio = AudioBackend.PIPEWIRE, graphics = GraphicsBackend.SOFTWARE), reopened.load("debian"))
        assertEquals("openbox-session", reopened.load("alpine").desktopCommand)
        assertEquals(listOf("startlxde"), reopened.history("debian"))
    }
    @Test fun commandHistoryIsRecentUniqueAndBounded() {
        val store = GuiPreferences(preferences())
        repeat(12) { store.save("a", GuiOptions(desktopCommand = "session-$it")) }
        store.save("a", GuiOptions(desktopCommand = "session-7"))
        assertEquals(8, store.history("a").size)
        assertEquals("session-7", store.history("a").first())
        assertEquals(8, store.history("a").distinct().size)
    }
    @Test fun oneOffAppPreviewDoesNotOverwriteDesktopPreferences() {
        val store = GuiPreferences(preferences())
        store.save("a", GuiOptions(desktopCommand = "startlxde"))
        store.save("a", GuiOptions(desktopCommand = "python3 app.py", appMode = true))
        assertEquals("startlxde", store.load("a").desktopCommand)
        assertEquals(listOf("startlxde"), store.history("a"))
    }
}
