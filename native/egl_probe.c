/* Lyra: isolated EGL capability probe. Runs in the renderer process, never HWUI. */
#include <epoxy/gl.h>
#include <epoxy/egl.h>
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <inttypes.h>
#include <sys/ucontext.h>
int lyra_egl_probe(void);
void lyra_report_crash(void *opaque);

void lyra_report_crash(void *opaque) {
    ucontext_t *context = opaque;
    uintptr_t pc = 0;
#if defined(__aarch64__)
    pc = context->uc_mcontext.pc;
#elif defined(__x86_64__)
    pc = context->uc_mcontext.gregs[REG_RIP];
#endif
    Dl_info info = {0};
    if (dladdr((void *)pc, &info))
        fprintf(stderr, "LYRA_NATIVE_CRASH library=%s offset=0x%" PRIxPTR " symbol=%s\n",
            info.dli_fname, pc - (uintptr_t)info.dli_fbase, info.dli_sname ? info.dli_sname : "unknown");
#if defined(__x86_64__)
    const uintptr_t *stack = (const uintptr_t *)context->uc_mcontext.gregs[REG_RSP];
    for (unsigned i = 0; i < 16; ++i) {
        if (dladdr((void *)stack[i], &info))
            fprintf(stderr, "LYRA_NATIVE_STACK slot=%u library=%s offset=0x%" PRIxPTR "\n",
                i, info.dli_fname, stack[i] - (uintptr_t)info.dli_fbase);
    }
#endif
}

static void json_string(const char *text) {
    putchar('"');
    for (const unsigned char *p = (const unsigned char *)(text ? text : ""); *p; ++p) {
        if (*p == '"' || *p == '\\') putchar('\\');
        if (*p >= 32) putchar(*p);
    }
    putchar('"');
}
int lyra_egl_probe(void) {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint major, minor, count;
    EGLConfig config;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    int result = 1;
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, &major, &minor)) goto done;
    const EGLint attributes[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8, EGL_NONE};
    if (!eglChooseConfig(display, attributes, &config, 1, &count) || !count) goto done;
    const EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    const EGLint surface_attributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    eglBindAPI(EGL_OPENGL_ES_API);
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attributes);
    surface = eglCreatePbufferSurface(display, config, surface_attributes);
    if (context == EGL_NO_CONTEXT || surface == EGL_NO_SURFACE ||
        !eglMakeCurrent(display, surface, surface, context)) goto done;
    const char *renderer = (const char *)glGetString(GL_RENDERER);
    const char *version = (const char *)glGetString(GL_VERSION);
    const char *vendor = (const char *)glGetString(GL_VENDOR);
    if (!renderer || !version) goto done;
    printf("{\"renderer\":"); json_string(renderer);
    printf(",\"vendor\":"); json_string(vendor);
    printf(",\"version\":"); json_string(version);
    bool desktop_gl_features = epoxy_gl_version() >= 32 &&
        epoxy_has_gl_extension("GL_EXT_depth_clamp") &&
        epoxy_has_gl_extension("GL_EXT_blend_func_extended") &&
        epoxy_has_gl_extension("GL_EXT_disjoint_timer_query");
    printf(",\"desktop_gl_features\":%s}\n", desktop_gl_features ? "true" : "false");
    result = 0;
done:
    if (result) fprintf(stderr, "LYRA_EGL_PROBE_FAILED error=0x%x\n", eglGetError());
    if (display != EGL_NO_DISPLAY) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglTerminate(display);
    }
    return result;
}
