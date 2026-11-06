package com.yukisoffd.lyracode.ai

import org.json.JSONArray
import org.json.JSONObject

internal fun linuxGuiToolDefinition(): JSONObject {
    val properties = JSONObject()
        .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("guide", "list", "start", "status", "show", "hide", "stop", "screenshot", "audio", "request_overlay_permission"))))
    val descriptions = mapOf(
        "linux_id" to "Required for start; choose an enabled environment from list.",
        "session_id" to "Required for show, stop and screenshot; optional for status.",
        "mode" to "overlay (default) or fullscreen. Overlay requires Android overlay permission.",
        "preset" to "Desktop preset: lxde, xfce, lxqt or openbox. Omit to reuse this environment's last command.",
        "command" to "Custom desktop/window manager command. Mutually exclusive with preset and app_command.",
        "app_command" to "Interactive GUI application command, e.g. cd /workspace/demo && python3 main.py. Lyra supplies Openbox and session D-Bus; install openbox first.",
    )
    descriptions.forEach { (name, description) -> properties.put(name, JSONObject().put("type", "string").put("description", description)) }
    for (name in listOf("width", "height", "fps")) properties.put(name, JSONObject().put("type", "integer"))
    properties.put("audio", JSONObject().put("type", "string").put("enum", JSONArray(listOf("auto", "pulseaudio", "pipewire", "off")))
        .put("description", "Audio backend for start. Default reuses saved profile; auto prefers PulseAudio, then PipeWire. Follow guide for guest packages."))
    properties.put("graphics", JSONObject().put("type", "string").put("enum", JSONArray(listOf("auto", "virgl", "software")))
        .put("description", "Graphics for start: auto validates Android EGL and guest Mesa virpipe, otherwise uses software; virgl requires validation to succeed. No Turnip is needed."))
    properties.put("display_backend", JSONObject().put("type", "string").put("enum", JSONArray(listOf("auto", "native", "vnc")))
        .put("description", "Display transport: auto prefers the embedded X11/AHardwareBuffer renderer and falls back before launch; native requires it; vnc forces the compatible fallback."))
    properties.put("muted", JSONObject().put("type", "boolean").put("description", "Required for action=audio: mute or resume this session's Android output."))
    return JSONObject().put("type", "function").put("function", JSONObject().put("name", "linux_gui")
        .put("description", "Configure and display Linux GUI desktops/apps directly inside Lyra or in a movable Android overlay. " +
            "Before GUI installs or repairs, call guide and follow its minimal distro package specification through proot_command. " +
            "Use start with app_command for GUI development previews, status for real readiness/logs, screenshot for native live-frame capture. " +
            "Lyra owns the embedded X11 server, private sockets and Xauthority; never invent a VNC password, patch VNC authentication, or launch a replacement server manually.")
        .put("parameters", JSONObject().put("type", "object").put("properties", properties)
            .put("required", JSONArray(listOf("action"))).put("additionalProperties", false)))
}
