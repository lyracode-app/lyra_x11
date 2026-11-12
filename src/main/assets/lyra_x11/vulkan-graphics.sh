# Runs in a separate guest tracer after the GUI supervisor has authenticated to X11.
set -eu
umask 077
backend=$1
media=$2
state=$3

lyra_check_venus_device() {
    command -v python3 >/dev/null || { echo 'Install Python 3 for the Venus headless probe.'; return 1; }
    unset VN_PERF
    status=0
    # Require a VkResult for the startup allocation before the probe binds it.
    LC_ALL=C DISPLAY= WAYLAND_DISPLAY= VN_PERF=no_async_mem_alloc \
        timeout -k 1 8 python3 -I "$media/venus-probe.py" "$media/vulkan.stage" \
        >"$media/venus-probe.log" 2>&1 || status=$?
    printf 'Venus headless probe exit=%s\n' "$status"
    tail -c 2000 "$media/venus-probe.log"
    [ "$status" -ne 0 ] || return 0
    case "$(cat "$media/vulkan.stage")" in
        venus_device|venus_create_fence|venus_submit|venus_wait_fence)
            echo 'Retrying Venus without shared-memory feedback or asynchronous queue submit.'
            export VN_PERF=no_fence_feedback,no_semaphore_feedback,no_event_feedback,no_query_feedback,no_async_queue_submit
            status=0
            LC_ALL=C DISPLAY= WAYLAND_DISPLAY= VN_PERF="$VN_PERF,no_async_mem_alloc" \
                timeout -k 1 8 python3 -I "$media/venus-probe.py" "$media/vulkan.stage" \
                >"$media/venus-probe-retry.log" 2>&1 || status=$?
            printf 'Venus synchronous headless probe exit=%s\n' "$status"
            tail -c 1800 "$media/venus-probe-retry.log"
            [ "$status" -eq 0 ]
            ;;
        *) return 1 ;;
    esac
}

lyra_check_turnip_device() {
    command -v vulkaninfo >/dev/null || { echo 'Install vulkaninfo (vulkan-tools).'; return 1; }
    echo vulkaninfo >"$media/vulkan.stage"
    status=0
    LC_ALL=C DISPLAY= WAYLAND_DISPLAY= timeout -k 1 8 vulkaninfo --summary >"$media/vulkaninfo.log" 2>&1 || status=$?
    if [ "$status" -ne 0 ]; then
        printf 'vulkaninfo exited with status %s.\n' "$status"
        tail -c 2800 "$media/vulkaninfo.log"
        return 1
    fi
    echo device_check >"$media/vulkan.stage"
    # vkcube uses GPU0. Inspect that same device instead of matching any GPU in the list.
    awk '/^[[:space:]]*GPU0:/{ device=1; next } /^[[:space:]]*GPU[1-9][0-9]*:/{ device=0 } device' \
        "$media/vulkaninfo.log" >"$state/vulkan-device.log"
    cat "$state/vulkan-device.log"
    if ! grep -Eq '^[[:space:]]*driverID[[:space:]]*=[[:space:]]*DRIVER_ID_MESA_TURNIP[[:space:]]*$' "$state/vulkan-device.log" ||
       ! grep -Eq '^[[:space:]]*deviceName[[:space:]]*=[[:space:]]*[^[:space:]]' "$state/vulkan-device.log" ||
       grep -Eiq 'PHYSICAL_DEVICE_TYPE_CPU|llvmpipe|lavapipe|swiftshader|softpipe|software rasterizer' "$state/vulkan-device.log"; then
        echo 'Vulkan GPU0 did not report a hardware Turnip device.'
        return 1
    fi
}

