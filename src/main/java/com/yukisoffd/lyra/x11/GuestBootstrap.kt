package com.yukisoffd.lyra.x11

/** POSIX shell contract, shared by glibc and musl guests. Installation is always user controlled. */
object GuestBootstrap {
    fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    fun directory(sessionId: String): String {
        require(Regex("[a-f0-9-]{36}").matches(sessionId))
        return "/tmp/lyra-x11-$sessionId"
    }
    fun socketDirectory(sessionId: String): String = directory(sessionId).replace("/lyra-x11-", "/lyra-rfb-")

    fun graphicsProbeCommand(display: Int, sessionId: String, media: GuestMedia,
        backend: GraphicsBackend, nativeAuthority: String?): String {
        require(display in 1..999 && backend.requiresVulkan)
        val state = directory(sessionId)
        return "export DISPLAY=:$display XAUTHORITY=${quote(nativeAuthority ?: "$state/Xauthority")} " +
            "XDG_RUNTIME_DIR=${quote("$state/runtime")}\n" +
            "exec /bin/sh ${quote("${media.directory}/vulkan-graphics.sh")} ${backend.id} ${quote(media.directory)} ${quote(state)}"
    }
    fun command(display: Int, options: GuiOptions, sessionId: String, media: GuestMedia? = null, nativeAuthority: String? = null): String {
        require(display in 1..999)
        if(nativeAuthority != null) return nativeCommand(display,options,sessionId,media,nativeAuthority)
        val stateDirectory = directory(sessionId)
        val launch = if (options.appMode) "openbox >/dev/null 2>&1 & exec /bin/sh -lc ${quote(options.desktopCommand)}" else options.desktopCommand
        return """
            set -eu
            server=${'$'}(command -v Xtigervnc || command -v Xvnc || true)
            if [ -z "${'$'}server" ]; then
              echo 'LYRA_X11: Install TigerVNC (Xtigervnc/Xvnc), a window manager/desktop and dbus in this Linux environment.' >&2
              exit 127
            fi
            command -v xdpyinfo >/dev/null 2>&1 || { echo 'LYRA_X11: Install xdpyinfo (x11-utils / xorg-xdpyinfo).' >&2; exit 127; }
            command -v dbus-run-session >/dev/null 2>&1 || { echo 'LYRA_X11: Install dbus-run-session (dbus / dbus-x11).' >&2; exit 127; }
            command -v xauth >/dev/null 2>&1 || { echo 'LYRA_X11: Install xauth (xauth / xorg-xauth).' >&2; exit 127; }
            ${if (options.appMode) "command -v openbox >/dev/null 2>&1 || { echo 'LYRA_X11: Install openbox for app previews.' >&2; exit 127; }" else ""}
            umask 077
            state=$stateDirectory
            bridge=${socketDirectory(sessionId)}
            [ -d "${'$'}bridge" ] || { echo 'LYRA_X11: Private GUI bridge is missing; start the session from Lyra.' >&2; exit 1; }
            mkdir -m 700 "${'$'}state"
            server_pid=''
            desktop_pid=''
            audio_pid=''; pulse_pid=''; policy_pid=''
            cleanup() {
              trap - EXIT HUP INT TERM
              [ -z "${'$'}desktop_pid" ] || kill "${'$'}desktop_pid" 2>/dev/null || true
              [ -z "${'$'}server_pid" ] || kill "${'$'}server_pid" 2>/dev/null || true
              for pid in "${'$'}policy_pid" "${'$'}pulse_pid" "${'$'}audio_pid"; do
                [ -z "${'$'}pid" ] || kill "${'$'}pid" 2>/dev/null || true
              done
              ${if (media != null) "rm -f -- ${quote(media.directory + "/environment")}" else ""}
              rm -f -- "${'$'}bridge/rfb"
              rm -rf -- "${'$'}state"
            }
            trap cleanup EXIT HUP INT TERM
            export DISPLAY=:$display
            export XAUTHORITY="${'$'}state/Xauthority"
            cookie=${'$'}(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
            [ "${'$'}{#cookie}" -eq 32 ] || { echo 'LYRA_X11: Unable to generate X11 authorization cookie.' >&2; exit 1; }
            touch "${'$'}XAUTHORITY"
            xauth -f "${'$'}XAUTHORITY" add "${'$'}DISPLAY" . "${'$'}cookie"
            export XDG_RUNTIME_DIR="${'$'}state/runtime"
            mkdir -m 700 "${'$'}XDG_RUNTIME_DIR"
            unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS WAYLAND_DISPLAY
            "${'$'}server" :$display -geometry ${options.width}x${options.height} -depth 24 \
              -rfbport -1 -rfbunixpath "${'$'}bridge/rfb" -rfbunixmode 0600 -SecurityTypes None \
              -auth "${'$'}XAUTHORITY" -AlwaysShared -nolisten tcp \
              -FrameRate ${options.framesPerSecond} -desktop ${quote("Lyra Linux :$display")} &
            server_pid=${'$'}!
            count=0
            until xdpyinfo -display "${'$'}DISPLAY" >/dev/null 2>"${'$'}state/display-check.log"; do
              kill -0 "${'$'}server_pid" 2>/dev/null || { cat "${'$'}state/display-check.log" >&2; echo 'LYRA_X11: X server exited. Check the log for missing fonts, display conflicts or unsupported options.' >&2; exit 1; }
              count=${'$'}((count + 1))
              [ "${'$'}count" -lt 100 ] || { cat "${'$'}state/display-check.log" >&2; echo 'LYRA_X11: X server readiness timed out.' >&2; exit 1; }
              sleep 0.1
            done
            ${media?.let { GuestMediaBootstrap.script(it, options).prependIndent("            ") }.orEmpty()}
            dbus-run-session -- /bin/sh -c ${quote(launch)} &
            desktop_pid=${'$'}!
            wait "${'$'}desktop_pid"
        """.trimIndent()
    }

