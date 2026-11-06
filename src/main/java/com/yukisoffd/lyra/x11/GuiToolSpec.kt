package com.yukisoffd.lyra.x11

import android.content.Context

object GuiToolSpec {
    const val SYSTEM_HINT = "For Linux GUI setup or troubleshooting, first call linux_gui(action=guide). " +
        "Follow its distro-specific minimal package contract through proot_command. " +
        "Use linux_gui start with app_command for a visible, floating application preview; use its screenshot action for the live framebuffer. " +
        "Use its audio/graphics launch options and media diagnostics for PulseAudio/PipeWire output and Android EGL VirGL; never infer GPU acceleration from environment variables alone. " +
        "Use display_backend=auto (native X11 first), native, or vnc fallback; inspect display_backend diagnostics for AHardwareBuffer versus shared memory upload. Lyra owns the X server, its private Unix socket, DISPLAY, XAUTHORITY and session D-Bus. Never guess passwords, patch authentication, install another VNC server, or manually replace the managed server."
    fun text(context: Context): String = context.assets.open("lyra_x11/GUI_TOOL_SPEC.md").bufferedReader().use { it.readText() }
}
