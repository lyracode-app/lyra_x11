package com.yukisoffd.lyra.x11

import org.json.JSONObject

data class GuiLaunchRequest(val environmentId: String, val mode: String, val options: GuiOptions) {
    companion object {
        fun parse(args: JSONObject, saved: GuiOptions): GuiLaunchRequest {
            val environment = args.getString("linux_id").also { require(it.isNotBlank()) { "linux_id is required" } }
            val mode = args.optString("mode", "overlay")
            require(mode in setOf("overlay", "fullscreen")) { "mode must be overlay or fullscreen" }
            val app = args.optString("app_command").trim()
            val desktop = args.optString("command").trim()
            val preset = args.optString("preset").trim()
            require(app.isEmpty() || (desktop.isEmpty() && preset.isEmpty())) { "Use app_command or a desktop command/preset, not both" }
            require(desktop.isEmpty() || preset.isEmpty()) { "Use preset or command, not both" }
            val command = when {
                app.isNotEmpty() -> app
                desktop.isNotEmpty() -> desktop
                preset.isNotEmpty() -> DesktopPresets.byId(preset)?.command ?: error("Unknown preset; use lxde, xfce, lxqt or openbox")
                else -> saved.desktopCommand
            }
            return GuiLaunchRequest(environment, mode, GuiOptions(args.optInt("width", saved.width), args.optInt("height", saved.height),
                args.optInt("fps", saved.framesPerSecond), command, appMode = app.isNotEmpty(),
                audio = AudioBackend.entries.firstOrNull { it.id == args.optString("audio", saved.audio.id) }
                    ?: error("audio must be auto, pulseaudio, pipewire or off"),
                displayBackend = DisplayBackend.entries.firstOrNull { it.id == args.optString("display_backend", saved.displayBackend.id) }
                    ?: error("display_backend must be auto, native or vnc"),
                graphics = GraphicsBackend.entries.firstOrNull { it.id == args.optString("graphics", saved.graphics.id) }
                    ?: error("graphics must be ${GraphicsBackend.entries.joinToString { it.id }}")))
        }
    }
}
