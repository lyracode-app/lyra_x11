# Lyra Linux GUI Tool Specification v4

This specification guides AI use of `linux_gui` and `proot_command` to set up and diagnose Linux GUI environments and display applications inside Lyra.

## Session contract

- Before initial setup, repair, or launch, call `linux_gui(action="guide")`. Use `list` to identify the environment and its `linux_id`, and to inspect existing sessions. Check and install packages separately in each rootfs. Keep DISPLAY, credentials, and sockets scoped to their own sessions.
- Use `proot_command` to read `/etc/os-release`. Check `xdpyinfo`, `dbus-run-session`, and the selected desktop or window manager. The APK supplies the native X server, XKB data, and authentication cookie. Install only missing components for the chosen setup.
- `display_backend="auto"` prefers the embedded X.Org/Lorie DDX. `native` requires it and `vnc` selects the TigerVNC fallback. Automatic fallback happens before the desktop or application command runs, so the command executes only once. Read `status.display_backend.backend` for the active backend.
- The native server runs in a separate Android process. Sessions in the same environment share a mount at `/tmp/.X11-unix` and have individual DISPLAY values, Xauthority files, and private control sockets. X11 TCP is disabled. Use the session cookie for X11 authentication. Never use `-ac`, `xhost +`, or authentication patches.
- Check or install `Xtigervnc` or `Xvnc` and `xauth` only for the VNC fallback. RFB uses a private Unix socket with mode 0600 and no password. Keep TightVNC, x11vnc, Vino, relays, and display managers out of this setup.
- Create sessions through `linux_gui(action="start")`. Let Lyra manage the X server and its display, cookie, and socket.
- Lyra starts the session D-Bus and sets `DISPLAY`, `XAUTHORITY`, and `XDG_RUNTIME_DIR`. Use that session bus. Avoid starting systemd, a display manager, or a system D-Bus.

## Minimal installation

Identify the distribution and installed components first. Run only the commands needed to fill gaps. Use noninteractive package installation, then verify the commands with `command -v` and inspect exit codes and logs.

### Debian and Ubuntu

Install missing shared dependencies from the following list.

    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends x11-utils dbus-x11 fonts-dejavu-core

Choose one setup.

- LXDE is the recommended desktop. Use `apt-get install -y --no-install-recommends lxde-core lxterminal` and the `lxde` preset (`startlxde`).
- For an application preview, use `apt-get install -y --no-install-recommends openbox` and pass `app_command`. Install the application's own GTK, Qt, Python, or other dependencies as required by the project.
- If the user selects Xfce, use `apt-get install -y --no-install-recommends xfce4` and the `xfce` preset (`startxfce4`).
- If the user selects LXQt, check whether the current distribution provides `lxqt-core`. Install it and use the `lxqt` preset (`startlxqt`).
- For a window manager alone, install `openbox` and use the `openbox` preset (`openbox-session`).

### Alpine

Enable the `community` repository for the installed Alpine release.

    apk add xdpyinfo dbus font-dejavu openbox

Use the `openbox` preset or `app_command` by default. For a full desktop, install the user's choice from packages confirmed available for the same Alpine release. Keep repositories from other releases and Edge out of the setup.

### Arch and Arch ARM

    pacman -S --needed --noconfirm xorg-xdpyinfo dbus ttf-dejavu openbox

Start with `openbox` or an application preview. Avoid partial system upgrades. If the repositories and system are out of sync, report the package manager's actual error first.

### Fedora 43 and later

    dnf install -y xdpyinfo dbus-x11 dejavu-sans-fonts openbox

Start with `openbox` or an application preview. Verify TigerVNC package names against the repositories for the installed Fedora release.

### Other distributions and VNC fallback

Use the distribution's official package information to find `xdpyinfo`, session D-Bus, fonts, and the selected desktop or window manager. Use commands and package names for that distribution.

Install VNC packages only when needed. Debian and Ubuntu use `tigervnc-standalone-server xauth`, Alpine uses `tigervnc xauth`, and Arch uses `tigervnc xorg-xauth`. On Fedora, check `tigervnc-x11-server xorg-x11-xauth` against the installed release.

