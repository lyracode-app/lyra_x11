package com.yukisoffd.lyra.x11

/** Standard guest daemons only. No package installation, global config changes or TCP listeners. */
object GuestMediaBootstrap {
    fun script(media: GuestMedia, options: GuiOptions): String {
        val audio = options.audio.id
        return """
            media=${GuestBootstrap.quote(media.directory)}
            export PULSE_SERVER="unix:§media/pulse"
            export PULSE_SINK=lyra_output
            export PULSE_LATENCY_MSEC=30
            export PULSE_RUNTIME_PATH="§state/runtime/pulse"
            mkdir -m 700 "§PULSE_RUNTIME_PATH"
            export PULSE_STATE_PATH="§state/pulse-state"
            mkdir -m 700 "§PULSE_STATE_PATH"
            export PIPEWIRE_RUNTIME_DIR="§media"
            export PIPEWIRE_REMOTE=lyra
            export PIPEWIRE_LATENCY=1024/48000
            wait_socket() {
              attempt=0
              until [ -S "§1" ]; do
                kill -0 "§2" 2>/dev/null || return 1
                attempt=§((attempt + 1)); [ "§attempt" -lt 50 ] || return 1
                sleep 0.1
              done
            }
            start_pulse() {
              command -v pulseaudio >/dev/null 2>&1 || return 1
              cat >"§state/pulse.pa" <<EOF
            load-module module-native-protocol-unix socket=§media/pulse auth-anonymous=1
            load-module module-pipe-sink sink_name=lyra_output file=§media/audio.pcm format=s16le rate=48000 channels=2 use_system_clock_for_timing=yes
            set-default-sink lyra_output
            EOF
              env DBUS_SYSTEM_BUS_ADDRESS="unix:path=§state/no-system-bus" DBUS_SESSION_BUS_ADDRESS="unix:path=§state/no-session-bus" \
                pulseaudio --daemonize=no --use-pid-file=no --exit-idle-time=-1 --disable-shm=yes --realtime=no --high-priority=no \
                -nF "§state/pulse.pa" >"§media/audio.log" 2>&1 &
              audio_pid=§!
              if wait_socket "§media/pulse" "§audio_pid"; then echo pulseaudio >"§media/audio.status"; return 0; fi
              kill "§audio_pid" 2>/dev/null || true; wait "§audio_pid" 2>/dev/null || true; audio_pid=''
              rm -f "§media/pulse"
              return 1
            }
            start_pipewire() {
              command -v pipewire >/dev/null 2>&1 || return 1
              command -v pipewire-pulse >/dev/null 2>&1 || return 1
              command -v wireplumber >/dev/null 2>&1 || return 1
              cat >"§state/pipewire.conf" <<EOF
            context.properties = {
              core.daemon = true
              core.name = lyra
              default.clock.rate = 48000
              default.clock.quantum = 1024
              default.clock.min-quantum = 256
              default.clock.max-quantum = 2048
            }
            context.spa-libs = { audio.convert.* = audioconvert/libspa-audioconvert support.* = support/libspa-support }
            context.modules = [
              { name = libpipewire-module-protocol-native }
              { name = libpipewire-module-access args = { access.force = unrestricted } }
              { name = libpipewire-module-metadata }
              { name = libpipewire-module-spa-node-factory }
              { name = libpipewire-module-client-node }
              { name = libpipewire-module-adapter }
              { name = libpipewire-module-link-factory }
              { name = libpipewire-module-pipe-tunnel args = {
                tunnel.mode = sink
                pipe.filename = "§media/audio.pcm"
                audio.format = S16LE
                audio.rate = 48000
                audio.channels = 2
                audio.position = [ FL FR ]
                stream.props = { node.name = lyra_output node.description = "Lyra Android" media.class = Audio/Sink node.virtual = true priority.session = 2000 }
              } }
            ]
            context.objects = [ { factory = spa-node-factory args = { factory.name = support.node.driver node.name = Dummy-Driver priority.driver = 20000 } } ]
            EOF
              cat >"§state/pipewire-pulse.conf" <<EOF
            context.properties = { log.level = 2 }
            context.spa-libs = { audio.convert.* = audioconvert/libspa-audioconvert support.* = support/libspa-support }
            context.modules = [
              { name = libpipewire-module-protocol-native }
              { name = libpipewire-module-client-node }
              { name = libpipewire-module-adapter }
              { name = libpipewire-module-metadata }
              { name = libpipewire-module-protocol-pulse args = {
              server.address = [ "unix:§media/pulse" ]
              pulse.min.req = 256/48000
              pulse.default.req = 1024/48000
              pulse.min.quantum = 256/48000
            } } ]
            EOF
              cat >"§state/pipewire-client.conf" <<EOF
            context.properties = { log.level = 2 }
            context.spa-libs = { audio.convert.* = audioconvert/libspa-audioconvert support.* = support/libspa-support }
            context.modules = [
              { name = libpipewire-module-protocol-native }
              { name = libpipewire-module-client-node }
              { name = libpipewire-module-adapter }
              { name = libpipewire-module-metadata }
            ]
            EOF
              mkdir -p "§state/config/wireplumber/wireplumber.conf.d"
              cat >"§state/config/wireplumber/wireplumber.conf.d/90-lyra.conf" <<EOF
            wireplumber.profiles = { main = {
              hardware.audio = disabled hardware.bluetooth = disabled hardware.video-capture = disabled
              monitor.alsa = disabled monitor.bluez = disabled monitor.v4l2 = disabled monitor.libcamera = disabled
              support.dbus = disabled support.logind = disabled support.reserve-device = disabled
            } }
            EOF
              env DBUS_SYSTEM_BUS_ADDRESS="unix:path=§state/no-system-bus" \
                pipewire -c "§state/pipewire.conf" >"§media/audio.log" 2>&1 &
              audio_pid=§!
              if ! wait_socket "§media/lyra" "§audio_pid"; then return 1; fi
              env DBUS_SYSTEM_BUS_ADDRESS="unix:path=§state/no-system-bus" \
                pipewire-pulse -c "§state/pipewire-pulse.conf" >>"§media/audio.log" 2>&1 &
              pulse_pid=§!
              env XDG_CONFIG_HOME="§state/config" XDG_STATE_HOME="§state/wp-state" XDG_CACHE_HOME="§state/wp-cache" \
                DBUS_SYSTEM_BUS_ADDRESS="unix:path=§state/no-system-bus" \
                DBUS_SESSION_BUS_ADDRESS="unix:path=§state/no-session-bus" PIPEWIRE_CONFIG_NAME="§state/pipewire-client.conf" \
                wireplumber >>"§media/audio.log" 2>&1 &
              policy_pid=§!
              if wait_socket "§media/pulse" "§pulse_pid"; then echo pipewire >"§media/audio.status"; return 0; fi
              return 1
            }
            audio_ok=no
            case $audio in
              pulseaudio) if start_pulse; then audio_ok=yes; fi ;;
              pipewire) if start_pipewire; then audio_ok=yes; fi ;;
              auto) if start_pulse || start_pipewire; then audio_ok=yes; fi ;;
              off) echo off >"§media/audio.status" ;;
            esac
            if [ "§audio_ok" = yes ]; then
              cat >"§state/asound.conf" <<EOF
            </usr/share/alsa/alsa.conf>
            pcm.!default { type pulse server "unix:§media/pulse" }
            ctl.!default { type pulse server "unix:§media/pulse" }
            EOF
              export ALSA_CONFIG_PATH="§state/asound.conf"
            elif [ "$audio" != off ]; then
              for pid in "§policy_pid" "§pulse_pid" "§audio_pid"; do
                [ -z "§pid" ] || kill "§pid" 2>/dev/null || true
              done
              audio_pid=''; pulse_pid=''; policy_pid=''
              echo unavailable >"§media/audio.status"
              echo 'LYRA_AUDIO: Audio unavailable. Install pulseaudio, or pipewire + pipewire-pulse + wireplumber (0.5+), then restart this session.' >&2
              [ ! -f "§media/audio.log" ] || tail -c 4096 "§media/audio.log" >&2
              unset PULSE_SERVER PULSE_SINK PULSE_LATENCY_MSEC PIPEWIRE_RUNTIME_DIR PIPEWIRE_REMOTE PIPEWIRE_LATENCY
            else
              unset PULSE_SERVER PULSE_SINK PULSE_LATENCY_MSEC PIPEWIRE_RUNTIME_DIR PIPEWIRE_REMOTE PIPEWIRE_LATENCY
            fi
            ${graphics(media, options).prependIndent("            ")}
            {
              echo '# Lyra session exports; source only while this session is running.'
              printf 'export DISPLAY=%s\nexport XAUTHORITY=%s\n' "§DISPLAY" "§XAUTHORITY"
              if [ "§audio_ok" = yes ]; then
                printf 'export PULSE_SERVER=%s\nexport PULSE_SINK=lyra_output\nexport PULSE_LATENCY_MSEC=30\n' "§PULSE_SERVER"
                printf 'export PIPEWIRE_RUNTIME_DIR=%s\nexport PIPEWIRE_REMOTE=lyra\nexport PIPEWIRE_LATENCY=1024/48000\n' "§media"
                printf 'export ALSA_CONFIG_PATH=%s\n' "§ALSA_CONFIG_PATH"
              fi
              cat "§state/graphics.env"
            } >"§media/environment.tmp"
            mv "§media/environment.tmp" "§media/environment"
        """.trimIndent().replace('§', '$')
    }

