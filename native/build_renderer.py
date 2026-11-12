"""Rebuild the bundled Android PIE renderer from checked, pinned sources.

Requires Python 3.12+, Meson 1.8.5, Mako 1.3.10, PyYAML 6.0.2, Ninja, git and Android NDK r28+.
No Termux prefix, downloaded executable, vendor driver or root access is used.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile

HERE = Path(__file__).resolve().parent
MODULE = HERE.parent
SOURCES = {
    'virglrenderer-1.3.0': 'c0e7240311896b12025c17497750f72f4a38cdd59b8d7d7f514290855bcffbc5',
    'libepoxy-1.5.10': 'a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15',
}
PATCHES = [1, 2, 4, 5, 6, 8, 9, 10]

def run(*args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)

def apply_source_patch(virgl, patch):
    patch_tool = shutil.which('patch')
    if not patch_tool and sys.platform == 'win32':
        patch_tool = str(Path(shutil.which('git')).resolve().parents[1] / 'usr/bin/patch.exe')
    run(patch_tool or 'patch', '--batch', '--forward', '-p1', '-i', patch, cwd=virgl)

def replace(path, old, new, count=-1):
    text = path.read_text(encoding='utf-8')
    if old not in text:
        raise RuntimeError(f'Upstream source changed: {path}: {old[:60]}')
    path.write_text(text.replace(old, new, count), encoding='utf-8', newline='\n')

def prepare(source):
    source.mkdir(parents=True, exist_ok=False)
    for name, digest in SOURCES.items():
        archive = HERE / 'upstream' / (name + '.tar.gz')
        if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            raise RuntimeError(f'Source checksum mismatch: {archive}')
        with tarfile.open(archive) as bundle:
            bundle.extractall(source, filter='data')
    virgl = source / 'virglrenderer-1.3.0'
    for number in PATCHES:
        patch = next((HERE / 'patches').glob(f'{number:04d}-*.patch.beforehostbuild'))
        apply_source_patch(virgl, patch)
    (virgl / 'subprojects').mkdir(exist_ok=True)
    shutil.move(str(source / 'libepoxy-1.5.10'), str(virgl / 'subprojects' / 'libepoxy'))
    # A static subproject avoids packaging generic libepoxy/libvirgl SONAMEs that
    # could collide with other app modules. All of these changes are deterministic.
    replace(virgl / 'meson.build', "epoxy_dep = dependency('epoxy', version: '>= 1.5.4')",
            "epoxy_dep = subproject('libepoxy', default_options: ['egl=yes', 'glx=no', 'x11=false', 'tests=false']).get_variable('libepoxy_dep')")
    replace(virgl / 'meson.build', "get_variable(pkgconfig: 'epoxy_has_egl')", "get_variable(internal: 'epoxy_has_egl')")
    replace(virgl / 'meson.build', "cc.has_header('epoxy/egl.h', dependencies: epoxy_dep) and ", '')
    # Android's EGL default display is the vendor implementation. Mesa's
    # EGL_PLATFORM_SURFACELESS_MESA token is not an Android platform token.
    replace(virgl / 'src/vrend/vrend_winsys_egl.c',
            'bool has_egl_base = virgl_egl_has_extension_in_string(client_extensions, "EGL_EXT_platform_base");',
            'bool has_egl_base = false; /* Android: use eglGetDisplay(EGL_DEFAULT_DISPLAY). */\n   (void)client_extensions;')
    shutil.copyfile(HERE / 'egl_probe.c', virgl / 'vtest' / 'lyra_egl_probe.c')
    replace(virgl / 'vtest/vtest_main.c', '#include "vtest_server.h"',
            '#include "vtest_server.h"\n#include <string.h>\nint lyra_egl_probe(void);')
    replace(virgl / 'vtest/vtest_main.c', 'return vtest_main(argc, argv);',
            'if (argc == 2 && !strcmp(argv[1], "--lyra-probe")) return lyra_egl_probe();\n    return vtest_main(argc, argv);')
    replace(virgl / 'vtest/meson.build', "'vtest_main.c',", "'vtest_main.c', 'lyra_egl_probe.c',")
    return virgl

def android_angle(virgl):
    dispatch = virgl / 'subprojects/libepoxy/src/dispatch_common.c'
    if 'lyra_angle_enabled' not in dispatch.read_text(encoding='utf-8'):
        replace(dispatch, '''#elif defined(__ANDROID__)
#define GLX_LIB "libGLESv2.so"
#define EGL_LIB "libEGL.so"
#define GLES1_LIB "libGLESv1_CM.so"
#define GLES2_LIB "libGLESv2.so"''', '''#elif defined(__ANDROID__)
static bool lyra_angle_enabled(void)
{
    const char *value = getenv("LYRA_VIRGL_ANGLE");
    return value && strcmp(value, "1") == 0;
}
#define GLX_LIB "libGLESv2.so"
#define EGL_LIB (lyra_angle_enabled() ? "libEGL_angle.so" : "libEGL.so")
#define GLES1_LIB "libGLESv1_CM.so"
#define GLES2_LIB (lyra_angle_enabled() ? "libGLESv2_angle.so" : "libGLESv2.so")''')
    renderer = virgl / 'src/vrend/vrend_renderer.c'
    if 'lyra_angle_vulkan' not in renderer.read_text(encoding='utf-8'):
        replace(renderer, '   bool use_gles : 1;', '   bool use_gles : 1;\n   bool lyra_angle_vulkan : 1;')
        replace(renderer, 'if (feature_id == feat_dual_src_blend) {',
                'if (feature_id == feat_dual_src_blend && !vrend_state.lyra_angle_vulkan) {')
        replace(renderer, '   gles = epoxy_is_desktop_gl() == 0;', '''   gles = epoxy_is_desktop_gl() == 0;
#ifdef __ANDROID__
   const char *angle = getenv("LYRA_VIRGL_ANGLE");
   const char *renderer = (const char *)glGetString(GL_RENDERER);
   vrend_state.lyra_angle_vulkan = angle && !strcmp(angle, "1") && renderer &&
      !strncmp(renderer, "ANGLE (", 7) && strstr(renderer, "Vulkan");
#endif''')
        replace(renderer, 'grctx->shader_cfg.has_dual_src_blend = false;',
                'grctx->shader_cfg.has_dual_src_blend = has_feature(feat_dual_src_blend);')
    meson = virgl / 'vtest/meson.build'
    old_dependencies = 'libvirglrenderer_dep, gallium_dep'
    if 'gallium_dep, epoxy_dep' not in meson.read_text(encoding='utf-8'):
        legacy = ", cc.find_library('EGL'), cc.find_library('GLESv3')"
        if legacy in meson.read_text(encoding='utf-8'):
            replace(meson, legacy, '')
        replace(meson, f'dependencies : [{old_dependencies}],',
                f'dependencies : [{old_dependencies}, epoxy_dep],')

def android_pbuffer(virgl):
    path = virgl / 'src/vrend/vrend_winsys_egl.c'
    if '/* Lyra Android pbuffer */' in path.read_text(encoding='utf-8'):
        return
    replace(path, '   EGLContext egl_ctx;\n   uint32_t extension_bits;',
            '   EGLContext egl_ctx;\n   /* Lyra Android pbuffer */\n   EGLSurface android_surface;\n   bool android_context_attributes;\n   uint32_t extension_bits;')
    replace(path, '   if (!has_bits(egl->extension_bits, EGL_KHR_SURFACELESS_CONTEXT | EGL_KHR_CREATE_CONTEXT)) {',
            '   /* Android can use a pbuffer and core GLES context attributes. */\n   if (false && !has_bits(egl->extension_bits, EGL_KHR_SURFACELESS_CONTEXT | EGL_KHR_CREATE_CONTEXT)) {')
    replace(path, '   if (!virgl_egl_check_extensions(egl))\n      goto fail;',
            '   egl->android_context_attributes = major > 1 || (major == 1 && minor >= 5) ||\n      has_bit(egl->extension_bits, EGL_KHR_CREATE_CONTEXT);\n   if (!virgl_egl_check_extensions(egl))\n      goto fail;', count=1)
    replace(path, '   eglMakeCurrent(egl->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE,\n                  egl->egl_ctx);',
            '   const EGLint surface_att[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };\n   egl->android_surface = eglCreatePbufferSurface(egl->egl_display, egl->egl_conf, surface_att);\n   if (egl->android_surface == EGL_NO_SURFACE ||\n       !eglMakeCurrent(egl->egl_display, egl->android_surface, egl->android_surface, egl->egl_ctx))\n      goto fail;')
    replace(path, '   eglDestroyContext(egl->egl_display, egl->egl_ctx);',
            '   eglDestroyContext(egl->egl_display, egl->egl_ctx);\n   if (egl->android_surface != EGL_NO_SURFACE)\n      eglDestroySurface(egl->egl_display, egl->android_surface);')
    replace(path, '   if (vparams->compat_ctx) {',
            '   if (!egl->android_context_attributes) {\n      /* Do not pretend a GLES 3.0 context satisfies a 3.1/3.2 request. */\n      if (vparams->minor_ver || vparams->compat_ctx)\n         return NULL;\n      ctx_att[2] = EGL_NONE;\n   }\n   if (vparams->compat_ctx) {')
    replace(path, '   return eglMakeCurrent(egl->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE,\n                         egl_ctx) ? 0 : -1;',
            '   EGLSurface surface = egl_ctx == EGL_NO_CONTEXT ? EGL_NO_SURFACE : egl->android_surface;\n   return eglMakeCurrent(egl->egl_display, surface, surface, egl_ctx) ? 0 : -1;')
    main = virgl / 'vtest/vtest_main.c'
    replace(main, '#include <string.h>', '#include <string.h>\n#include <stdio.h>')
    replace(main, 'int main(int argc, char** argv) {',
            'int main(int argc, char** argv) {\n    setvbuf(stdout, NULL, _IONBF, 0);\n    setvbuf(stderr, NULL, _IONBF, 0);')

def android_context_surfaces(virgl):
    path = virgl / 'src/vrend/vrend_winsys_egl.c'
    if 'struct lyra_android_context' in path.read_text(encoding='utf-8'):
        return
    replace(path, 'struct virgl_egl {',
            '/* A surface cannot be current on both renderer and fence threads. */\nstruct lyra_android_context { EGLContext context; EGLSurface surface; };\nstatic _Thread_local struct lyra_android_context *lyra_current;\n\nstruct virgl_egl {')
    replace(path, '   return (virgl_renderer_gl_context)egl_ctx;',
            '   if (egl_ctx == EGL_NO_CONTEXT) return NULL;\n   struct lyra_android_context *ctx = calloc(1, sizeof(*ctx));\n   if (!ctx) { eglDestroyContext(egl->egl_display, egl_ctx); return NULL; }\n   ctx->context = egl_ctx;\n   const EGLint attributes[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };\n   ctx->surface = eglCreatePbufferSurface(egl->egl_display, egl->egl_conf, attributes);\n   if (ctx->surface == EGL_NO_SURFACE) { eglDestroyContext(egl->egl_display, egl_ctx); free(ctx); return NULL; }\n   return (virgl_renderer_gl_context)ctx;', count=1)
    replace(path, '   EGLContext egl_ctx = (EGLContext)virglctx;\n   eglDestroyContext(egl->egl_display, egl_ctx);',
            '   struct lyra_android_context *ctx = virglctx;\n   if (!ctx) return;\n   eglDestroyContext(egl->egl_display, ctx->context);\n   eglDestroySurface(egl->egl_display, ctx->surface);\n   free(ctx);')
    replace(path, '   EGLContext egl_ctx = (EGLContext)virglctx;\n\n   EGLSurface surface = egl_ctx == EGL_NO_CONTEXT ? EGL_NO_SURFACE : egl->android_surface;\n   return eglMakeCurrent(egl->egl_display, surface, surface, egl_ctx) ? 0 : -1;',
            '   struct lyra_android_context *ctx = virglctx;\n   EGLContext context = ctx ? ctx->context : EGL_NO_CONTEXT;\n   EGLSurface surface = ctx ? ctx->surface : EGL_NO_SURFACE;\n   if (!eglMakeCurrent(egl->egl_display, surface, surface, context)) return -1;\n   lyra_current = ctx;\n   return 0;')
    replace(path, '   EGLContext egl_ctx = eglGetCurrentContext();\n   return (virgl_renderer_gl_context)egl_ctx;',
            '   return (virgl_renderer_gl_context)lyra_current;')

def android_client_workers(virgl):
    path = virgl / 'vtest/vtest_server.c'
    if 'LYRA_VTEST_WORKER_START' in path.read_text(encoding='utf-8'):
        return
    apply_source_patch(virgl, HERE / 'patches/lyra-android-vtest-workers.patch')

def gles_conditional_render(virgl):
    path = virgl / 'src/vrend/vrend_renderer.c'
    if 'vrend_emulated_render_condition_passes' in path.read_text(encoding='utf-8'):
        return
    apply_source_patch(virgl, HERE / 'patches/lyra-gles-conditional-render.patch')

def android_venus_ahb_export(virgl):
    venus = virgl / 'src/venus'
    physical = venus / 'vkr_physical_device.c'
    if 'android_ahb_export' not in physical.read_text(encoding='utf-8'):
        replace(venus / 'vkr_physical_device.h', '   bool is_opaque_fd_export_supported;',
                '   bool is_opaque_fd_export_supported;\n   bool android_ahb_export;', count=1)
        replace(physical, '   /* fallback to gbm allocation with dma-buf import */',
                '   if (physical_dev->android_ahb_export) {\n'
                '      info.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;\n'
                '      vk->GetPhysicalDeviceExternalBufferProperties(handle, &info, &props);\n'
                '      const VkExternalMemoryFeatureFlags features = props.externalMemoryProperties.externalMemoryFeatures;\n'
                '      physical_dev->android_ahb_export =\n'
                '         (features & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) &&\n'
                '         !(features & VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT);\n'
                '   }\n\n'
                '   /* fallback to gbm allocation with dma-buf import */', count=1)
        replace(physical, '      image_modifiers |=',
                '      physical_dev->android_ahb_export |=\n'
                '         !strcmp(props->extensionName, "VK_ANDROID_external_memory_android_hardware_buffer");\n'
                '      image_modifiers |=', count=1)
        replace(physical, '   if (physical_dev->KHR_external_fence_fd) {',
                '   physical_dev->android_ahb_export &= foreign_queue && physical_dev->api_version >= VK_API_VERSION_1_1;\n\n'
                '   if (physical_dev->KHR_external_fence_fd) {', count=1)
        replace(physical, 'dma_buf_export=%d opaque_fd_export=%d",',
                'dma_buf_export=%d opaque_fd_export=%d ahb_export=%d",', count=1)
        replace(physical, '              physical_dev->is_opaque_fd_export_supported);',
                '              physical_dev->is_opaque_fd_export_supported, physical_dev->android_ahb_export);',
                count=1)
    device = venus / 'vkr_device.c'
    if 'android_ahb_export' not in device.read_text(encoding='utf-8'):
        replace(device, '   ext_count += physical_dev->KHR_external_memory_fd;',
                '   bool add_foreign_queue = physical_dev->android_ahb_export;\n'
                '   for (uint32_t i = 0; add_foreign_queue && i < ext_count; i++)\n'
                '      add_foreign_queue = strcmp(args->pCreateInfo->ppEnabledExtensionNames[i],\n'
                '                                 "VK_EXT_queue_family_foreign") != 0;\n'
                '   ext_count += physical_dev->android_ahb_export + add_foreign_queue;\n'
                '   ext_count += physical_dev->KHR_external_memory_fd;', count=1)
        replace(device, '      if (physical_dev->KHR_external_memory_fd)',
                '      if (physical_dev->android_ahb_export)\n'
                '         exts[ext_count++] = "VK_ANDROID_external_memory_android_hardware_buffer";\n'
                '      if (add_foreign_queue)\n'
                '         exts[ext_count++] = "VK_EXT_queue_family_foreign";\n'
                '      if (physical_dev->KHR_external_memory_fd)', count=1)
    memory = venus / 'vkr_device_memory.c'
    if 'vkr_android_export_ahb_fd' in memory.read_text(encoding='utf-8'):
        return
    replace(venus / 'vkr_image.h', 'struct vkr_image {\n   struct vkr_object base;',
            'struct vkr_image {\n   struct vkr_object base;\n   VkImageTiling tiling;', count=1)
    replace(venus / 'vkr_image.c', '   vkr_image_create_and_add(dispatch->data, args);',
            '   struct vkr_device *dev = vkr_device_from_handle(args->device);\n'
            '   struct vkr_image *image = vkr_image_create(dispatch->data, args);\n'
            '   if (image) {\n'
            '      image->tiling = args->pCreateInfo->tiling;\n'
            '      vkr_device_add_object(dispatch->data, dev, &image->base);\n'
            '   }', count=1)
    replace(memory, '#include "lyra_android_blob.h"',
            '#include "vkr_image.h"\n#include "lyra_android_blob.h"', count=1)
    replace(memory, '   VkExportMemoryAllocateInfo local_export_info;',
            '   VkExportMemoryAllocateInfo local_export_info;\n'
            '   bool share_host_visible = property_flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;\n'
            '#if defined(__ANDROID__) && !defined(ENABLE_GBM_ALLOCATION)\n'
            '   bool export_ahb = false;\n'
            '   const VkMemoryDedicatedAllocateInfo *dedicated =\n'
            '      vkr_find_struct(alloc_info->pNext, VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO);\n'
            '   const bool can_export_ahb = physical_dev->android_ahb_export && physical_dev->EXT_external_memory_dma_buf &&\n'
            '      !physical_dev->is_dma_buf_fd_export_supported && !physical_dev->is_opaque_fd_export_supported &&\n'
            '      physical_dev->udmabuf_dev_fd < 0 &&\n'
            '      (property_flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);\n'
            '   if (can_export_ahb && !might_export && dedicated && dedicated->image &&\n'
            '       vkr_image_from_handle(dedicated->image)->tiling == VK_IMAGE_TILING_OPTIMAL)\n'
            '      share_host_visible = false; /* Keep private optimal images on the GPU. */\n'
            '#endif', count=1)
    replace(memory, '   if ((property_flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) && !res_info) {',
            '   if (share_host_visible && !res_info) {', count=1)
    replace(memory, '         if (force_udmabuf_import) {',
            '#if defined(__ANDROID__) && !defined(ENABLE_GBM_ALLOCATION)\n'
            '         export_ahb = can_export_ahb && !might_export && (!dedicated || !dedicated->image);\n'
            '         if (export_ahb) {\n'
            '            local_export_info = (VkExportMemoryAllocateInfo){\n'
            '               .sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO,\n'
            '               .pNext = alloc_info->pNext,\n'
            '               .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,\n'
            '            };\n'
            '            alloc_info->pNext = &local_export_info;\n'
            '            args->ret = VK_SUCCESS;\n'
            '         } else\n'
            '#endif\n'
            '         if (force_udmabuf_import) {', count=1)
    replace(memory, '         alloc_info->pNext = &local_import_info;',
            '         if (local_import_info.fd >= 0)\n'
            '            alloc_info->pNext = &local_import_info;', count=1)
    replace(memory, 'struct vkr_device_memory *mem = vkr_device_memory_create_and_add(ctx, args);',
            'struct vkr_device_memory *mem = vkr_device_memory_create(ctx, args);', count=1)
    replace(memory, '   mem->device = dev;',
            '#if defined(__ANDROID__) && !defined(ENABLE_GBM_ALLOCATION)\n'
            '   if (export_ahb) {\n'
            '      udmabuf_fd = vkr_android_export_ahb_fd(dev, alloc_info, mem->base.handle.device_memory);\n'
            '      if (udmabuf_fd < 0) {\n'
            '         dev->proc_table.FreeMemory(dev->base.handle.device, mem->base.handle.device_memory, NULL);\n'
            '         free(mem);\n'
            '         args->ret = VK_ERROR_INVALID_EXTERNAL_HANDLE;\n'
            '         return;\n'
            '      }\n'
            '   }\n'
            '#endif\n\n'
            '   mem->device = dev;', count=1)
    replace(memory, '   mem->memory_type_index = mem_type_index;',
            '   mem->memory_type_index = mem_type_index;\n'
            '   vkr_device_add_object(ctx, dev, &mem->base);', count=1)
    replace(memory, '   int fd;\n   if (mem->udmabuf_fd >= 0) {',
            '   int fd = -1;\n   if (mem->udmabuf_fd >= 0) {', count=1)
    replace(memory, '      if (ret != VK_SUCCESS) {\n         vkr_log("mem fd export failed (vk ret %d)", ret);',
            '      if (ret != VK_SUCCESS || fd < 0) {\n'
            '         vkr_log("mem fd export failed (vk ret %d fd %d)", ret, fd);', count=1)


def android_venus_linear_images(virgl):
    venus = virgl / 'src/venus'
    image = venus / 'vkr_image.c'
    shutil.copyfile(HERE / 'venus-linear-image.h', venus / 'lyra_linear_image.h')
    if '#include "lyra_linear_image.h"' in image.read_text(encoding='utf-8'):
        return
    replace(venus / 'vkr_image.h', '   VkImageTiling tiling;',
            '   VkImageTiling tiling;\n   bool android_linear_dma_buf;', count=1)
    replace(venus / 'vkr_device.h', '   struct vn_device_proc_table proc_table;',
            '   struct vn_device_proc_table proc_table;\n   uint32_t android_linear_image_types;', count=1)
    replace(venus / 'vkr_device_memory.h', 'struct vkr_device_memory {',
            'uint32_t vkr_android_probe_linear_image_types(struct vkr_device *dev);\n\n'
            'struct vkr_device_memory {', count=1)
    replace(venus / 'vkr_device.c', '   free(exts);\n\n   args->ret = vkr_device_create_queues',
            '#if defined(__ANDROID__) && !defined(ENABLE_GBM_ALLOCATION)\n'
            '   dev->android_linear_image_types = vkr_android_probe_linear_image_types(dev);\n'
            '#endif\n'
            '   free(exts);\n\n   args->ret = vkr_device_create_queues', count=1)
    replace(image, '#include "vkr_physical_device.h"',
            '#include "vkr_physical_device.h"\n#include "lyra_linear_image.h"', count=1)
    replace(image, '      image->tiling = args->pCreateInfo->tiling;',
            '      image->tiling = args->pCreateInfo->tiling;\n'
            '      image->android_linear_dma_buf = vkr_android_is_linear_dma_buf_image(dev, image, args->pCreateInfo);',
            count=1)
    replace(image, '   vn_replace_vkGetImageMemoryRequirements_args_handle(args);',
            '   const struct vkr_image *image = vkr_image_from_handle(args->image);\n'
            '   vn_replace_vkGetImageMemoryRequirements_args_handle(args);', count=1)
    replace(image, '   vk->GetImageMemoryRequirements(args->device, args->image, args->pMemoryRequirements);',
            '   vk->GetImageMemoryRequirements(args->device, args->image, args->pMemoryRequirements);\n'
            '   vkr_android_filter_linear_image_types(dev, image, args->pMemoryRequirements);', count=1)
    replace(image, '   vn_replace_vkGetImageMemoryRequirements2_args_handle(args);',
            '   const struct vkr_image *image = vkr_image_from_handle(args->pInfo->image);\n'
            '   vn_replace_vkGetImageMemoryRequirements2_args_handle(args);', count=1)
    replace(image, '   vk->GetImageMemoryRequirements2(args->device, args->pInfo, args->pMemoryRequirements);',
            '   vk->GetImageMemoryRequirements2(args->device, args->pInfo, args->pMemoryRequirements);\n'
            '   vkr_android_filter_linear_image_types(dev, image, &args->pMemoryRequirements->memoryRequirements);',
            count=1)


def android_venus_disconnect(virgl):
    vtest = virgl / 'vtest/vtest_server.c'
    if 'VTEST_CLIENT_ERROR_RENDERER_LOST' in vtest.read_text(encoding='utf-8'):
        return

    # The render-control socket only receives replies to synchronous requests.
    # While the worker is selecting on inputs, readability means peer closure.
    proxy = virgl / 'src/proxy/proxy_renderer.c'
    replace(proxy, 'void\nproxy_renderer_fini(void)',
            'int\nproxy_renderer_get_control_fd(uint32_t capset_id)\n{\n'
            '   return capset_id == VIRTGPU_DRM_CAPSET_VENUS && proxy_renderer.client\n'
            '      ? proxy_renderer.client->socket.fd : -1;\n'
            '}\n\nvoid\nproxy_renderer_fini(void)', count=1)
    replace(virgl / 'src/proxy/proxy_renderer.h', '#ifdef ENABLE_RENDER_SERVER\n',
            '#ifdef ENABLE_RENDER_SERVER\n\nint proxy_renderer_get_control_fd(uint32_t capset_id);\n', count=1)
    replace(proxy, '      proxy_server_destroy(proxy_renderer.server);',
            '      proxy_server_destroy(proxy_renderer.server,\n'
            '                           proxy_renderer.client &&\n'
            '                           !proxy_socket_is_connected(&proxy_renderer.client->socket));',
            count=1)

    server = virgl / 'src/proxy/proxy_server.c'
    replace(virgl / 'src/proxy/proxy_server.h', 'proxy_server_destroy(struct proxy_server *srv);',
            'proxy_server_destroy(struct proxy_server *srv, bool disconnected);', count=1)
    replace(server, 'proxy_server_destroy(struct proxy_server *srv)',
            'proxy_server_destroy(struct proxy_server *srv, bool disconnected)', count=1)
    start = '      kill(srv->pid, SIGKILL);\n\n      siginfo_t siginfo = { 0 };'
    end = '                   srv->pid, siginfo.si_code, siginfo.si_status);'
    original = server.read_text(encoding='utf-8')
    first = original.index(start)
    last = original.index(end, first) + len(end)
    replace(server, original[first:last],
            '''      siginfo_t siginfo = { 0 };
      int ret;
      do {
         ret = waitid(P_PID, srv->pid, &siginfo, WEXITED | WNOHANG);
      } while (ret == -1 && errno == EINTR);

      const bool lost = disconnected || (ret == 0 && siginfo.si_pid);
      if (ret == 0 && !siginfo.si_pid) {
         kill(srv->pid, SIGKILL);
         do {
            ret = waitid(P_PID, srv->pid, &siginfo, WEXITED);
         } while (ret == -1 && errno == EINTR);
      }

      if (ret == 0)
         proxy_log("LYRA_VENUS_RENDERER_%s pid=%d si_code=%d status=%d\\n",
                   lost ? "LOST" : "EXIT", srv->pid, siginfo.si_code, siginfo.si_status);''',
            count=1)
    replace(virgl / 'src/proxy/proxy_context.c',
            '   if (!proxy_client_destroy_context(ctx->client, ctx->base.ctx_id))',
            '   if (proxy_socket_is_connected(&ctx->client->socket) &&\n'
            '       !proxy_client_destroy_context(ctx->client, ctx->base.ctx_id) &&\n'
            '       proxy_socket_is_connected(&ctx->client->socket))', count=1)

    replace(virgl / 'vtest/vtest.h', 'int vtest_get_context_poll_fd(struct vtest_context *ctx);',
            'int vtest_get_context_poll_fd(struct vtest_context *ctx);\n'
            'int vtest_get_renderer_control_fd(const struct vtest_context *ctx);', count=1)
    renderer = virgl / 'vtest/vtest_renderer.c'
    replace(renderer, '#include "vtest.h"',
            '#include "vtest.h"\n#include "proxy/proxy_renderer.h"', count=1)
    replace(renderer, 'void vtest_set_current_context(struct vtest_context *ctx)',
            '''int vtest_get_renderer_control_fd(const struct vtest_context *ctx)
{
#ifdef ENABLE_RENDER_SERVER
   if (ctx && ctx->context_initialized)
      return proxy_renderer_get_control_fd(ctx->capset_id);
#else
   (void)ctx;
#endif
   return -1;
}

void vtest_set_current_context(struct vtest_context *ctx)''', count=1)
    replace(vtest, '   VTEST_CLIENT_ERROR_COMMAND_DISPATCH,',
            '   VTEST_CLIENT_ERROR_COMMAND_DISPATCH,\n   VTEST_CLIENT_ERROR_RENDERER_LOST,', count=1)
    replace(vtest, '   bool context_need_poll;', '   bool context_need_poll;\n   bool renderer_lost;', count=1)
    replace(vtest, '   int max_fd = -1;\n   int ret;',
            '   int max_fd = -1;\n   int renderer_fd = -1;\n   int ret;', count=1)
    replace(vtest, '   /* accept new clients when there is none or when multi_clients is set */',
            '''   /* Android isolates each vtest client in its own worker process. */
   if (!server.main_server && !list_is_empty(&server.active_clients)) {
      client = list_first_entry(&server.active_clients, struct vtest_client, head);
      renderer_fd = vtest_get_renderer_control_fd(client->context);
      if (renderer_fd >= 0 && renderer_fd < FD_SETSIZE) {
         FD_SET(renderer_fd, &read_fds);
         max_fd = MAX2(renderer_fd, max_fd);
      } else {
         renderer_fd = -1;
      }
   }

   /* accept new clients when there is none or when multi_clients is set */''', count=1)
    replace(vtest, '      if (FD_ISSET(client->in_fd, &read_fds)) {',
            '      if (renderer_fd >= 0 && FD_ISSET(renderer_fd, &read_fds)) {\n'
            '         client->renderer_lost = true;\n'
            '         continue;\n'
            '      }\n\n'
            '      if (FD_ISSET(client->in_fd, &read_fds)) {', count=1)
    replace(vtest, '   CASE(VTEST_CLIENT_ERROR_COMMAND_DISPATCH)',
            '   CASE(VTEST_CLIENT_ERROR_COMMAND_DISPATCH)\n   CASE(VTEST_CLIENT_ERROR_RENDERER_LOST)', count=1)
    replace(vtest, '      if (client->context_need_poll) {',
            '''      if (client->renderer_lost) {
         shutdown(client->in_fd, SHUT_RDWR);
         ret = VTEST_CLIENT_ERROR_RENDERER_LOST;
         goto disconnect;
      }

      if (client->context_need_poll) {''', count=1)
    replace(vtest, '      ret = vtest_client_dispatch_commands(client);\n      if (ret) {',
            '      ret = vtest_client_dispatch_commands(client);\n'
            'disconnect:\n      if (ret) {', count=1)


def android_venus(virgl):
    device_lifetime = virgl / 'src/venus/vkr_device.c'
    if '/* Leftover objects still take object_mutex. */' not in device_lifetime.read_text(encoding='utf-8'):
        replace(device_lifetime, '   mtx_destroy(&dev->object_mutex);\n\n', '', count=1)
        replace(device_lifetime, '   mtx_destroy(&dev->free_sync_mutex);',
                '   /* Leftover objects still take object_mutex. */\n'
                '   mtx_destroy(&dev->object_mutex);\n   mtx_destroy(&dev->free_sync_mutex);', count=1)
    ring_lifetime = virgl / 'src/venus/vkr_context.c'
    if '/* Join the monitor before destroying the rings it reads. */' not in ring_lifetime.read_text(encoding='utf-8'):
        replace(ring_lifetime, '   if (ctx->ring_monitor.started)\n      vkr_context_ring_monitor_fini(ctx);\n\n', '', count=1)
        replace(ring_lifetime, '    * cannot get blocked by the vkDeviceWaitIdle upon device destruction.\n    */\n',
                '    * cannot get blocked by the vkDeviceWaitIdle upon device destruction.\n    */\n'
                '   /* Join the monitor before destroying the rings it reads. */\n'
                '   if (ctx->ring_monitor.started)\n      vkr_context_ring_monitor_fini(ctx);\n\n', count=1)
    transport = virgl / 'src/venus/vkr_transport.c'
    if 'LYRA_VENUS_RING' not in transport.read_text(encoding='utf-8'):
        replace(transport, '   vkr_ring_start(ring);',
                '   vkr_ring_start(ring);\n'
                '   vkr_log("LYRA_VENUS_RING ctx=%u bytes=%u backing=shm started=%d",\n'
                '           ctx->ctx_id, ring->buffer.size, ring->started);', count=1)
    device = virgl / 'src/venus/vkr_physical_device.c'
    if 'LYRA_VENUS_HOST_ENUMERATE' not in device.read_text(encoding='utf-8'):
        replace(device, '   args->ret = vkr_instance_enumerate_physical_devices(instance);',
                '   if (!args->pPhysicalDevices)\n'
                '      vkr_log("LYRA_VENUS_HOST_ENUMERATE begin");\n'
                '   args->ret = vkr_instance_enumerate_physical_devices(instance);\n'
                '   if (!args->pPhysicalDevices || args->ret != VK_SUCCESS)\n'
                '      vkr_log("LYRA_VENUS_HOST_ENUMERATE result=%d devices=%u",\n'
                '              args->ret, instance->physical_device_count);', count=1)
        replace(device, '   uint32_t advertised_count = 0;',
                '   bool image_modifiers = false;\n   bool foreign_queue = false;\n'
                '   uint32_t advertised_count = 0;', count=1)
        replace(device, '      const uint32_t spec_ver = vkr_extension_get_spec_version(props->extensionName);',
                '      image_modifiers |= !strcmp(props->extensionName, "VK_EXT_image_drm_format_modifier");\n'
                '      foreign_queue |= !strcmp(props->extensionName, "VK_EXT_queue_family_foreign");\n\n'
                '      const uint32_t spec_ver = vkr_extension_get_spec_version(props->extensionName);', count=1)
        replace(device, '   physical_dev->extension_count = advertised_count;',
                '   physical_dev->extension_count = advertised_count;\n'
                '   vkr_log("LYRA_VENUS_HOST_EXT memory_fd=%d dma_buf=%d image_modifiers=%d foreign_queue=%d",\n'
                '           physical_dev->KHR_external_memory_fd, physical_dev->EXT_external_memory_dma_buf,\n'
                '           image_modifiers, foreign_queue);')
        replace(device, '      vkr_physical_device_init_queue_family_properties(physical_dev);',
                '      vkr_physical_device_init_queue_family_properties(physical_dev);\n'
                '      vkr_log("LYRA_VENUS_HOST_DEVICE %s api=%u.%u.%u dma_buf_export=%d opaque_fd_export=%d",\n'
                '              physical_dev->properties.deviceName,\n'
                '              VK_API_VERSION_MAJOR(physical_dev->properties.apiVersion),\n'
                '              VK_API_VERSION_MINOR(physical_dev->properties.apiVersion),\n'
                '              VK_API_VERSION_PATCH(physical_dev->properties.apiVersion),\n'
                '              physical_dev->is_dma_buf_fd_export_supported,\n'
                '              physical_dev->is_opaque_fd_export_supported);', count=1)
    context = virgl / 'src/proxy/proxy_context.c'
    seal_guard = '#if defined(HAVE_MEMFD_CREATE) || defined(__ANDROID__)'
    if seal_guard not in context.read_text(encoding='utf-8'):
        # Android's anon_file.c uses the memfd syscall even on API 26 builds.
        replace(context, '#if defined(HAVE_MEMFD_CREATE)', seal_guard)
    if 'LYRA_VENUS_BLOB_REJECTED' not in context.read_text(encoding='utf-8'):
        replace(context, '   if (!reply_fd_count) {\n      proxy_log("invalid reply for blob',
                '   if (!reply_fd_count && reply.fd_type == VIRGL_RESOURCE_FD_INVALID) {\n'
                '      proxy_log("LYRA_VENUS_BLOB_REJECTED id=%" PRIu64, blob_id);\n'
                '      return -ENOMEM;\n'
                '   }\n\n'
                '   if (!reply_fd_count) {\n      proxy_log("invalid reply for blob', count=1)
    memory = virgl / 'src/venus/vkr_device_memory.c'
    shutil.copyfile(HERE / 'venus-android-blob.h', memory.with_name('lyra_android_blob.h'))
    if '#include "lyra_android_blob.h"' not in memory.read_text(encoding='utf-8'):
        replace(memory, '#include "vkr_physical_device.h"',
                '#include "vkr_physical_device.h"\n\n'
                '#if defined(__ANDROID__) && !defined(ENABLE_GBM_ALLOCATION)\n'
                '#include "lyra_android_blob.h"\n'
                '#endif', count=1)
        replace(memory, 'static inline VkResult\nvkr_gbm_get_fd_info_from_allocation_info(UNUSED',
                'static inline UNUSED VkResult\nvkr_gbm_get_fd_info_from_allocation_info(UNUSED', count=1)
        replace(memory,
                '            args->ret = vkr_gbm_get_fd_info_from_allocation_info(\n'
                '               physical_dev, alloc_info, &gbm_bo, &local_import_info);',
                '#if defined(__ANDROID__) && !defined(ENABLE_GBM_ALLOCATION)\n'
                '            args->ret = vkr_android_get_fd_info(\n'
                '               dev, alloc_info, property_flags, &udmabuf_fd, &local_import_info);\n'
                '#else\n'
                '            args->ret = vkr_gbm_get_fd_info_from_allocation_info(\n'
                '               physical_dev, alloc_info, &gbm_bo, &local_import_info);\n'
                '#endif', count=1)
        replace(memory, '   if (!mem) {\n      if (local_import_info.fd >= 0)',
                '   if (!mem) {\n'
                '      if (udmabuf_fd >= 0) {\n'
                '         vkr_log("LYRA_VENUS_HOST_BLOB import_result=%d size=%" PRIu64 " memory_type=%u",\n'
                '                 args->ret, alloc_info->allocationSize, mem_type_index);\n'
                '         close(udmabuf_fd);\n'
                '      }\n'
                '      if (local_import_info.fd >= 0)', count=1)
    dependencies = virgl / 'src/meson.build'
    android_dep = "virgl_depends += [cc.find_library('android')]"
    if android_dep not in dependencies.read_text(encoding='utf-8'):
        replace(dependencies, '   virgl_depends += [venus_dep]',
                '   virgl_depends += [venus_dep]\n'
                "   if host_machine.system() == 'android'\n"
                f'      {android_dep}\n'
                '   endif', count=1)
    android_venus_ahb_export(virgl)
    android_venus_linear_images(virgl)
    path = virgl / 'src/proxy/proxy_server.c'
    if 'LYRA_VENUS_RENDERER_' not in path.read_text(encoding='utf-8'):
        replace(path, '      waitid(P_PID, srv->pid, &siginfo, WEXITED);',
                '      int ret;\n'
                '      do {\n'
                '         ret = waitid(P_PID, srv->pid, &siginfo, WEXITED);\n'
                '      } while (ret == -1 && errno == EINTR);\n'
                '      if (ret == 0)\n'
                '         proxy_log("LYRA_VENUS_RENDERER_EXIT pid=%d si_code=%d status=%d\\n",\n'
                '                   srv->pid, siginfo.si_code, siginfo.si_status);')
    android_venus_disconnect(virgl)
    if 'PR_SET_PDEATHSIG' in path.read_text(encoding='utf-8'):
        return
    replace(path, '#include <signal.h>', '#include <signal.h>\n#include <sys/prctl.h>')
    replace(path, '   pid_t pid = fork();', '   pid_t parent = getpid();\n   pid_t pid = fork();')
    replace(path, '      /* do not receive signals from terminal */',
            '      /* Match the lifetime of the per-connection vtest worker. */\n'
            '      if (prctl(PR_SET_PDEATHSIG, SIGKILL, 0L, 0L, 0L) || getppid() != parent)\n'
            '         _exit(EXIT_FAILURE);\n\n      /* do not receive signals from terminal */')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', required=True, type=Path)
    parser.add_argument('--ninja', default='ninja')
    parser.add_argument('--abi', action='append', choices=['arm64-v8a', 'x86_64'])
    args = parser.parse_args()
    host = 'windows-x86_64' if sys.platform == 'win32' else 'linux-x86_64'
    toolchain = args.ndk.resolve() / 'toolchains/llvm/prebuilt' / host / 'bin'
    suffix = '.exe' if sys.platform == 'win32' else ''
    build_root = HERE / 'build'
    source = build_root / 'sources'
    virgl = source / 'virglrenderer-1.3.0' if source.exists() else prepare(source)
    android_pbuffer(virgl)
    android_context_surfaces(virgl)
    android_client_workers(virgl)
    gles_conditional_render(virgl)
    android_angle(virgl)
    android_venus(virgl)
    egl_source = virgl / 'src/vrend/vrend_winsys_egl.c'
    if 'Reject desktop GL versions' not in egl_source.read_text(encoding='utf-8'):
        replace(egl_source, '   if (!egl->android_context_attributes) {',
                '   /* Reject desktop GL versions on this GLES-only Android backend. */\n   if (vparams->major_ver > 3 || vparams->minor_ver > 2 || vparams->compat_ctx) return NULL;\n   if (!egl->android_context_attributes) {')
    main_source = virgl / 'vtest/vtest_main.c'
    if 'setenv("VIRGL_LOG_LEVEL"' not in main_source.read_text(encoding='utf-8'):
        replace(main_source, '#include <stdio.h>', '#include <stdio.h>\n#include <stdlib.h>')
        replace(main_source, '    setvbuf(stdout, NULL, _IONBF, 0);', '    setenv("VIRGL_LOG_LEVEL", "info", 0);\n    setvbuf(stdout, NULL, _IONBF, 0);')
    server_source = virgl / 'vtest/vtest_server.c'
    if 'lyra_report_crash' not in server_source.read_text(encoding='utf-8'):
        replace(server_source, 'static void handler(int sig, siginfo_t *si, void *unused)',
                'void lyra_report_crash(void *opaque);\nstatic void handler(int sig, siginfo_t *si, void *unused)')
        replace(server_source, '   printf("SIGSEGV!\\n");', '   lyra_report_crash(unused);\n   printf("SIGSEGV!\\n");')
    shutil.copyfile(HERE / 'egl_probe.c', virgl / 'vtest/lyra_egl_probe.c')
    env = os.environ.copy()
    env['PATH'] = str(Path(args.ninja).resolve().parent) + os.pathsep + env['PATH']
    manifest_file = HERE / 'renderer-sha256.json'
    manifest = json.loads(manifest_file.read_text()) if manifest_file.exists() else {}
    for abi in args.abi or ['arm64-v8a', 'x86_64']:
        triple, cpu = ('aarch64-linux-android26', 'aarch64') if abi == 'arm64-v8a' else ('x86_64-linux-android26', 'x86_64')
        build = build_root / abi
        cross = build_root / (abi + '.ini')
        compiler = (toolchain / ('clang' + suffix)).as_posix()
        cross_text = f"""[binaries]
