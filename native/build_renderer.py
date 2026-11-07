"""Rebuild the bundled Android PIE renderer from checked, pinned sources.

Requires Python 3.12+, Meson 1.8.5, Mako 1.3.10, Ninja, git and Android NDK r28+.
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
    replace(virgl / 'vtest/meson.build', 'dependencies : [libvirglrenderer_dep, gallium_dep],',
            "dependencies : [libvirglrenderer_dep, gallium_dep, cc.find_library('EGL'), cc.find_library('GLESv3')],")
    return virgl

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
[built-in options]
c_link_args = ['-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384']
"""
        if not cross.exists() or cross.read_text(encoding='utf-8') != cross_text:
            cross.write_text(cross_text, encoding='utf-8')
        if not (build / 'build.ninja').exists():
            run(sys.executable, '-m', 'mesonbuild.mesonmain', 'setup', build, virgl,
                '--cross-file', cross, '-Ddefault_library=static', '-Dplatforms=egl',
                '-Dvenus=false', '-Dtests=false', '-Dvideo=false', env=env)
        run(args.ninja, '-C', build, '-j', '4', 'vtest/virgl_test_server', env=env)
        output = MODULE / 'src/main/jniLibs' / abi / 'liblyra_virgl_exec.so'
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(build / 'vtest/virgl_test_server', output)
        run(toolchain / ('llvm-strip' + suffix), '--strip-unneeded', output)
        run(toolchain / ('llvm-readelf' + suffix), '-l', '-d', output)
        manifest[abi] = hashlib.sha256(output.read_bytes()).hexdigest()
    (HERE / 'renderer-sha256.json').write_text(json.dumps(manifest, indent=2) + '\n')

if __name__ == '__main__':
    main()