lyra_check_vulkan() {
    command -v timeout >/dev/null && command -v vkcube >/dev/null || {
        echo 'Install timeout (coreutils) and vkcube (vulkan-tools).'
        return 1
    }
    case "$1" in
        venus)
            export VN_DEBUG=vtest,init MESA_LOG_LEVEL=debug VTEST_SOCKET_NAME="$media/virgl"
            icd_name=virtio
            ;;
        turnip)
            unset VN_DEBUG VTEST_SOCKET_NAME
            icd_name=freedreno
            ;;
        *) return 1 ;;
    esac
    arch=$(uname -m)
    for icd_dir in /etc/vulkan/icd.d /usr/local/share/vulkan/icd.d /usr/share/vulkan/icd.d; do
        for icd in "$icd_dir/${icd_name}_icd.$arch.json" "$icd_dir/${icd_name}_icd.json"; do
            [ -r "$icd" ] && break 2
        done
    done
    if [ ! -r "$icd" ]; then
        printf 'Missing %s Vulkan ICD for %s. See the Lyra GUI guide.\n' "$icd_name" "$arch"
        return 1
    fi
    unset VK_ADD_DRIVER_FILES MESA_VK_WSI_DEBUG MESA_VK_WSI_HEADLESS_SWAPCHAIN
    export VK_DRIVER_FILES="$icd" VK_ICD_FILENAMES="$icd"
    printf 'Vulkan ICD: %s\n' "$icd"
    case "$1" in
        venus) lyra_check_venus_device || return 1 ;;
        turnip) lyra_check_turnip_device || return 1 ;;
    esac
    # Vulkan Tools releases before --wsi used XCB for the vkcube binary on Linux.
    if LC_ALL=C timeout -k 1 2 vkcube --help 2>&1 | grep -q -- '--wsi'; then
        set -- --wsi xcb
    else
        set --
    fi
    echo vkcube >"$media/vulkan.stage"
    status=0
    (
        seconds=10
        if [ "$backend" = venus ]; then
            seconds=5
            export VN_PERF="${VN_PERF:+$VN_PERF,}no_async_mem_alloc"
        fi
        LC_ALL=C timeout -k 1 "$seconds" vkcube "$@" --c 8 --width 96 --height 96
    ) >"$media/vkcube.log" 2>&1 || status=$?
    if [ "$status" -eq 0 ]; then
        echo default >"$media/vulkan.presentation"
    else
        printf 'Default X11 swapchain exit=%s. Retrying with a CPU presentation copy and the same Vulkan device.\n' "$status"
        tail -c 1800 "$media/vkcube.log"
        export MESA_VK_WSI_DEBUG=sw
        echo vkcube_cpu_copy >"$media/vulkan.stage"
        if ! LC_ALL=C timeout -k 1 10 vkcube "$@" --c 8 --width 96 --height 96 >"$media/vkcube.log" 2>&1; then
            echo 'Vulkan GPU detected, but X11 swapchain presentation failed.'
            tail -c 1200 "$media/vkcube.log"
            return 1
        fi
        echo cpu_copy >"$media/vulkan.presentation"
    fi
    echo 'Vulkan X11 swapchain probe completed (8 frames).'
}

lyra_check_zink() {
    command -v python3 >/dev/null && command -v glxinfo >/dev/null || {
        echo 'Install Python 3 and mesa-utils for Zink validation.'
        return 1
    }
    unset LIBGL_ALWAYS_SOFTWARE
    export GALLIUM_DRIVER=zink MESA_LOADER_DRIVER_OVERRIDE=zink
    if [ "${MESA_VK_WSI_DEBUG:-}" = sw ]; then export LIBGL_KOPPER_DRI2=true; fi
    if lyra_probe_zink; then return 0; fi
    [ "${LIBGL_KOPPER_DRI2:-}" != true ] || return 1
    # The embedded server imports pixmap FDs but does not open a DRM node for clients.
    echo 'Retrying Zink without requiring an X11 DRM device or explicit modifiers.'
    export LIBGL_KOPPER_DRI2=true
    lyra_probe_zink
}

