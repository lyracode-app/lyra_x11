package com.yukisoffd.lyra.x11

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

data class DesktopPreset(val id: String, val title: String, val command: String)

object DesktopPresets {
    val entries = listOf(
        DesktopPreset("lxde", "LXDE", "startlxde"),
        DesktopPreset("xfce", "Xfce", "startxfce4"),
        DesktopPreset("lxqt", "LXQt", "startlxqt"),
        DesktopPreset("openbox", "Openbox", "openbox-session"),
    )
    fun byId(id: String) = entries.firstOrNull { it.id == id }
}

/** Last successful launch request and command history are scoped to the Linux environment ID. */
class GuiPreferences internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("lyra_x11_profiles", Context.MODE_PRIVATE))
    fun load(environmentId: String): GuiOptions = runCatching {
        GuiOptions(
            preferences.getInt("$environmentId.width", 1280),
            preferences.getInt("$environmentId.height", 720),
            preferences.getInt("$environmentId.fps", 30),
            preferences.getString("$environmentId.command", "startlxde") ?: "startlxde",
            audio = AudioBackend.entries.firstOrNull { it.id == preferences.getString("$environmentId.audio", "auto") } ?: AudioBackend.AUTO,
            displayBackend = DisplayBackend.entries.firstOrNull { it.id == preferences.getString("$environmentId.display", "auto") } ?: DisplayBackend.AUTO,
            graphics = GraphicsBackend.entries.firstOrNull { it.id == preferences.getString("$environmentId.graphics", "auto") } ?: GraphicsBackend.AUTO,
        )
    }.getOrDefault(GuiOptions(desktopCommand = "startlxde"))

    fun history(environmentId: String): List<String> = runCatching {
        val array = JSONArray(preferences.getString("$environmentId.history", "[]"))
        (0 until array.length()).map { array.getString(it) }.filter(String::isNotBlank).take(8)
    }.getOrDefault(emptyList())

    fun save(environmentId: String, options: GuiOptions) {
        if (options.appMode) return // A one-off app preview must not replace the user's desktop profile.
        val history = (listOf(options.desktopCommand) + history(environmentId)).distinct().take(8)
        preferences.edit().putInt("$environmentId.width", options.width).putInt("$environmentId.height", options.height)
            .putInt("$environmentId.fps", options.framesPerSecond).putString("$environmentId.command", options.desktopCommand)
            .putString("$environmentId.history", JSONArray(history).toString())
            .putString("$environmentId.audio", options.audio.id)
            .putString("$environmentId.graphics", options.graphics.id)
            .putString("$environmentId.display", options.displayBackend.id).apply()
    }
}
