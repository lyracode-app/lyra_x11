#include "hooks.h"
#include <cstdlib>
#include <cstring>
#include <cmath>

static LyraSurfaces *data(Renderer *r) { return (LyraSurfaces*)r->lyraSurfaceState; }
bool lyraPending(Renderer *r) { auto *s=data(r); return s && (s->dirty || (s->capture.pending && s->primary>=0)); }

void lyraApplySurfaces(Renderer *r) {
    auto *s=data(r);
    if (!s || !s->dirty) return;
    eglMakeCurrent(r->egl_display,r->defaultSfc,r->defaultSfc,r->ctx);
    s->primary=-1;
    for (int i=0;i<8;i++) {
        auto &t=s->targets[i];
        if (t.changed) {
            if (t.surface) eglDestroySurface(r->egl_display,t.surface);
            if (t.window) ANativeWindow_release(t.window);
            t.window=t.pending; t.pending=nullptr; t.surface=EGL_NO_SURFACE; t.changed=false;
            if (t.window) {
                t.surface=eglCreateWindowSurface(r->egl_display,r->cfg,t.window,nullptr);
                if (!t.surface) { ANativeWindow_release(t.window); t.window=nullptr; }
            }
        }
        if (t.surface && s->primary<0) s->primary=i;
        if (!t.window) t.id=0;
    }
    r->win=nullptr; r->sfc=r->defaultSfc;
    if (s->primary>=0) {
        auto &t=s->targets[s->primary]; r->win=t.window; r->sfc=t.surface;
        int w=ANativeWindow_getWidth(t.window),h=ANativeWindow_getHeight(t.window);
        float scale=fminf((float)w/s->desktopWidth,(float)h/s->desktopHeight);
        r->viewportW=lroundf(s->desktopWidth*scale); r->viewportH=lroundf(s->desktopHeight*scale);
        r->viewportX=(w-r->viewportW)/2; r->viewportY=(h-r->viewportH)/2;
        r->expectedW=s->desktopWidth; r->expectedH=s->desktopHeight;
    }
    eglMakeCurrent(r->egl_display,r->sfc,r->sfc,r->ctx);
    eglSwapInterval(r->egl_display,0);
    if (r->state) {
        r->state->surfaceAvailable=s->primary>=0;
        r->state->drawRequested=true; r->state->waitForNextFrame=false;
    }
    s->dirty=false;
    pthread_cond_broadcast(&r->stateChangeFinishCond);
}

void lyraCaptureFrame(Renderer *r) {
    auto *s=data(r);
    if (s->capture.pending && r->state && s->primary>=0) {
        r->state->drawRequested=true; r->state->waitForNextFrame=false;
    }
}

