# Embedded Android X11 server

This directory owns the native server, JNI client, AHardwareBuffer renderer, source archives and offline build. Host integration, Android views and guest bootstrap remain under `lyra_x11` as well.

The server is derived from Termux-X11 revision `f1d9834236341a5b3723cd7e4fe65601673a2d5a`. `sources.json` pins every submodule and archive hash. Archives contain the complete corresponding sources and upstream licenses. The Termux Android/Java application, global discovery listener, `app_process` entry point and external app dependency are not used.

## Build

Python 3.12+, CMake 3.22+, Ninja, GNU patch and Android NDK r28+ are required. Windows Git supplies GNU patch. No network downloads occur during this build.

```powershell
python lyra_x11/native/xserver/build_xserver.py --ndk G:/sdk/ndk/28.2.13676358 --cmake G:/sdk/cmake/3.22.1/bin/cmake.exe --ninja G:/sdk/cmake/3.22.1/bin/ninja.exe
```

Both ARM64 and x86_64 build by default; `--abi` selects one ABI. The outputs are `liblyra_xserver.so` (JNI/shared server implementation) and `liblyra_xserver_exec.so` (a real PIE executable). They target API 26 with 16 KiB LOAD alignment. The APK extracts JNI libraries into Android's executable native library directory. The managed process sets `LD_LIBRARY_PATH` to that directory; it never executes code from rootfs or writable app storage. `binaries.json` records shipped hashes.

Build trees are ignored in `build/`. After changing integration edits, move that directory aside and rebuild. `adapt.py` describes deterministic edits to the pinned DDX/renderer; `build_xserver.py` applies pinned upstream patches, selects the private native entry points, embeds the XKB compiler and handles Windows header name collisions. Generated parser/key tables are bundled, so Bison and a host C compiler are not build dependencies. `regenerate.py` explains how to regenerate them on Linux. The XKB asset is xkeyboard-config 2.42 from Ubuntu's `xkb-data_2.42-1ubuntu1_all.deb`, with its upstream copyright notices retained.

## Ownership and presentation

Each GUI session owns one native X.Org process. Its X11 listener is mounted at `/tmp/.X11-unix/X<display>` in that Linux environment; authentication uses a random MIT-MAGIC-COOKIE-1 file under its private `/run/lyra/<session>/xs` directory. TCP is disabled. The renderer/control socket is private to Android, mode 0600, and never exposed to other apps.

The DDX sends root buffers by AHardwareBuffer/SCM_RIGHTS handles. The Android EGL thread imports them as EGLImage textures and samples them into SurfaceView. CPU access to the root buffer and GPU sampling are serialized with the shared request mutex and GPU fences. CPU mappings are released before sampling instead of speculatively relocking a write-locked allocation from another process. No image compression, RFB decoder, per-frame Bitmap or CPU pixel readback is involved in this path.

All EGL Surface creation/destruction runs on that renderer thread. JNI queues updates under the state lock; a Surface destruction callback waits for EGL release. Up to eight surfaces share a session; extra surfaces use a GPU texture copy of the completed viewport. Secondary surfaces retain their own aspect fit. A 1×1 pbuffer keeps the context current with no visible Surface. Active drawing follows Android Choreographer; no visible surfaces means no presentation. Hidden sessions retain 2 Hz server bookkeeping for Present completion without GPU presentation. Screenshots compose at the full desktop resolution, independent of floating-window size, and perform an explicit, counted readback only when requested.

`ASharedMemory_getSize` validates IPC state allocations; ashmem descriptors can report `st_size=0` and must not be mistaken for empty memfds. Initialization and close join renderer/IO threads before releasing mappings, window references, FDs and JNI globals. The Android client and server use the same pinned IPC structs and ABI.

VNC remains a separate backend. AUTO fallback happens before launching the guest desktop command. Runtime failure never silently re-executes user code. Ordinary X11 drawing may still use CPU, and guest Mesa virpipe may still read back application rendering; this integration does not provide universal Linux DRM/Vulkan drivers.

Linear DMA-BUF pixmaps imported through DRI3 synchronize CPU cache access when the X server maps them for drawing or copying. This path can consume a Vulkan swapchain image directly as a pixmap while still copying its pixels into the desktop framebuffer.

## Licenses and tests

Termux-X11 is GPL-3.0; its X.Org and supporting libraries retain their original licenses. InputXKB has GPL-2.0-or-later notices. Full source notices remain in the archives; redistributable license texts are packaged under `assets/lyra_x11/licenses/xserver`. Lyra's new integration code follows the repository's AGPL-3.0 license. See `XKB-COPYRIGHT` for keyboard data.

`NativeDisplayTest` uses a real X11 wire client against the embedded process to check cookies, drawing, shared buffers, pointer input, screenshots and multiple Surfaces under the app UID. Emulator results do not establish phone/GPU compatibility or performance. The existing RFB, audio and VirGL tests continue to cover the fallback and media services.
