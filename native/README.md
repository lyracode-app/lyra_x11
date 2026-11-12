# Android VirGL and Venus renderers

`liblyra_virgl_exec.so` (vtest listener) and `liblyra_venus_exec.so` (Venus render server) under `src/main/jniLibs/{arm64-v8a,x86_64}/` are PIE executables. Android extracts them into the executable native library directory. Both statically link virglrenderer, use 16 KiB ELF LOAD alignment and target Android API 26. Venus loads Android's system `libvulkan.so` at runtime.

Sources, patches, licenses and build recipe are kept here. No Termux runtime, driver binary, root daemon or hardcoded Termux data path is needed. APKs must keep `jniLibs.useLegacyPackaging=true` so `ProcessBuilder` can execute the extracted binary.

## Pinned sources

- virglrenderer 1.3.0: upstream annotated tag `0e0bd48c039cbc6ab7111989e4d4bd98184f63a5`, commit `ca50e008863837e094747a69974dde3ae148aeaa`, https://gitlab.freedesktop.org/virgl/virglrenderer. `upstream/virglrenderer-1.3.0.tar.gz` was made with `git archive --format=tar.gz --prefix=virglrenderer-1.3.0/ ca50e008863837e094747a69974dde3ae148aeaa`; the GitLab archive download endpoint returned an anti-bot HTML page, which was rejected.
- libepoxy 1.5.10: https://github.com/anholt/libepoxy/tree/1.5.10, unchanged release archive.
- Android patches: https://github.com/termux/termux-packages/tree/c9e1b12fdf3483d4a66b0b19bf752098a39f8815/packages/virglrenderer-android. Only the eight patches listed in `build_renderer.py` are applied. ANGLE/Venus/Vulkan and 32-bit patches are excluded. `patches/build.sh` is the upstream reference recipe, not the Lyra build command.
- SHA256 of both source bundles is enforced by the build script. `sources-sha256.json` records sources, patches, probe and recipe; `renderer-sha256.json` records the shipped executable hashes.

`build_renderer.py` builds libepoxy as a static Meson subproject and uses Android's default EGL display. Each rendering or fence context uses an independent 1×1 pbuffer. The isolated `--lyra-probe` mode uses the same EGL dispatch as vtest. The native GLES path retains Termux's dual-source blending workaround and S3TC decompression.

The GLES backend rejects desktop GL 4.x/3.3 context requests instead of relying on vendor EGL to reject them. This avoids an emulator EGL failure while preserving real GLES 3.0/3.1/3.2 capability checks. `LYRA_VREND_FRAMEBUFFER` records the bound draw framebuffer and its status on `GL_INVALID_FRAMEBUFFER_OPERATION`.

On a GLES host with occlusion queries, VirGL implements conditional drawing, clears, and blits when the host lacks native conditional rendering. Mesa requires this capability for guest desktop OpenGL 3.0. The guest profile still depends on the host's other capabilities. In the guest, `eglinfo -B -p x11` reports the actual desktop and GLES profiles and their renderers on the X11 path.

Mesa computes the desktop OpenGL version from the VirGL `GLSL feature level` and the advertised extensions. The `virgl_angle` mode selects Vulkan-backed ANGLE in the vtest process on Mali GPUs. The host probe requires depth clamp, dual-source blending and timestamp queries before starting vtest. Only that ANGLE path enables dual-source blending. The guest must create a core OpenGL 3.3 context and draw and read back a GLSL 3.30 shader before starting the desktop. With Mesa 25.0.7 in the guest, this path exposed desktop OpenGL 3.3 and guest GLES 3.1, while native VirGL provided desktop OpenGL 3.1 and guest GLES 3.2 on the same hardware.