    private fun graphics(media: GuestMedia, options: GuiOptions): String = """
        {
          echo 'unset GALLIUM_DRIVER VTEST_SOCKET_NAME LIBGL_ALWAYS_SOFTWARE LYRA_MPV_EGL_REFRESH LYRA_CHROMIUM_WEBGL'
          echo 'if [ -n "§{LYRA_VULKAN_BACKEND:-}" ]; then'
          echo '  unset LYRA_VULKAN_BACKEND VK_DRIVER_FILES VK_ICD_FILENAMES VN_DEBUG VN_PERF MESA_VK_WSI_DEBUG LIBGL_KOPPER_DRI2 MESA_LOADER_DRIVER_OVERRIDE'
          echo 'fi'
        } >"§state/graphics.env"
        . "§state/graphics.env"
        ${if (options.graphics.requiresVulkan) applyVulkanProbe(media, options) else virglGraphics(media, options)}
    """.trimIndent()

    private fun applyVulkanProbe(media: GuestMedia, options: GuiOptions): String = """
        echo 'unset LIBGL_KOPPER_DRI2 MESA_LOADER_DRIVER_OVERRIDE MESA_GL_VERSION_OVERRIDE MESA_GLSL_VERSION_OVERRIDE MESA_GLES_VERSION_OVERRIDE' >>"§state/graphics.env"
        . "§state/graphics.env"
        : >"§media/vulkan.request"
        attempts=0
        until [ -f "§media/vulkan.supervisor" ] || [ "§attempts" -ge 1000 ]; do
          sleep 0.1
          attempts=§((attempts + 1))
        done
        zink_ready=no
        if [ -f "§media/vulkan.supervisor" ] && [ -s "§media/vulkan.env" ]; then
          echo ${options.graphics.id} >"§media/vulkan.status"
          cat "§media/vulkan.env" >>"§state/graphics.env"
          if [ "§(cat "§media/vulkan.supervisor")" = exit_0 ] && [ -s "§media/zink.env" ]; then
            zink_ready=yes
            echo zink >"§media/graphics.status"
            cp "§media/zink.log" "§media/graphics.log"
            cp "§media/zink.probe" "§media/graphics.probe"
            cat "§media/zink.env" >>"§state/graphics.env"
          fi
        else
          echo validation_failed >"§media/vulkan.status"
        fi
        if [ "§zink_ready" = no ]; then
          echo 'LYRA_GPU: Vulkan or Zink validation failed. Trying the OpenGL compatibility path.' >&2
          ${virglGraphics(media, options).prependIndent("          ")}
        fi
        . "§state/graphics.env"
    """.trimIndent()

