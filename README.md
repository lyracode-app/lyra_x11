# Lyra X11

**English** · [简体中文](README.zh-CN.md)

Lyra X11 is a standalone Android library (`:lyra_x11`) that brings a Linux X11 desktop into an app: the desktop itself, plus keyboard, mouse, touch and IME input for X11 programs running inside PRoot.

The default backend is an embedded **X.Org/Lorie DDX** running in its own Android process. Root-window pixels reach the UI as **AHardwareBuffer → EGLImage → SurfaceView**, so the regular display path needs no compression, no RFB decoding and no CPU readback. Guests connect over the standard X11 socket with Xauthority, and PRoot only supplies general directory binds. A private **TigerVNC** pipeline stays available as an optional fallback.

```text
Linux X11 app → /tmp/.X11-unix/X100 → native X server (Android process)
                                   ↓ shared AHardwareBuffer + fence
                               EGLImage → SurfaceView
                                        ↘ GPU mirror → floating window
fallback: Linux TigerVNC → private Unix RFB → Android Bitmap
```

`display_backend="auto"` starts the native server before the desktop command runs and moves to VNC only when that fails. `native` reports its own error, `vnc` selects the older pipeline directly, and the choice is remembered per Linux environment together with resolution, frame rate and recent commands. CPU readback opens only for screenshots you request. Every session owns its server and up to eight Surfaces; the current UI uses the full-screen page and the floating window.

## Highlights

- A native X11 desktop inside an app window, without the Termux app, root or a background daemon.
- Up to four sessions across environments and displays, switchable while they run.
- Touch, mouse, wheel, physical keyboard and IME, with keyboard layouts handled by Linux XKB.
- Session audio through PulseAudio or PipeWire, played by Android `AudioTrack`.
- Optional VirGL, Venus, Turnip and ANGLE backends, with software rendering as the compatibility baseline.
- The `linux_gui` AI tool for guided setup, app previews, screenshots and floating windows.

## Getting started

1. Open the PRoot Linux settings of the app and tap the desktop icon next to an environment.
2. Ask the AI to call `linux_gui(action="guide")`, let it check the environment against the shared [installation and troubleshooting spec](src/main/assets/lyra_x11/GUI_TOOL_SPEC.md), then install the minimal dependencies with `proot_command`. For LXDE on Debian/Ubuntu:

   ```sh
   apt update
   apt install --no-install-recommends x11-utils dbus-x11 fonts-dejavu-core lxde-core lxterminal
   ```

3. Choose LXDE, Xfce, LXQt, Openbox or an entry from history under "Presets / recent", or type a custom command. Each environment remembers its last command, resolution, frame rate and the previous eight commands. The toolbar folds away once the session connects; the small button in the top-right corner brings it back.
4. To launch more programs from another terminal of the same environment, copy the `DISPLAY` and `XAUTHORITY` lines from the top of the session log and use them for that launch.

Android manages the native X server, and the guest scripts run under `dbus-run-session` instead of systemd, a display manager, the system bus or a login manager. Desktops that insist on that infrastructure need their own setup inside the rootfs; this module installs and edits nothing in the imported distribution.

## Session model

One Linux session maps to one Android desktop surface, and the window manager inside that session arranges its Linux windows. Different environments, and different displays of the same environment, are independent sessions in the session list. Capacity is four concurrent sessions, 16,588,800 pixels of combined desktop area and a single desktop of up to 3840×2160.

The full-screen page rotates to landscape and hides the system bars. The toolbar floats above the desktop, so expanding it never squeezes the picture. Tap is a left click, long press a right click, drag a left-button drag, and two fingers scroll. USB and Bluetooth mice with wheels, physical keyboards and modifiers all work. Over the desktop Android's pointer is hidden and the Linux cursor is drawn from the framebuffer at the matching scale. The IME sends confirmed candidates as Unicode keysyms and handles Backspace and Enter. Keys and buttons are released whenever focus is lost or the session changes. A few system keys and shortcuts stay with Android. Glyphs appear once the Linux side has a font that covers them.