c = ['{compiler}', '--target={triple}']
ar = '{(toolchain / ('llvm-ar' + suffix)).as_posix()}'
strip = '{(toolchain / ('llvm-strip' + suffix)).as_posix()}'
[host_machine]
system = 'android'
cpu_family = '{cpu}'
cpu = '{cpu}'
endian = 'little'
[properties]
needs_exe_wrapper = true
"""
        if not cross.exists() or cross.read_text(encoding='utf-8') != cross_text:
            cross.write_text(cross_text, encoding='utf-8')
        reconfigure = ['--reconfigure'] if (build / 'build.ninja').exists() else []
        run(sys.executable, '-m', 'mesonbuild.mesonmain', 'setup', *reconfigure, build, virgl,
            '--cross-file', cross, '-Ddefault_library=static', '-Dplatforms=egl',
            '-Doptimization=3', '-Ddebug=true', '-Db_ndebug=true',
            "-Dc_link_args=['-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384', '-Wl,--build-id=sha1']",
            '-Dvenus=true', '-Drender-server-worker=thread', '-Dtests=false', '-Dvideo=false', env=env)
        targets = {'vtest/virgl_test_server': 'liblyra_virgl_exec.so',
                   'server/virgl_render_server': 'liblyra_venus_exec.so'}
        run(args.ninja, '-C', build, '-j', '4', *targets, env=env)
        hashes = {}
        for target, filename in targets.items():
            output = MODULE / 'src/main/jniLibs' / abi / filename
            output.parent.mkdir(parents=True, exist_ok=True)
            if filename == 'liblyra_venus_exec.so':
                run(toolchain / ('llvm-objcopy' + suffix), '--only-keep-debug', build / target,
                    build / 'liblyra_venus_exec.so.debug')
            shutil.copyfile(build / target, output)
            run(toolchain / ('llvm-strip' + suffix), '--strip-unneeded', output)
            run(toolchain / ('llvm-readelf' + suffix), '-l', '-d', output)
            hashes[filename] = hashlib.sha256(output.read_bytes()).hexdigest()
        manifest[abi] = hashes
    (HERE / 'renderer-sha256.json').write_text(json.dumps(manifest, indent=2) + '\n')

if __name__ == '__main__':
    main()