    private fun virglGraphics(media: GuestMedia, options: GuiOptions): String {
        if (!media.virgl || options.graphics == GraphicsBackend.SOFTWARE) {
            return "unset GALLIUM_DRIVER VTEST_SOCKET_NAME LIBGL_ALWAYS_SOFTWARE LYRA_MPV_EGL_REFRESH LYRA_CHROMIUM_WEBGL\necho software >\"§media/graphics.status\""
        }
        val angle = options.graphics == GraphicsBackend.VIRGL_ANGLE
        val unsetOverrides = if (angle) "env -u MESA_GL_VERSION_OVERRIDE -u MESA_GLSL_VERSION_OVERRIDE -u MESA_EXTENSION_OVERRIDE " else ""
        val probeArgument = if (angle) " --virgl-angle" else ""
        val probeHelp = if (angle) "Check guest Mesa virpipe, Python 3, libEGL and libGL." else
            "Check guest Mesa virpipe, timeout (coreutils), and Python 3 with EGL/GLES libraries or mesa-utils."
        return """
        export VTEST_SOCKET_NAME="§media/virgl"
        export GALLIUM_DRIVER=virpipe
        export LIBGL_ALWAYS_SOFTWARE=1
        graphics_probe=failed
        graphics_log="§media/graphics.log"
        : >"§graphics_log"
        if command -v timeout >/dev/null 2>&1; then
          if command -v python3 >/dev/null 2>&1 && [ -r "§media/virgl-probe.py" ]; then
            if LC_ALL=C DISPLAY= EGL_PLATFORM=surfaceless timeout -k 1 6 ${unsetOverrides}python3 -I "§media/virgl-probe.py"$probeArgument >>"§graphics_log" 2>&1; then
              graphics_probe=${if (angle) "pbuffer_gl33" else "pbuffer_pixels"}
            else
              echo "LYRA_GPU: Guest EGL pixel probe exited with status §?." >>"§graphics_log"
            fi
          elif [ '$angle' = false ] && command -v eglinfo >/dev/null 2>&1; then
            if LC_ALL=C DISPLAY= EGL_PLATFORM=surfaceless timeout -k 1 6 eglinfo -B -p surfaceless >>"§graphics_log" 2>&1 &&
               grep -Eiq '^OpenGL.* profile renderer:[[:space:]]*virgl([[:space:](]|§)' "§graphics_log"; then
              graphics_probe=surfaceless_context
            fi
          fi
          if [ '$angle' = false ] && [ "§graphics_probe" = failed ] && command -v glxinfo >/dev/null 2>&1; then
            glx_log="§state/glxinfo.log"
            glx_exit=0
            LC_ALL=C timeout -k 1 8 glxinfo -B >"§glx_log" 2>&1 || glx_exit=§?
            cat "§glx_log" >>"§graphics_log"
            if [ "§glx_exit" -eq 0 ] &&
               grep -Eiq '^OpenGL renderer string:[[:space:]]*virgl([[:space:](]|§)' "§glx_log"; then
              graphics_probe=glx_context
            elif grep -q 'BadMatch' "§glx_log" && grep -q '73 (X_GetImage)' "§glx_log" &&
                 command -v glxgears >/dev/null 2>&1; then
              echo 'LYRA_GPU: glxinfo hit X_GetImage BadMatch; checking a mapped GLX window.' >>"§graphics_log"
              gears_log="§state/glxgears.log"
              gears_exit=0
              LC_ALL=C timeout -k 1 10 glxgears -info >"§gears_log" 2>&1 || gears_exit=§?
              echo "glxgears exit status: §gears_exit" >>"§graphics_log"
              grep -v '^GL_EXTENSIONS[[:space:]]*=' "§gears_log" >>"§graphics_log" || true
              # GNU and BusyBox timeout can report different statuses for SIGTERM.
              case "§gears_exit" in
                0|124|143)
                  if grep -Eiq '^GL_RENDERER[[:space:]]*=[[:space:]]*virgl([[:space:](]|§)' "§gears_log" &&
                     grep -Eq '^[1-9][0-9]* frames in ' "§gears_log"; then graphics_probe=glx_window; fi ;;
              esac
            fi
          fi
        fi
        printf '%s\n' "§graphics_probe" >"§media/graphics.probe"
        if [ "§graphics_probe" != failed ]; then
          echo virgl >"§media/graphics.status"
          export LYRA_MPV_EGL_REFRESH="§media/mpv-egl-refresh"
          export LYRA_CHROMIUM_WEBGL="§media/chromium-webgl"
          {
            printf 'export GALLIUM_DRIVER=virpipe\nexport LIBGL_ALWAYS_SOFTWARE=1\nexport VTEST_SOCKET_NAME=%s\n' "§VTEST_SOCKET_NAME"
            printf 'export LYRA_MPV_EGL_REFRESH=%s\nexport LYRA_CHROMIUM_WEBGL=%s\n' "§LYRA_MPV_EGL_REFRESH" "§LYRA_CHROMIUM_WEBGL"
          } >>"§state/graphics.env"
        else
          echo 'LYRA_GPU: $probeHelp' >>"§graphics_log"
          cat "§graphics_log" >&2
          if [ '${options.graphics.requiresVirgl}' = true ]; then
            echo validation_failed >"§media/graphics.status"
            echo 'LYRA_GPU: Explicit VirGL request failed validation. See the graphics log and the GUI guide.' >&2
            exit 1
          fi
          echo software >"§media/graphics.status"
          echo 'LYRA_GPU: Mesa virpipe validation failed; using software. See the graphics log and the GUI guide.' >&2
          unset GALLIUM_DRIVER VTEST_SOCKET_NAME LIBGL_ALWAYS_SOFTWARE LYRA_MPV_EGL_REFRESH LYRA_CHROMIUM_WEBGL
        fi
        """.trimIndent()
    }
}
