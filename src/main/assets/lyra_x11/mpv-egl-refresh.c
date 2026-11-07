/* Refresh Mesa's cached X11 surface dimensions before mpv presents a frame. */
#define _GNU_SOURCE
#include <EGL/egl.h>
#include <dlfcn.h>
#include <pthread.h>

static __typeof__(&eglSwapBuffers) real_swap;
static pthread_once_t swap_once = PTHREAD_ONCE_INIT;

static void resolve_swap(void)
{
    real_swap = dlsym(RTLD_NEXT, "eglSwapBuffers");
}

EGLBoolean EGLAPIENTRY eglSwapBuffers(EGLDisplay display, EGLSurface surface)
{
    pthread_once(&swap_once, resolve_swap);
    if (!real_swap)
        return EGL_FALSE;

    EGLint height;
    /* Mesa refreshes both cached dimensions in this query. */
    eglQuerySurface(display, surface, EGL_HEIGHT, &height);
    return real_swap(display, surface);
}