## Native display and diagnostics

- The native DDX passes shared buffer handles over a private Unix control socket. Android displays them with AHardwareBuffer, EGLImage, and SurfaceView. Linux applications and window managers use the standard X11 protocol. This transport bypasses RFB encoding, decoding, and full-frame bitmap uploads.
- Verify actual AHardwareBuffer use with `display_backend.transport=ahardwarebuffer_eglimage` and an increasing `ahb_frames` counter. `shared_memory_texture_upload` uses shared-memory texture uploads. `vnc_encoding=true` identifies the VNC fallback.
- A session can appear on the main page and in a floating window. A second surface uses GPU mirroring. Each session owns its server, input, and buffers. Hiding all surfaces suspends presentation while Linux applications and audio continue.
- `screenshot_readbacks` should increase only for explicit screenshots. Show the native session before capturing it. Lyra composites at the full desktop resolution and reads the frame back on demand, independent of the floating window size.
- Native X11 reduces display transport overhead. Ordinary X11 2D drawing may still use the CPU, and distribution Mesa builds using virpipe may still read pixels back per application. Check actual capabilities before claiming support for guest Vulkan, DRI3 drivers, or hardware video decoding.
- If automatic fallback occurs, read `fallback_reason` and the session log before adding TigerVNC packages. A native connection that fails at runtime reports an error and leaves application restart to the user. Use a new `display_backend="vnc"` session for comparison when needed.

## Starting sessions and previews

- Desktop example `linux_gui(action="start", linux_id="debian", preset="lxde", mode="fullscreen", width=1920, height=1080, fps=30)`.
- Application preview example `linux_gui(action="start", linux_id="debian", app_command="cd /workspace/demo && python3 main.py", mode="overlay")`. Lyra starts Openbox and the application under the same session D-Bus. Existing desktop sessions run independently.
- `app_command` may be a long-running interactive command. Launch it through `linux_gui` and keep it available for interaction. A headless browser or shell screenshot cannot substitute for a requested interactive preview.
- Floating windows require Android overlay permission. Check `overlay_permission` in `guide` or `list`. Use `request_overlay_permission` to open system settings when needed. Wait for the user to grant permission and verify it before calling `start` or `show`.
- `start` returns `session_id`, status, and the session's `DISPLAY` and `XAUTHORITY`. When it returns `starting`, check `status` until the session is `connected` with `has_frame=true` before reporting that the display is ready.
- The Linux window manager handles multiple windows in one session. Several environments and sessions can run at once. `show` selects a session to display, `hide` keeps it running in the background, and `stop` terminates its GUI and associated PRoot supervisor.
- `screenshot` saves a PNG of the current Linux framebuffer directly and returns its location. The floating and full-screen toolbars offer the same capture function. The PNG contains the Linux desktop without Android controls.
- To launch another program on an existing desktop, use the same `linux_id` with `proot_command` and run the `environment_exports` returned by `status`. Keep the contents of Xauthority cookies out of the conversation.

## Troubleshooting order

1. Call `linux_gui(action="status", session_id=...)` for bounded logs and the current state.
2. Check for missing programs, an invalid desktop command, incompatible Xvnc options or versions, an occupied DISPLAY, and application startup errors. Keep authentication intact and repair only the failing component.
3. If the private channel asks for a VNC password, stop the stale or unmanaged session and create a Lyra session. Keep the server binaries and system authentication settings intact.
4. Use `has_frame`, application logs, and the actual image to assess the display when logs report “Could not find any render nodes” or “Failed to initialize DRI3.”
5. Use 1080p at 30 FPS on devices where the user has verified that configuration. Start at 720p and 30 FPS on other devices. Check the media diagnostics below before claiming guest GPU acceleration.

## Audio

