package com.yukisoffd.lyra.x11

import android.content.Context

object GuiToolSpec {
    const val SYSTEM_HINT = "For Linux GUI setup or troubleshooting, first call linux_gui(action=guide). " +
        "Follow its distro-specific minimal package contract through proot_command. " +
        "Use linux_gui start with app_command for a visible, floating application preview; use its screenshot action for the live framebuffer. " +
        "Use the media diagnostics for audio and GPU validation. The guide covers VirGL, experimental Venus on Mali, and guest-installed KGSL Turnip on Adreno. " +
        "Use display_backend=auto (native X11 first), native, or vnc fallback; inspect display_backend diagnostics for AHardwareBuffer versus shared memory upload. Lyra owns the X server, its private Unix socket, DISPLAY, XAUTHORITY and session D-Bus. Never guess passwords, patch authentication, install another VNC server, or manually replace the managed server."
    fun text(context: Context): String = context.assets.open("lyra_x11/GUI_TOOL_SPEC.md").bufferedReader().use { it.readText() }
}
