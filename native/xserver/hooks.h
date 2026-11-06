#include <stdlib.h>
#pragma once
#include <unistd.h>
#include "../lorie/lorie.h"

// Render-thread hooks; all target mutations are serialized with Renderer::stateLock.
struct LyraTarget { long id; ANativeWindow *pending; ANativeWindow *window; EGLSurface surface; int width,height; bool changed; };
struct LyraCapture { uint32_t *pixels; int width,height; bool pending,done; };
struct LyraSurfaces {
    LyraTarget targets[8]{};
    bool dirty = false;
    bool failed = false, ready = false;
    int primary = -1;
    int desktopWidth = 0, desktopHeight = 0;
    uint64_t frames = 0, ahbFrames = 0, uploadFrames = 0, gpuMirrors = 0;
    uint64_t readbacks = 0;
    GLuint mirror = 0;
    int mirrorWidth = 0, mirrorHeight = 0;
    LyraCapture capture{};
};
bool lyraPending(Renderer *r);
void lyraApplySurfaces(Renderer *r);
void lyraMirror(Renderer *r, LorieBuffer *buffer);
void lyraPresent(Renderer *r);
void lyraReleaseSurfaces(Renderer *r);
void lyraCaptureFrame(Renderer *r);