lyra_probe_zink() {
    LC_ALL=C EGL_PLATFORM=x11 timeout -k 1 8 python3 -I "$media/virgl-probe.py" --zink || return 1
    glx_log="$state/zink-glx.log"
    if LC_ALL=C timeout -k 1 8 glxinfo -B >"$glx_log" 2>&1; then
        cat "$glx_log"
        grep -Eiq '^OpenGL renderer string:[[:space:]]*zink([[:space:](]|$)' "$glx_log" &&
            ! grep -Ei '^OpenGL renderer string:' "$glx_log" | grep -Eiq 'llvmpipe|lavapipe|swiftshader|softpipe|software rasterizer' &&
            grep -Eq '^OpenGL core profile version string: (3\.[3-9]|[4-9]\.[0-9])' "$glx_log" || return 1
        echo zink_shader_pixels_and_glx >"$media/zink.probe"
        return 0
    fi
    tail -c 1600 "$glx_log"
    grep -q BadMatch "$glx_log" && grep -q '73 (X_GetImage)' "$glx_log" && command -v glxgears >/dev/null || return 1
    echo 'glxinfo hit X_GetImage BadMatch. Checking a mapped GLX window.'
    gears_status=0
    LC_ALL=C timeout -k 1 7 glxgears -info >"$glx_log" 2>&1 || gears_status=$?
    grep -v '^GL_EXTENSIONS[[:space:]]*=' "$glx_log" || true
    case "$gears_status" in 0|124|143) ;; *) return 1 ;; esac
    grep -Eiq '^GL_RENDERER[[:space:]]*=[[:space:]]*zink([[:space:](]|$)' "$glx_log" &&
        ! grep -Ei '^GL_RENDERER[[:space:]]*=' "$glx_log" | grep -Eiq 'llvmpipe|lavapipe|swiftshader|softpipe|software rasterizer' &&
        grep -Eq '^[1-9][0-9]* frames in ' "$glx_log" || return 1
    echo zink_shader_pixels_and_glx_window >"$media/zink.probe"
}

unset LIBGL_KOPPER_DRI2 MESA_LOADER_DRIVER_OVERRIDE MESA_GL_VERSION_OVERRIDE MESA_GLSL_VERSION_OVERRIDE MESA_GLES_VERSION_OVERRIDE
echo icd >"$media/vulkan.stage"
lyra_check_vulkan "$backend" >"$media/vulkan.log" 2>&1 || exit 1
{
    echo 'unset VK_ADD_DRIVER_FILES MESA_VK_WSI_DEBUG MESA_VK_WSI_HEADLESS_SWAPCHAIN'
    printf 'export LYRA_VULKAN_BACKEND=%s VK_DRIVER_FILES=%s VK_ICD_FILENAMES=%s\n' "$backend" "$VK_DRIVER_FILES" "$VK_ICD_FILENAMES"
    if [ "$backend" = venus ]; then
        printf 'export VN_DEBUG=vtest VTEST_SOCKET_NAME=%s\n' "$VTEST_SOCKET_NAME"
        if [ -n "${VN_PERF:-}" ]; then printf 'export VN_PERF=%s\n' "$VN_PERF"; else echo 'unset VN_PERF'; fi
    else
        echo 'unset VN_DEBUG'
    fi
    if [ "${MESA_VK_WSI_DEBUG:-}" = sw ]; then echo 'export MESA_VK_WSI_DEBUG=sw'; fi
} >"$media/vulkan.env.tmp"
mv "$media/vulkan.env.tmp" "$media/vulkan.env"

echo zink >"$media/vulkan.stage"
lyra_check_zink >"$media/zink.log" 2>&1 || exit 2
{
    echo 'unset LIBGL_ALWAYS_SOFTWARE LIBGL_KOPPER_DRI2 MESA_GL_VERSION_OVERRIDE MESA_GLSL_VERSION_OVERRIDE MESA_GLES_VERSION_OVERRIDE'
    echo 'export GALLIUM_DRIVER=zink MESA_LOADER_DRIVER_OVERRIDE=zink'
    if [ "${LIBGL_KOPPER_DRI2:-}" = true ]; then echo 'export LIBGL_KOPPER_DRI2=true'; fi
} >"$media/zink.env.tmp"
mv "$media/zink.env.tmp" "$media/zink.env"
echo complete >"$media/vulkan.stage"