- `start` accepts `audio="auto" | "pulseaudio" | "pipewire" | "off"`. If omitted, it uses the saved setting for the environment. The initial `auto` setting tries PulseAudio first, then a complete PipeWire stack. Prefer an existing stack and install only the missing components.
- Lyra uses a per-session audio service and a private Unix socket and FIFO. The Linux service mixes stereo 48 kHz S16LE. Android AudioTrack sends it to the selected speaker, Bluetooth, or USB route. The bridge works without audio passwords, TCP, systemd, a Linux ALSA sound card, root, or recording permission.
- On Debian and Ubuntu, fill in `pulseaudio pulseaudio-utils` as needed. Add `libasound2-plugins` for ALSA clients. An example is `apt-get install -y --no-install-recommends pulseaudio pulseaudio-utils libasound2-plugins`. Check package availability and running services first. Keep global PulseAudio and ALSA configuration intact and use the session daemon.
- If the user already runs PipeWire, check `pipewire`, `pipewire-pulse`, WirePlumber 0.5 or later, `libpipewire-module-pipe-tunnel`, and the SPA audioconvert and support modules. Debian and Ubuntu use `pipewire pipewire-pulse wireplumber libspa-0.2-modules libpipewire-0.3-modules` where available. Lyra supplies an independent configuration with hardware enumeration disabled. Prefer PulseAudio on WirePlumber 0.4 environments.
- For Alpine, Arch, and Fedora, find the packages for the equivalent commands, pipe sink or tunnel, and ALSA Pulse plugin in the distribution's official repositories. Fedora uses `pipewire-pulseaudio` for Pulse compatibility.
- Check `status.media.audio_backend` for `pulseaudio` or `pipewire`. During playback, `received_frames` and `written_frames` under `audio_output` should increase. `output=playing` confirms that audio was written to Android. Check mute, system volume, Bluetooth routing, and audio focus before reporting what the user can hear.
- Mute or resume audio using `linux_gui(action="audio", session_id=..., muted=true/false)` or the toolbar buttons. Playback continues when switching apps. On loss of Android audio focus, the bridge stops output and discards stale audio. It resumes with current audio after focus returns. Use Resume audio to request focus again if needed.
- New applications in the session inherit audio variables. For a new terminal or `proot_command` in the same environment, first run `status.environment_exports` to source `/run/lyra/<session>/environment`. Reopen terminals started before the upgrade to obtain the stable `/run/lyra` mount. Use the session exports for `PULSE_SERVER` and `PIPEWIRE_REMOTE`.
- If audio startup fails, the graphical desktop can still run. Read `status.media.guest_audio_log` and `audio_output.error`, fill in the specific missing components, and recreate the session. Keep anonymous authentication confined to the private Unix socket.

## GPU acceleration

- `start` accepts `graphics="auto" | "virgl" | "software"`. The initial default is `auto`. The APK includes an isolated Android EGL/GLES VirGL service for ARM64 and x86_64. Each session has a private vtest socket and a separate renderer process for each connection. Use the vendor EGL probe to assess compatibility on Mali, Immortalis, Adreno, and x86 GPUs.
- Dimensity 9400 and Immortalis use this EGL/GLES path. Keep the setup within the app's existing permissions and vendor drivers. Turnip is for Adreno. Leave restricted `/dev/dri` and `/dev/kgsl` nodes, kernel drivers, and SELinux settings alone.
- The guest needs Mesa with VirGL and virpipe and `timeout`. On Debian and Ubuntu, check `libgl1-mesa-dri libegl1 libgles2 mesa-utils coreutils` and `libglx-mesa0` for the GLX compatibility check. Python 3 enables the bundled pixel probe. Use matching Mesa packages from the same distribution repositories.
- After the Android GLES 3 probe, Lyra checks guest Mesa with `GALLIUM_DRIVER=virpipe LIBGL_ALWAYS_SOFTWARE=1 VTEST_SOCKET_NAME=...`. It first draws and reads back a pixel in a surfaceless EGL pbuffer when Python 3 is available. Otherwise it tries `eglinfo -B -p surfaceless`. If headless validation is unavailable or fails, it checks `glxinfo -B` and uses a mapped `glxgears` window for the `X_GetImage BadMatch` case. `LIBGL_ALWAYS_SOFTWARE=1` selects the loader that routes OpenGL to virpipe.
- `status.media.graphics=virgl` indicates successful guest validation. Check `graphics_probe` for the test that passed and `guest_graphics_log` for its renderer and output. Headless validation leaves X11 window presentation to be tested in the running session. `host_gpu` reports the Android renderer. `renderer_exit_code` records an independently observed renderer exit. Auto mode falls back to software if validation fails. Required VirGL reports `validation_failed` and offers a retry or software selection in the launch form. Use actual capabilities and avoid version or extension overrides such as `MESA_GL_VERSION_OVERRIDE`.
- A `LYRA_VTEST_WORKER_EXIT` line identifies an individual renderer worker that stopped abnormally. The listener can still accept new connections. Relaunch the affected guest application to open a new connection. If `LYRA_VREND_FRAMEBUFFER` appears, capture the framebuffer status and surrounding command errors from the same log.
- Applications launched inside a validated session inherit the graphics variables. Run `environment_exports` for extra commands. Check Mesa support separately in each environment. If its Mesa lacks virpipe, report the error and select software until a maintained VirGL/vtest-enabled build is available.
- This path accelerates compatible Linux OpenGL calls independently of the display backend. Native X11 shares an AHardwareBuffer and the VNC fallback uses RFB encoding and decoding. Guest virpipe may still read back pixels. The FPS setting is a cap and actual performance depends on the device and workload.