Back leaves the desktop running; the foreground service notification returns to it and can stop all desktops. Closing one session terminates its PRoot supervisor and tracees. Disabling or deleting an environment closes its graphical sessions first. A process killed by the system does not restore graphical sessions. When an old X server lingers, the startup log reports the display conflict: clear it inside the Linux environment or restart the app runtime.

## AI previews and floating windows

`linux_gui` exposes `guide`, `list`, `start`, `status`, `show`, `hide`, `stop`, `screenshot` and `request_overlay_permission`. The definitions, routing adapter, spec and UI are maintained in this module, and custom launch commands follow the app's existing PRoot command approval rules.

```json
{"action":"start","linux_id":"debian","app_command":"cd /workspace/demo && python3 main.py","mode":"overlay","width":1024,"height":768,"fps":30}
```

An app preview starts Openbox and a session D-Bus on its own, is interactive right away and leaves the remembered desktop command untouched. Openbox plus the GUI libraries the project needs must be installed. Floating mode uses Android's "display over other apps" permission; when it is missing the tool opens the system permission page so the launch can be retried.

The floating window drags by its title bar, resizes from the bottom-right corner, minimizes, raises the IME, goes full screen or closes the session. `hide` only hides it, `stop` terminates the selected session.

For a background AI launch, the real preview window appears first and the foreground service starts afterwards, which satisfies the Android 15+ visible-overlay requirement. A failure in either step returns the error and stops that new session. [Android foreground service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

The screenshot button and `screenshot` save the Linux framebuffer directly, so no screenshot tool is needed inside Linux and the Android toolbar stays out of the image. Android 10+ saves to `Pictures/LyraCode`, older releases to the app's external pictures directory. The tool returns the resulting URI or path.

## Module layout

| Path | Responsibility |
| --- | --- |
| `src/main/.../GuiRuntime.kt` | Host interface: environment list, process launch, environment removal notification |
| `src/main/.../GuestBootstrap.kt` | Standalone POSIX guest launch protocol, Unix sockets, dependency checks, Xauth, D-Bus |
| `src/main/.../GuiSessions.kt` | Multi-environment and multi-display lifecycle, authentication, capacity limits, logs |
| `src/main/.../rfb/` | RFB, VNC authentication, pixel and ZRLE decoding, testable on the JVM |
| `src/main/.../X11DisplayView.kt` | Local bitmap upload, Android composition, touch, physical input, IME |
| `src/main/.../X11Activity.kt` | Container-agnostic session UI |
| `src/main/.../X11OverlayWindow.kt` | Service-managed draggable and resizable floating GUI |
| `src/main/assets/lyra_x11/GUI_TOOL_SPEC.md` | Single source of truth for AI installation and troubleshooting |
| `host/java/` | The only PRoot host adapter, compiled as `:app` source to reach its internal API |
| `src/test/` | Protocol, compression, input message, authentication, isolation and shutdown race tests |

Module code, resources, tests and the host adapter all live in this directory. The root project registers the module, and the app declares the dependency, host source directory, Activity, settings entry and AI tool route. PRoot supplies optional directory binds and a lifecycle hook that withdraws disabled environments before launch, while graphics protocol and driver code stays here with the viewer.

## Transport, input and performance

- RFB 3.3/3.7/3.8 with ZRLE, CopyRect and Raw, plus the DesktopSize, Cursor and LastRect extensions.
- One zlib state per connection. ZRLE decodes through a 16 KiB streaming buffer and fills solid rectangles in bulk, so a full-screen decompressed copy is never allocated; CopyRect handles overlapping regions.
- Framebuffers update by dirty rectangle and compose on a hardware-accelerated Android Canvas. Updates inside a frame merge into a single redraw, and network reads and decompression stay off the main thread.
- One incremental update request per connection. Background sessions request at most about 2 Hz, and the active session follows its frame-rate cap. The 15, 30, 60, 90 and 120 FPS presets apply to both backends, and 90/120 FPS asks Android for a matching display refresh rate. Achieved frame rate follows screen capability and load.
- Pointer motion is coalesced, while key and button edges travel in order. An overflowing bounded input queue closes the connection, so a button release is never dropped silently. While the desktop holds focus, a physical keyboard goes to Linux first; the native path forwards scan codes and leaves layouts to XKB, and the on-screen keyboard keeps submitting text through the Android IME.
- Decoded size, rectangles, compressed lengths, decompression volume, cursor and string lengths all have ceilings. Stopping a session releases its framebuffer, and error logs stay under 16 KiB.

RFB re-encodes guest pixels, decodes them on the host and uploads bitmaps, which adds a copy per frame. That suits general 2D desktops; video, games, CAD and 4K/60 workloads belong on the native backend. Users report LXDE at 1920×1080/30 FPS running smoothly on their phones.

## Audio output

Each session runs its own guest PulseAudio, or PipeWire with pipewire-pulse and WirePlumber 0.5+. A standard pipe sink or tunnel writes 48 kHz stereo S16LE into a private FIFO that Android plays through `AudioTrack`. No audio TCP listener, user password, systemd unit or recording permission is involved, and ALSA programs use the pulse plugin configuration a session generates instead of global files.

`audio=auto` prefers an existing PulseAudio server and then the full PipeWire stack; `pulseaudio`, `pipewire` and `off` can be chosen explicitly, and the result is remembered per environment. Android limits the output queue to 80 ms and drops the oldest whole frame when it overflows. Writes stay off the UI thread, and audio focus, mute, multi-session mixing and `AudioTrack` service rebuilds are handled for you. Android decides between speaker, Bluetooth and USB routing, and the phone media volume controls the output level. End-to-end latency also includes the guest service, the kernel FIFO and the output device.

Mute is available in the toolbar, in the floating window and through `linux_gui(action=audio, session_id=..., muted=...)`. `status.media` reports the selected backend, frames received/written/dropped, queue depth, underruns, audio focus and errors. Install commands and the diagnostic flow live in the [tool spec](src/main/assets/lyra_x11/GUI_TOOL_SPEC.md).

## Linux GPU acceleration

The module ships virglrenderer 1.3.0 with libepoxy 1.5.10 as Android services for ARM64 and x86_64, using the Android vendor EGL/GLES stack. Nothing has to be installed through Termux, no root is needed and the service never touches GPU device nodes directly. Source archives, the applied Termux Android patches, licenses, the 16 KiB aligned executables and the rebuild scripts are all in this module; see [native/README.md](native/README.md).

`graphics=auto` probes Android GLES 3 and the renderer in a separate process, then validates the container's Mesa virpipe. The guest first tries an EGL pixel readback and falls back to `glxinfo -B` or `glxgears -info`. `graphics=virgl` requires the validation to pass, and `software` skips the host GPU services.

Android devices differ in system version, CPU/GPU vendor and driver stack, so GL and Vulkan capabilities and driver maturity vary widely. **Software rendering is the most compatible choice** and the safe default on an unknown device: `graphics=auto` falls back to it whenever validation fails, and `graphics=software` never starts the host GPU services. GPU acceleration pays off on devices whose drivers cooperate, and each mode below targets one hardware family.

- **Mali / Immortalis (Dimensity).** `graphics=venus` is the experimental path: container Mesa Venus reaches Android system Vulkan through a private vtest socket, and the desktop OpenGL stack tries Zink first. On Debian 13 install `mesa-vulkan-drivers vulkan-tools mesa-utils python3` and keep the existing Mesa EGL, GLX and DRI packages. Available versions follow the device driver and the X11 presentation path.
- **Adreno (Snapdragon).** `graphics=turnip` installs a KGSL Turnip driver inside the container that matches the distribution, architecture and GPU, under the app's existing permissions; `linux_gui(action="guide")` walks the AI through the matching package. Vulkan uses Turnip directly and OpenGL prefers Zink. [Driver packages](https://github.com/lfdevs/mesa-for-android-container/releases)
- Venus validates hardware driver ID, device creation, submission and fences with a small probe at startup, while Turnip still enumerates through `vulkaninfo`. Both modes require `vkcube` to complete an X11 swapchain test. A failed default present falls back to a CPU copy while 3D stays on the GPU. `status.media.vulkan`, `vulkan_presentation` and `guest_vulkan_log` show the details. `status.media.graphics` reports `zink` once Zink draws desktop GLSL, reads pixels back and passes the GLX check; otherwise guest OpenGL returns to VirGL. `glxinfo -B` and `eglinfo -B -p x11` confirm the GL versions in use.

`graphics=virgl_angle` runs a Vulkan-backed ANGLE inside the vtest process. The current build enables it on Mali-G925 renderers, checks that the bundled ANGLE libraries load, and requires the guest to reach a core desktop OpenGL 3.3 context with a GLSL 3.30 draw and readback before the desktop starts, so the container needs Python 3, `libegl1`, `libgl1` and `libgl1-mesa-dri`. Native `virgl` needs no Vulkan and stays on the plain GLES path. When `glxinfo -B` reports `X_GetImage BadMatch` on the embedded X server, query versions with `eglinfo -B -p x11` and check a windowed GLX context with `glxgears -info`.

A failed Vulkan validation retires the Venus listener and swaps in a VirGL-only listener, so the next launch still gets a working graphics path. When the failure sits in the device or fence stage, disabling feedback and using synchronous submission is worth trying, with the X11 frame output as the deciding test. For a retest, capture `vulkan_probe_stage`, `vulkan_probe_supervisor_log`, `guest_venus_probe_log`, `guest_venus_probe_retry_log` and the `LYRA_VENUS_*` lines from the session log, and create a new session. [Mesa Venus](https://docs.mesa3d.org/drivers/venus.html)

Each session owns its renderer process, which serves multiple clients internally, so stopping a session ends only the native processes it owns. `status.media.host_gpu` and `guest_graphics_log` report the driver actually in use, and `graphics=virgl` marks a validated guest. Distributions whose Mesa lacks virpipe fall back automatically.

## Local access and cleanup

Xvnc runs as `-rfbport -1 -rfbunixpath ... -rfbunixmode 0600 -SecurityTypes None`, so RFB never opens a TCP port. The host creates a short private cache directory (0700) and binds only that session's socket directory into its guest. Android connects through a filesystem Unix socket, and no VNC password is generated, passed around or requested. X11 disables TCP too, authenticates each client with its own random MIT-MAGIC-COOKIE-1, and keeps the Android clipboard separate.

Only that private Unix transport accepts RFB `SecurityTypes None`. The retained TCP compatibility code still requires authentication, and there is no anonymous TCP path or remote-address setting. The legacy password-file generator now writes TigerVNC d3des key bits in the correct order and was checked against a real `tigervncpasswd -f`; production traffic uses the private channel instead. Isolation covers the display and media channels and follows PRoot itself, so Linux environments under the same UID share one trust domain.

Environments never share `/tmp`. Every new PRoot process mounts a stable `/run/lyra` and `/tmp/.X11-unix` for its environment. Native Xauthority lives in the host's private session directory and the VNC fallback keeps its own copy in the rootfs session state directory. Each GUI session maintains its audio/GPU sockets and environment export file independently; other commands run `status.environment_exports` first to connect. Terminals opened before an upgrade need one restart. RFB keeps its own short-path bind. Stopping a session ends its supervisor, native X server, audio and renderer processes, and directory cleanup does not follow guest symlinks. A crash or force-kill can leave temporary files behind for a later cleanup.

## Build and verification

```powershell
.\gradlew.bat :lyra_x11:testDebugUnitTest :lyra_x11:lintDebug :app:assembleDebug
```

JVM tests cover the password-free private handshake, rejection of anonymous TCP, pixel and input messages, ZRLE, CopyRect, size limits, independent view refresh, environment isolation, shutdown races, launch arguments and command memory. `src/androidTest` holds Unix socket tests on the real Android filesystem: build the test APK with `:lyra_x11:assembleDebugAndroidTest` and run `:lyra_x11:connectedDebugAndroidTest` with a device attached.

A release also needs the [ART regression check](tools/ART_VERIFICATION.md). `tools/verify-art.py` runs the real R8 APK with its mapping on Android and exercises GUI sessions and composition tasks, catching optimized class-loading problems that debug tests miss.

Media coverage spans PCM framing and backlog drops, audio/GPU settings with remembered configuration, script generation, and real-device AudioTrack, multi-client EGL/vtest and resource-directory ownership tests. The native path was verified on Android 17 / targetSdk 37 / 16 KB page emulators. Tone and channel checks from PA 17.0 and PipeWire 1.2.7 + WirePlumber 0.5.8 into the FIFO used Linux builds of the project's PRoot sources, root mode and private binds; WSL disables its seccomp fast path, so those runs describe the Linux environment. Audio routing and GPU performance follow the device and SoC, so verify both on the hardware you target.

`tools/tigervnc-smoke.sh` connects the real Kotlin decoder from the build output to stock TigerVNC and reuses the same guest launch script, Xauth and session D-Bus to verify live frames and password-file compatibility. Set `LYRA_X11_CLASSPATH` to the module `classes.jar` plus the Kotlin stdlib; the Linux/JDK17+ host needs the shared dependencies from the tool spec, Python3/libX11 and `setsid`. Setting `LYRA_X11_EXEC_WRAPPER` exercises PRoot's socket directory bind: the wrapper receives `LYRA_X11_HOST_BRIDGE` and `LYRA_X11_GUEST_BRIDGE` and then runs the remaining command. IME behavior, overlay permission, rotation and GPU drivers go through real-device verification.

## Native X11 sources

Source archives, pinned revisions, build scripts, integration code and licenses are in [native/xserver](native/xserver/README.md). The status page and the AI's `status.display_backend` report `ahb_frames`, `uploaded_frames`, `gpu_mirrors`, `screenshot_readbacks` and the transport actually in use, so the active path is readable from the counters.

Native 2D X11 drawing, VirGL application readback and Vulkan CPU-copy presentation all consume CPU. When comparing VirGL with Venus/Zink on a phone, pin the resolution, frame cap and scene, and record the actual driver versions, presentation path, process CPU time and frame times. Log first-run shader compilation separately from the warmed-up cache.

## References and license

Module code is licensed under GPL-3.0. The projects below inform the design and interfaces; the Termux-X11 native DDX and its build dependencies are integrated with pinned sources.

- [Termux-X11](https://github.com/termux/termux-x11): native Android X server, shared tmp, input and rendering design reference (surveyed revision `f1d9834236341a5b3723cd7e4fe65601673a2d5a`).
- [TigerVNC Xvnc documentation](https://tigervnc.org/doc/Xvnc.html): guest X server command interface.
- [RFB protocol specification](https://github.com/rfbproto/rfbproto/blob/master/rfbproto.rst): network format, VNC authentication, ZRLE and extension definitions.
- Install commands follow the [Debian package](https://packages.debian.org/en/stable/x11/tigervnc-standalone-server), [Alpine package](https://pkgs.alpinelinux.org/package/edge/community/aarch64/tigervnc), [Arch tool package](https://archlinux.org/packages/extra/x86_64/xorg-xdpyinfo/) and [Fedora X11 server package](https://packages.fedoraproject.org/pkgs/tigervnc/tigervnc-x11-server/) pages. Fedora 43+ splits `tigervnc-x11-server` from `xdpyinfo`.

The pinned VirGL/libepoxy sources and Android patches, with their MIT license, are described in [native](native/README.md). Their complete sources ship with the module, and the license texts are packaged into assets/lyra_x11/licenses. TigerVNC, PulseAudio/PipeWire, desktops, fonts and guest Mesa come from the distribution package manager rather than APK binaries.