    private fun nativeCommand(display: Int, options: GuiOptions, id: String, media: GuestMedia?, authority: String): String {
        require(authority == "/run/lyra/${id.replace("-", "").take(16)}/xs/auth")
        val launch = if(options.appMode) "openbox >/dev/null 2>&1 & exec /bin/sh -lc ${quote(options.desktopCommand)}" else options.desktopCommand
        return """
            set -eu
            command -v xdpyinfo >/dev/null 2>&1 || { echo 'LYRA_X11: Install x11-utils / xorg-xdpyinfo.' >&2; exit 127; }
            command -v dbus-run-session >/dev/null 2>&1 || { echo 'LYRA_X11: Install dbus / dbus-x11.' >&2; exit 127; }
            ${if(options.appMode) "command -v openbox >/dev/null 2>&1 || { echo 'LYRA_X11: Install openbox for app previews.' >&2; exit 127; }" else ""}
            umask 077
            state=${directory(id)}
            mkdir -m 700 "${'$'}state"
            desktop_pid=''; audio_pid=''; pulse_pid=''; policy_pid=''
            cleanup() {
              trap - EXIT HUP INT TERM
              for pid in "${'$'}desktop_pid" "${'$'}policy_pid" "${'$'}pulse_pid" "${'$'}audio_pid"; do
                [ -z "${'$'}pid" ] || kill "${'$'}pid" 2>/dev/null || true
              done
              ${if(media != null) "rm -f -- ${quote(media.directory + "/environment")}" else ""}
              rm -rf -- "${'$'}state"
            }
            trap cleanup EXIT HUP INT TERM
            export DISPLAY=:$display
            export XAUTHORITY=${quote(authority)}
            export XDG_RUNTIME_DIR="${'$'}state/runtime"
            mkdir -m 700 "${'$'}XDG_RUNTIME_DIR"
            unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS WAYLAND_DISPLAY
            count=0
            until xdpyinfo -display "${'$'}DISPLAY" >/dev/null 2>"${'$'}state/display-check.log"; do
              count=${'$'}((count + 1))
              [ "${'$'}count" -lt 100 ] || { cat "${'$'}state/display-check.log" >&2; echo 'LYRA_X11: Native X11 socket/authorization check failed.' >&2; exit 1; }
              sleep 0.1
            done
            ${media?.let { GuestMediaBootstrap.script(it,options).prependIndent("            ") }.orEmpty()}
            dbus-run-session -- /bin/sh -c ${quote(launch)} &
            desktop_pid=${'$'}!
            wait "${'$'}desktop_pid"
        """.trimIndent()
    }
}
