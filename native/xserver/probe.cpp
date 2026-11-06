// Match the real root allocation usage, verify actual GPU pixels, then choose AHB or FD.
#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <cstdio>

bool lyraProbeHardwareBuffer() {
    EGLDisplay display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLContext context=EGL_NO_CONTEXT;EGLSurface surface=EGL_NO_SURFACE;EGLImageKHR image=EGL_NO_IMAGE_KHR;
    AHardwareBuffer *buffer=nullptr;GLuint texture=0,fbo=0;bool good=false,current=false;
    const char *stage="egl";uint32_t actual=0;GLenum framebuffer=0,error=0;
    do {
        EGLint major,minor,count;EGLConfig config;
        if(!eglInitialize(display,&major,&minor))break;
        EGLint attributes[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
        if(!eglChooseConfig(display,attributes,&config,1,&count)||count!=1)break;
        EGLint ca[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE},pa[]={EGL_WIDTH,64,EGL_HEIGHT,64,EGL_NONE};
        context=eglCreateContext(display,config,EGL_NO_CONTEXT,ca);surface=eglCreatePbufferSurface(display,config,pa);
        if(!context||!surface||!eglMakeCurrent(display,surface,surface,context))break;
        current=true;
        stage="allocation";
        AHardwareBuffer_Desc desc{};desc.width=64;desc.height=64;desc.layers=1;desc.format=AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
        desc.usage=AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN|AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN|AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE|AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
        if(AHardwareBuffer_allocate(&desc,&buffer))break;
        uint32_t *pixels=nullptr;
        if(AHardwareBuffer_lock(buffer,AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,-1,nullptr,(void**)&pixels))break;
        pixels[0]=0xffd38743u;
        if(AHardwareBuffer_unlock(buffer,nullptr))break;
        stage="image_import";
        EGLint ia[]={EGL_IMAGE_PRESERVED_KHR,EGL_TRUE,EGL_NONE};
        image=eglCreateImageKHR(display,EGL_NO_CONTEXT,EGL_NATIVE_BUFFER_ANDROID,eglGetNativeClientBufferANDROID(buffer),ia);
        if(!image)break;
        glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);glEGLImageTargetTexture2DOES(GL_TEXTURE_2D,image);
        glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
        stage="framebuffer";framebuffer=glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if(framebuffer!=GL_FRAMEBUFFER_COMPLETE)break;
        stage="pixels";glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,&actual);error=glGetError();
        good=error==GL_NO_ERROR && (actual&0xffffffu)==0xd38743u;
    } while(false);
    if(current){
        fprintf(stderr,"LYRA_DISPLAY: EGL renderer=%s vendor=%s\n",(const char*)glGetString(GL_RENDERER),(const char*)glGetString(GL_VENDOR));
        glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(1,&fbo);glDeleteTextures(1,&texture);
    }
    if(image)eglDestroyImageKHR(display,image);
    if(buffer)AHardwareBuffer_release(buffer);
    eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    if(surface)eglDestroySurface(display,surface);
    if(context)eglDestroyContext(display,context);
    // The server's driver connection stays initialized for AHardwareBuffer cache operations.
    fprintf(stderr,"LYRA_DISPLAY: AHardwareBuffer GPU pixel probe %s\n",good?"passed":"failed; using native shared-memory upload");
    if(!good)fprintf(stderr,"LYRA_DISPLAY: probe stage=%s framebuffer=0x%x gl_error=0x%x actual_rgb=0x%06x\n",stage,framebuffer,error,actual&0xffffffu);
    return good;
}