The arm64 ANGLE libraries come from the [Termux angle-android build recipe](https://github.com/termux/termux-packages/tree/master/packages/angle-android) for ANGLE commit `f09a19cebdaf04bddcd3101e9783207cb5cf3e13`. The [aarch64 package](https://packages.termux.dev/apt/termux-main/pool/main/a/angle-android/angle-android_2.1.24923-f09a19ce-2_aarch64.deb) and the two extracted libraries are pinned in `angle-sha256.json`. To recover them, verify the package hash, use `dpkg-deb -x` and copy `libEGL_angle.so` and `libGLESv2_angle.so` from `data/data/com.termux/files/usr/opt/angle-android/vulkan-null/` to `src/main/jniLibs/arm64-v8a/`. Both use 16 KiB ELF LOAD alignment. The APK includes the licenses under `assets/lyra_x11/licenses/angle.txt`.

## Rebuild

Install Python 3.12+, Meson 1.8.5, Mako 1.3.10, PyYAML 6.0.2, GNU patch, Ninja and Android NDK r28+. On Windows Git for Windows supplies GNU patch. A clean checkout needs no network fetch by the build script.

```powershell
python -m pip install meson==1.8.5 mako==1.3.10 PyYAML==6.0.2
python lyra_x11/native/build_renderer.py --ndk G:/sdk/ndk/28.2.13676358 --ninja G:/sdk/cmake/3.22.1/bin/ninja.exe
```

Linux uses the NDK's `linux-x86_64` host toolchain; Windows uses `windows-x86_64`. Generated source/build trees live in ignored `native/build/`. To change a patch or build edit, move the old `native/build/` aside before rebuilding. Verify `llvm-readelf -l -d` reports 0x4000 LOAD alignment and only Android platform DT_NEEDED libraries. Do not rename a generic shared library and try to execute its `main` symbol.

`native/build/<abi>/liblyra_venus_exec.so.debug` contains host renderer debug information and shares the APK executable's ELF Build ID. Use that file with `llvm-symbolizer` and module-relative offsets from the Android crash trace.

## Runtime contract and limits

Each GUI session owns a vtest listener with `--use-egl-surfaceless --use-gles --socket-path <private socket>`. Each connection initializes EGL in a separate worker. If the worker exits on a native signal, the listener logs `LYRA_VTEST_WORKER_EXIT` with the PID and signal and keeps accepting connections. Workers terminate with the listener when the GUI session closes. Context fences still retire normally. Only legacy context-zero fences enter the native FD export table.

Guest Mesa clients use `GALLIUM_DRIVER=virpipe`, `LIBGL_ALWAYS_SOFTWARE=1` (the Mesa software loader selects virpipe), and `VTEST_SOCKET_NAME`. Guest validation prefers a surfaceless EGL pixel check, with a context check through `eglinfo` when Python 3 is unavailable. The GLX compatibility path still handles `X_GetImage BadMatch` with a bounded `glxgears -info` run. Diagnostics record which probe ran. On validation failure, AUTO uses software and explicit VIRGL stops with retry controls and the diagnostic log.

VirGL requires the isolated Android GLES 3 probe to pass and rejects known software renderers. Guest application readback may remain, even when the native display shares an AHardwareBuffer with Android. Video decoding is independent of this graphics path.

`NativeMediaTest` checks the executable, EGL context, isolation after an invalid CLEAR and a worker killed by `SIGKILL`, acceptance of a new connection after the fault, and worker cleanup on listener shutdown. Phone GPU compatibility and performance require device testing.

## Additional rendering paths

- **Mali/Immortalis.** `graphics=venus` adds `--venus` to the session's private vtest listener. Guest Mesa Venus forwards Vulkan to the Android driver. The Vulkan renderer lives in a separate subprocess owned by the connection worker. Android's memfd command buffers are sealed against size changes. Venus needs host external-memory and image-modifier support, and vtest does not import arbitrary guest DMA-BUFs. A phone reporting Vulkan 1.3 may still fail allocation or X11 presentation. The existing ANGLE-backed VirGL projects translate host GLES to Vulkan and still rely on VirGL's guest GL capabilities. A glibc ICD over libhybris is a separate approach with fewer command copies, but current vendor-specific experiments require their own WSI and driver compatibility work. [Mesa Venus](https://docs.mesa3d.org/drivers/venus.html) · [DroidDeck Venus testing](https://github.com/Droid-Deck/DroidDeck/issues/165) · [VirGL/ANGLE](https://github.com/ar37-rs/virgl-angle) · [Blobferry](https://github.com/zolotvok/blobferry)
- **Adreno.** `graphics=turnip` uses a guest-installed Turnip ICD built for Android KGSL and the guest's libc/architecture. This uses `/dev/kgsl-3d0` through existing Android app permissions and bypasses vtest for Vulkan. [Turnip KGSL implementation](https://raw.githubusercontent.com/mirror/mesa/main/src/freedreno/vulkan/tu_knl_kgsl.cc) · [Mesa for Android containers](https://github.com/lfdevs/mesa-for-android-container)
- Both modes select a specific ICD for the session and require a hardware Vulkan device and an X11 `vkcube` run to complete. Zink additionally requires a desktop GL 3.3+ core context, successful GLSL drawing and pixel readback through EGL, and a hardware Zink GLX context. If Zink fails, guest OpenGL tries VirGL while Vulkan retains the selected ICD. Desktop OpenGL 4.6 depends on the GPU exposing the necessary Vulkan features through the chosen driver. [Zink requirements](https://docs.mesa3d.org/drivers/zink.html)
- The guest startup probe runs in a separate PRoot tracer. The Venus worker logs `LYRA_VENUS_HOST_ENUMERATE`, `LYRA_VENUS_HOST_EXT`, and `LYRA_VENUS_HOST_DEVICE` during device discovery. On drivers that support AHardwareBuffer export but lack native fd export, coherent buffer allocations can share the DMA-BUF of a Vulkan-exported AHardwareBuffer. Private, dedicated optimal images use ordinary Vulkan allocation on this path. For external DMA-BUF imports the Android worker tries a cache-matched heap and then an AHardwareBuffer BLOB. `LYRA_VENUS_HOST_BLOB` separates allocator failures from incompatible Vulkan memory types. The headless probe waits for the allocation result and reads back a GPU fill through a 64 KiB guest mapping before testing X11 presentation.
- On import-only Android drivers, Venus intersects the memory types of linear, single-plane BGRA DMA-BUF images with those accepted by the cached heap. `LYRA_VENUS_LINEAR_IMAGE` records the original and filtered masks. The default X11 swapchain shares the image through DRI3, and the embedded X server synchronizes CPU access to the imported pixmap before copying into the desktop framebuffer. The CPU presentation fallback remains available when the default probe fails.
- Abnormal disconnect can leave live Vulkan objects. Their cleanup must finish before destroying the device object mutex. Context cleanup first joins the ring monitor, then frees rings. `LYRA_VENUS_RING` reports the command ring's shared-memory backing, distinct from export of Vulkan device memory. [Related device mutex fix](https://github.com/kevinbryant551-ux/DroidDeck-Pixel/blob/main/tools/venus/patches/0103-destroy-object-mutex-after-objects.patch)
- A per-client vtest worker watches the Venus render-control socket while idle. If the renderer exits, the worker shuts down that guest connection and reaps the child. `LYRA_VENUS_RENDERER_LOST` records an unexpected exit, and the session listener can still serve new clients. Stock Mesa 25.0.7 does not watch that socket during shared-ring fence waits. Returning `VK_ERROR_DEVICE_LOST` from those waits and handling EOF during vtest replies require changes in the guest ICD.

Embedded X11 display sources, JNI, AHardwareBuffer renderer and build recipe: [xserver/README.md](xserver/README.md).
