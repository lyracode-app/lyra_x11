"""Small, deterministic integration edits on the pinned upstream tree."""
from pathlib import Path
def edit(path, old, new, count=-1):
    text=path.read_text(encoding='utf-8')
    if old not in text: raise RuntimeError(f'Missing integration anchor: {path}: {old[:80]}')
    path.write_text(text.replace(old,new,count),encoding='utf-8',newline='\n')

def adapt(source):
    header=source/'lorie/lorie.h'; renderer=source/'lorie/renderer.cpp'
    if 'lyraSurfaceState' in header.read_text(): return
    edit(header,'struct Renderer {','struct Renderer {\n    void *lyraSurfaceState = nullptr;')
    renderer.write_text('#include <stdlib.h>\n'+renderer.read_text())
    edit(renderer,'#include "lorie.h"','#include "lorie.h"\n#include "../lyra/hooks.h"')
    # Initialize lists before the IO thread can enqueue its first buffer.
    lists='    xorg_list_init(&addedBuffers);\n    xorg_list_init(&buffers);\n    xorg_list_init(&removedBuffers);\n'
    edit(renderer, lists, '')
    edit(renderer, '    stopping = false;', lists+'    stopping = false;')
    edit(renderer, '    threadLoop();', '''    pthread_mutex_lock(&stateLock);
    ((LyraSurfaces*)lyraSurfaceState)->ready = true;
    pthread_cond_broadcast(&stateChangeFinishCond);
    threadLoop();''')
    edit(renderer, '        return ((Renderer*) cookie)->initThread();', '''        auto *r=(Renderer*)cookie;
        void *result=r->initThread();
        auto *s=(LyraSurfaces*)r->lyraSurfaceState;
        pthread_mutex_lock(&r->stateLock);
        s->failed=true;
        pthread_cond_broadcast(&r->stateChangeFinishCond);
        pthread_mutex_unlock(&r->stateLock);
        if(!s->ready) {
            if(r->sfc) eglDestroySurface(r->egl_display,r->sfc);
            if(r->ctx) eglDestroyContext(r->egl_display,r->ctx);
        }
        r->jvm->DetachCurrentThread();
        return result;''')
    edit(renderer, 'rendererTestCapabilities(', 'lyraUnusedCapabilities(')
    # Lyra uses explicit CPU/GPU ownership and its own usage-matched EGL pixel probe.
    text=renderer.read_text()
    begin=text.index('static const int relockProbeSize')
    end=text.index('void Renderer::testCapabilities',begin)
    text=text[:begin]+text[end:]
    text=text.replace('*renderer_relock = rendererCanRelockWhileLocked();','*renderer_relock = 0;')
    renderer.write_text(text)
    # Keep vertex data in driver-owned storage, never stack-backed client arrays.
    edit(header,'    GLuint g_texture_program =','    GLuint lyraVertexBuffer = 0;\n    GLint lyraOpaque = -1, lyraOpaqueBgra = -1;\n    GLuint g_texture_program =')
    edit(renderer,'    glGenTextures(1, &cursor.id);','''    glGenTextures(1, &cursor.id);
    glGenBuffers(1, &lyraVertexBuffer);
    lyraOpaque = glGetUniformLocation(g_texture_program,"forceOpaque");
    lyraOpaqueBgra = glGetUniformLocation(g_texture_program_bgra,"forceOpaque");''')
    edit(renderer,'    glDeleteTextures(1, &cursor.id);','    glDeleteTextures(1, &cursor.id);\n    glDeleteBuffers(1, &lyraVertexBuffer);')
    edit(renderer,'    glVertexAttribPointer(p, 2, GL_FLOAT, GL_FALSE, 16, coords);\n    glVertexAttribPointer(c, 2, GL_FLOAT, GL_FALSE, 16, &coords[2]);',
        '''    glBindBuffer(GL_ARRAY_BUFFER,lyraVertexBuffer);
    glBufferData(GL_ARRAY_BUFFER,sizeof(coords),coords,GL_STREAM_DRAW);
    glVertexAttribPointer(p,2,GL_FLOAT,GL_FALSE,16,(void*)0);
    glVertexAttribPointer(c,2,GL_FLOAT,GL_FALSE,16,(void*)8);''')
    # XRGB desktop pixels have undefined alpha; only the cursor preserves transparency.
    edit(renderer, '    "uniform sampler2D texture;\\n" \\', '    "uniform sampler2D texture;\\n" \\\n    "uniform float forceOpaque;\\n" \\')
    edit(renderer, 'texture ";\\n" \\', 'texture ";\\n" \\\n    "   gl_FragColor.a = max(gl_FragColor.a, forceOpaque);\\n" \\')
    edit(renderer, '    glUseProgram(flip ? g_texture_program_bgra : g_texture_program);',
        '    glUseProgram(flip ? g_texture_program_bgra : g_texture_program);\n    glUniform1f(flip ? lyraOpaqueBgra : lyraOpaque, id ? 0.f : 1.f);')
    edit(renderer,'com/termux/x11/LorieView','com/yukisoffd/lyra/x11/nativebackend/NativeDisplay')
    # Default native window must work without Java SurfaceTexture/global-ref leaks.
    start=renderer.read_text().index('static ANativeWindow* createDefaultWindow')
    end=renderer.read_text().index('void* Renderer::initThread()',start)
    text=renderer.read_text(); renderer.write_text(text[:start]+'''static ANativeWindow* createDefaultWindow(JNIEnv*) { return nullptr; }
'''+text[end:])
    edit(header,'EGL_SURFACE_TYPE, EGL_WINDOW_BIT','EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT')
    start=renderer.read_text().index('    win = defaultWin = createDefaultWindow(rendererEnv);')
    end=renderer.read_text().index('    g_texture_program =',start)
    text=renderer.read_text(); renderer.write_text(text[:start]+'''    win = defaultWin = nullptr;
    EGLint pa[] = {EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
    sfc = defaultSfc = eglCreatePbufferSurface(egl_display, cfg, pa);
    if (sfc == EGL_NO_SURFACE || !eglMakeCurrent(egl_display,sfc,sfc,ctx))
        return printEglError("Default pbuffer failed",__LINE__);
    eglSwapInterval(egl_display, 0);
'''+text[end:])
    edit(renderer,'    if (!ctx)\n        return; // never initialized, or already destroyed','    if (!thread)\n        return; // never initialized, or already destroyed')
    edit(renderer, '    munmap(stateCond, sizeof(pthread_cond_t));\n    close(stateCondFd);', '')
    edit(renderer, '    while(stateChanged)', '    while(stateChanged && !((LyraSurfaces*)lyraSurfaceState)->failed)')
    edit(renderer,'bool Renderer::shouldWait(bool *waitingForBuffers) {','bool Renderer::shouldWait(bool *waitingForBuffers) {\n    if (lyraPending(this)) return false;')
    edit(renderer,'        if (windowChanged)\n            refreshContext();','        lyraApplySurfaces(this);\n        lyraCaptureFrame(this);\n        if (windowChanged)\n            refreshContext();')
    # Mirror the completed primary image with GPU copies only, under the same root ownership fence.
    edit(renderer,'    // Wait until root window drawing is finished before giving control back to X server','    lyraMirror(this, buffer);\n\n    // Wait until root window drawing is finished before giving control back to X server')
    edit(renderer,'    if (eglSwapBuffers(egl_display, sfc) != EGL_TRUE)\n        printEglError("Failed to swap buffers", __LINE__);','    lyraPresent(this);')
    # Fence after all surfaces have sampled root, not before the cursor and mirror work.
    edit(renderer,'    waitForFence(egl_display, fence, 0);','    eglDestroySyncKHR(egl_display, fence);\n    fence = eglCreateSyncKHR(egl_display, EGL_SYNC_FENCE_KHR, nullptr);\n    glFlush();\n    waitForFence(egl_display, fence, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR);')
    edit(renderer,'    glDeleteProgram(g_texture_program);','    lyraReleaseSurfaces(this);\n    glDeleteProgram(g_texture_program);')
    edit(renderer,'    ANativeWindow_release(defaultWin);','    if (defaultWin) ANativeWindow_release(defaultWin);')
    # A timed-out GPU fence must never release a buffer that is still being sampled.
    edit(renderer,'if (eglClientWaitSyncKHR(egl_display, fence, flags, FENCE_TIMEOUT_NANOS) == EGL_TIMEOUT_EXPIRED_KHR)',
        'if (eglClientWaitSyncKHR(egl_display, fence, flags, FENCE_TIMEOUT_NANOS) != EGL_CONDITION_SATISFIED_KHR)')
    edit(renderer,'        loge("GPU fence wasn\'t signalled within %lldns, proceeding without it", (long long) FENCE_TIMEOUT_NANOS);',
        '        glFinish(); /* Keep ownership until the GPU really finishes; never publish torn pixels. */')
    # Frame packets may be split by a stream socket. Failed imports must not retain sender pointers.
    buf=source/'lorie/buffer.c'
    edit(buf,'    read(socketFd, &buffer, sizeof(buffer));','''    if(recv(socketFd,&buffer,sizeof(buffer),MSG_WAITALL)!=(ssize_t)sizeof(buffer)) {
        if(outBuffer) *outBuffer=NULL; return;
    }''')
    edit(buf,'            AHardwareBuffer_recvHandleFromUnixSocket(socketFd, &buffer.desc.buffer);', '''        {
            buffer.desc.buffer=NULL;
            if(AHardwareBuffer_recvHandleFromUnixSocket(socketFd,&buffer.desc.buffer)!=0 || !buffer.desc.buffer) {
                if(outBuffer) *outBuffer=NULL; return;
            }
        }''')
    # Server-sized counters/configuration controlled by the host, not display-dependent defaults.
    init=source/'lorie/InitOutput.c'
    edit(init,'void lorieChoreographerFrameCallback(__unused long t, AChoreographer* d) {', '''void lorieFrameTick(void) {
    if (pScreenPtr) { QueueWorkProc(lorieRedraw, NULL, NULL); lorieWakeServer(); }
}

void lorieChoreographerFrameCallback(__unused long t, AChoreographer* d) {''')
    # End CPU ownership before GPU sampling. Retaining a CPU write mapping across
    # frames can leave imported EGLImages stale on drivers with explicit cache upload.
    # EXA Prepare/FinishAccess reacquires/maps only for the duration of an X request.
    text=init.read_text()
    begin=text.index('        // We should unlock and lock buffer in order to update texture content')
    end=text.index('        DamageEmpty(pvfb->damage);',begin)
    text=text[:begin]+'''        lorieRequestLockTake();
        if (priv->locked) {
            LorieBuffer_unlock(priv->buffer);
            priv->locked = NULL;
        }
'''+text[end:]
    text=text.replace('        pvfb->state->drawRequested = TRUE;\n    }','        pvfb->state->drawRequested = TRUE;\n        lorieRequestLockRelease();\n    }')
    text=text.replace('if (index == EXA_PREPARE_DEST && pScreenPtr->GetScreenPixmap(pScreenPtr) == pPix)',
                      'if (pScreenPtr->GetScreenPixmap(pScreenPtr) == pPix)')
    init.write_text(text)
    edit(init,'    rendererTestCapabilities(&pvfb->root.legacyDrawing, &pvfb->rendererRelock);',
        '    rendererTestCapabilities(&pvfb->root.legacyDrawing, &pvfb->rendererRelock);\n    pvfb->root.width = atoi(getenv("LYRA_X11_WIDTH") ?: "1280");\n    pvfb->root.height = atoi(getenv("LYRA_X11_HEIGHT") ?: "720");\n    pvfb->root.framerate = atoi(getenv("LYRA_X11_FPS") ?: "60");')