To run Chromium WebGL through VirGL, use the launcher inside a validated VirGL session. It selects ANGLE's system EGL backend and keeps Chromium's compositor on the CPU. Chromium 156 was verified on the RMX6688 with Mesa 25.0.7 and the Mali-G925 host renderer.

```sh
sh "$LYRA_CHROMIUM_WEBGL" --user-data-dir="$(mktemp -d)"
```

If `chrome://gpu` reports `gl=disabled`, inspect the command line shown by `chrome://version`. On Debian, `/usr/bin/chromium` sources configuration under `/etc/chromium.d/`. In the tested guest, `/etc/chromium.d/99-lyra-proot` added `--disable-gpu`. Remove that option from the guest configuration while preserving its other options. After restarting Chromium, check for `ANGLE_OPENGLES_EGL` and a WebGL renderer containing `virgl`. Keep the window size, session frame cap, and page fixed when comparing performance. Record 2D and WebGL results separately.

When comparing the reported desktop GL version across APKs, run `eglinfo -B -p x11` in each session. Pair each profile version with its renderer line to distinguish VirGL from other Mesa drivers.

On the tested Dimensity 9400 device, Parole displayed the full video in a VirGL session where mpv's EGL output showed only the top of the picture. To test the affected mpv path, compare the two commands with the same video and window geometry. The second refreshes Mesa's cached X11/EGL surface dimensions before each swap for that mpv process. It needs a guest C toolchain and EGL headers, such as `gcc libc6-dev libegl-dev` on Debian.

```sh
mpv --no-config --vo=gpu --gpu-api=opengl --gpu-context=x11egl -- ./video.mp4
sh "$LYRA_MPV_EGL_REFRESH" --no-config --vo=gpu -- ./video.mp4
```

The RMX6688 rendered all 560 rows with `--vo=gpu`. An earlier session exposed desktop GL 2.1, which `gpu-next` rejected. To verify desktop OpenGL after updating VirGL, check the X11 profiles with `eglinfo -B -p x11`, then run `mpv --no-config --vo=gpu-next --gpu-api=opengl --gpu-context=x11egl --opengl-es=no -- ./video.mp4`. To test the GLES profile separately, use `--opengl-es=yes`.

Check the initial frame, resized window, and fullscreen. SDL's OpenGL output is also available as a workaround in that session.

```sh
SDL_RENDER_DRIVER=opengl SDL_VIDEO_X11_FORCE_EGL=0 mpv --vo=sdl -- ./video.mp4
```

If SDL output is unavailable, `mpv --vo=x11 -- ./video.mp4` uses software output for that player. Run `mpv --vo=help` to list supported outputs.
