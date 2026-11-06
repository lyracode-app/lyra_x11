# Android VirGL renderer

`src/main/jniLibs/{arm64-v8a,x86_64}/liblyra_virgl_exec.so` is a real PIE **executable**, packaged under a JNI library name so Android extracts it into the executable native library directory. It statically links VirGL/libepoxy; it is not a JNI entry point. Runtime dependencies are Android's libc, libdl, libm, EGL and GLES. Both binaries use 16 KiB ELF LOAD alignment and Android API 26.

Sources, patches, licenses and build recipe are kept here. No Termux runtime, driver binary, root daemon or hardcoded Termux data path is needed. APKs must keep `jniLibs.useLegacyPackaging=true` so `ProcessBuilder` can execute the extracted binary.

## Pinned sources

- virglrenderer 1.3.0: upstream annotated tag `0e0bd48c039cbc6ab7111989e4d4bd98184f63a5`, commit `ca50e008863837e094747a69974dde3ae148aeaa`, https://gitlab.freedesktop.org/virgl/virglrenderer. `upstream/virglrenderer-1.3.0.tar.gz` was made with `git archive --format=tar.gz --prefix=virglrenderer-1.3.0/ ca50e008863837e094747a69974dde3ae148aeaa`; the GitLab archive download endpoint returned an anti-bot HTML page, which was rejected.
- libepoxy 1.5.10: https://github.com/anholt/libepoxy/tree/1.5.10, unchanged release archive.
- Android patches: https://github.com/termux/termux-packages/tree/c9e1b12fdf3483d4a66b0b19bf752098a39f8815/packages/virglrenderer-android. Only the eight patches listed in `build_renderer.py` are applied. ANGLE/Venus/Vulkan and 32-bit patches are excluded. `patches/build.sh` is the upstream reference recipe, not the Lyra build command.
- SHA256 of both source bundles is enforced by the build script. `sources-sha256.json` records sources, patches, probe and recipe; `renderer-sha256.json` records the shipped executable hashes.

Additional deterministic edits in `build_renderer.py`: build libepoxy as a static Meson subproject; use Android's default EGL display; use an independent 1×1 pbuffer for every rendering/fence context instead of requiring surfaceless extensions; handle EGL 1.4 GLES context attributes without pretending higher minor versions are available; add the isolated `--lyra-probe` mode and immediate logging. Termux's conservative dual-source blend workaround and S3TC decompression are retained.

The GLES backend rejects desktop GL 4.x/3.3 context requests instead of relying on vendor EGL to reject them. This avoids an emulator EGL failure while preserving real GLES 3.0/3.1/3.2 capability checks. Renderer crash diagnostics include library-relative native offsets.

## Rebuild

Install Python 3.12+, Meson 1.8.5, Mako 1.3.10, GNU patch, Ninja and Android NDK r28+. Build tools are development dependencies, not APK dependencies. On Windows Git for Windows supplies GNU patch. A clean checkout needs no network fetch by the build script.

```powershell
python -m pip install meson==1.8.5 mako==1.3.10
python lyra_x11/native/build_renderer.py --ndk G:/sdk/ndk/28.2.13676358 --ninja G:/sdk/cmake/3.22.1/bin/ninja.exe
```

Linux uses the NDK's `linux-x86_64` host toolchain; Windows uses `windows-x86_64`. Generated source/build trees live in ignored `native/build/`. To change a patch or build edit, move the old `native/build/` aside before rebuilding. Verify `llvm-readelf -l -d` reports 0x4000 LOAD alignment and only Android platform DT_NEEDED libraries. Do not rename a generic shared library and try to execute its `main` symbol.

## Runtime contract and limits

Each GUI session owns one server process with `--no-fork --multi-clients --use-egl-surfaceless --use-gles --socket-path <private socket>`. Guest Mesa clients use `GALLIUM_DRIVER=virpipe`, `LIBGL_ALWAYS_SOFTWARE=1` (the Mesa software loader selects the remote virpipe driver), and `VTEST_SOCKET_NAME`. No GL version or extension override is fabricated. Actual `glxinfo -B` must identify VirGL before these exports reach the desktop/apps. AUTO falls back; explicit VIRGL fails with the diagnostic log.

Android EGL must pass an isolated GLES 3 probe; known software renderers are rejected for production AUTO. This path is suitable for Mali/Immortalis as well as other vendor EGL implementations; Turnip is not used. It accelerates compatible guest OpenGL work, independently of the native X11 / VNC display selection. Guest application readback may remain; Vulkan, generic guest DRI3 drivers and hardware video decoding are not implemented.

`NativeMediaTest` checks the actual executable, EGL context and two sequential vtest clients under the application UID. A successful emulator test does not establish compatibility or performance on a particular phone/GPU.

Embedded X11 display sources, JNI, AHardwareBuffer renderer and build recipe: [xserver/README.md](xserver/README.md).