// Called before releasing the root-buffer GPU fence. Extra viewers never read pixels on CPU.
void lyraMirror(Renderer *r, LorieBuffer *buffer) {
    auto *s=data(r);
    int bufferType=LorieBuffer_description(buffer)->type;
    __atomic_add_fetch(&s->frames,1,__ATOMIC_RELAXED);
    __atomic_add_fetch(bufferType==LORIEBUFFER_AHARDWAREBUFFER ? &s->ahbFrames : &s->uploadFrames,1,__ATOMIC_RELAXED);
    int w=r->viewportW,h=r->viewportH;
    int surfaceH=ANativeWindow_getHeight(r->win);

    pthread_mutex_lock(&r->stateLock);
    if (s->capture.pending) {
        auto &c=s->capture;
        c.width=s->desktopWidth; c.height=s->desktopHeight;
        int cw=c.width,ch=c.height;
        c.pixels=(uint32_t*)malloc((size_t)cw*ch*4);
        if (c.pixels) {
            // Full desktop resolution even when the only viewer is a small floating window.
            GLuint texture=0,fbo=0;
            glGenTextures(1,&texture);r->bindTexture(texture);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,cw,ch,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
            glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE) {
                glViewport(0,0,cw,ch);
                LorieBuffer_bindTexture(buffer);
                const auto *desc=LorieBuffer_description(buffer);
                float u=desc->type==LORIEBUFFER_FD ? (float)desc->width/desc->stride : 1.f;
                r->drawRegion(0,-1,-1,1,1,0,0,u,1,LorieBuffer_isRgba(buffer));
                r->drawCursor(cw,ch,0,0,r->state->cursor.x,r->state->cursor.y);
                glReadPixels(0,0,cw,ch,GL_RGBA,GL_UNSIGNED_BYTE,c.pixels);
                if(glGetError()==GL_NO_ERROR) __atomic_add_fetch(&s->readbacks,1,__ATOMIC_RELAXED);
                else { free(c.pixels);c.pixels=nullptr; }
            } else { free(c.pixels);c.pixels=nullptr; }
            glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(1,&fbo);glDeleteTextures(1,&texture);
        }
        if(c.pixels) {
            // GL is bottom-up RGBA; Android Bitmap consumes top-down ARGB integers.
            for(int y=0;y<(ch+1)/2;y++) for(int x=0;x<cw;x++) {
                int a=y*cw+x,b=(ch-1-y)*cw+x;
                uint32_t p=c.pixels[a],q=c.pixels[b];
                auto argb=[](uint32_t v){return 0xff000000u|((v&255)<<16)|(v&0xff00)|((v>>16)&255);};
                c.pixels[a]=argb(q); if(a!=b) c.pixels[b]=argb(p);
            }
        }
        c.pending=false; c.done=true;
        pthread_cond_broadcast(&r->stateChangeFinishCond);
    }
    pthread_mutex_unlock(&r->stateLock);
    bool extra=false;
    for(int i=0;i<8;i++) if(i!=s->primary && s->targets[i].surface) extra=true;
    if(!extra) return;
    if(!s->mirror) glGenTextures(1,&s->mirror);
    r->bindTexture(s->mirror);
    if(s->mirrorWidth!=w||s->mirrorHeight!=h) {
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGB,w,h,0,GL_RGB,GL_UNSIGNED_BYTE,nullptr);
        s->mirrorWidth=w; s->mirrorHeight=h;
    }
    glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,r->viewportX,surfaceH-r->viewportY-h,w,h);
}

// Root sampling/copying is finished and fenced before entering this function.
// Commit the primary buffer before changing drawables; never swap its new empty buffer later.
void lyraPresent(Renderer *r) {
    auto *s=data(r);
    if(!eglSwapBuffers(r->egl_display,r->sfc)) {
        __android_log_print(ANDROID_LOG_ERROR,"LyraX11","Primary swap failed: 0x%x",eglGetError());
        pthread_mutex_lock(&r->stateLock);s->failed=true;r->stopping=true;
        pthread_cond_broadcast(&r->stateChangeFinishCond);pthread_mutex_unlock(&r->stateLock);
        return;
    }
    int w=s->mirrorWidth,h=s->mirrorHeight;
    if(!s->mirror || w<=0 || h<=0)return;
    for(int i=0;i<8;i++) {
        auto &t=s->targets[i]; if(i==s->primary||!t.surface) continue;
        if(!eglMakeCurrent(r->egl_display,t.surface,t.surface,r->ctx)) continue;
        eglSwapInterval(r->egl_display,0);
        int tw=ANativeWindow_getWidth(t.window),th=ANativeWindow_getHeight(t.window);
        glViewport(0,0,tw,th); glClearColor(0,0,0,1); glClear(GL_COLOR_BUFFER_BIT);
        float z=fminf((float)tw/w,(float)th/h); int vw=lroundf(w*z),vh=lroundf(h*z);
        glViewport((tw-vw)/2,(th-vh)/2,vw,vh);
        r->drawRegion(s->mirror,-1,-1,1,1,0,1,1,0,0);
        eglSwapBuffers(r->egl_display,t.surface);
        __atomic_add_fetch(&s->gpuMirrors,1,__ATOMIC_RELAXED);
    }
    eglMakeCurrent(r->egl_display,r->sfc,r->sfc,r->ctx);
}

void lyraReleaseSurfaces(Renderer *r) {
    auto *s=data(r); if(!s) return;
    eglMakeCurrent(r->egl_display,r->defaultSfc,r->defaultSfc,r->ctx);
    for(auto &t:s->targets) {
        if(t.surface) eglDestroySurface(r->egl_display,t.surface);
        if(t.window) ANativeWindow_release(t.window);
        if(t.pending) ANativeWindow_release(t.pending);
        t={};
    }
    if(s->mirror) glDeleteTextures(1,&s->mirror);
    r->sfc=r->defaultSfc; r->win=nullptr;
}
