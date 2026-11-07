// Private, per-session Android client. Only the render thread owns EGL resources.
#include "hooks.h"
#include <android/native_window_jni.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <new>
#include <android/sharedmem.h>

struct Client {
    Renderer r;
    LyraSurfaces surfaces;
    volatile int fd=-1;
    pthread_t io=0;
    int buttons=0;
    bool alive=false;
    uint64_t received=0, buffers=0;
    pthread_mutex_t writer=PTHREAD_MUTEX_INITIALIZER;
};
static bool readAll(int fd,void *p,size_t n) {
    auto *b=(char*)p;
    while(n) { ssize_t k=read(fd,b,n); if(k<0&&errno==EINTR)continue; if(k<=0)return false; b+=k;n-=k; }
    return true;
}
static bool sendEvent(Client *c,const lorieEvent &e) {
    const char *p=(const char*)&e; size_t n=sizeof(e);
    while(n) { ssize_t k=send(c->fd,p,n,MSG_NOSIGNAL); if(k<0&&errno==EINTR)continue; if(k<=0)return false;p+=k;n-=k; }
    return true;
}
static timespec deadline(int seconds) { timespec t{};clock_gettime(CLOCK_REALTIME,&t);t.tv_sec+=seconds;return t; }
static void dispose(JNIEnv *env,Client *c) {
    if(c->fd>=0) shutdown(c->fd,SHUT_RDWR);
    if(c->io) pthread_join(c->io,nullptr);
    // The server is still alive until the owner closes this client. No stale shared mutex is touched.
    c->r.destroy();
    munmap(c->r.stateCond,sizeof(pthread_cond_t));close(c->r.stateCondFd);
    if(c->fd>=0)close(c->fd);
    if(c->r.thiz)env->DeleteGlobalRef(c->r.thiz);
    if(c->r.lorieViewClass)env->DeleteGlobalRef(c->r.lorieViewClass);
    free(c->surfaces.capture.pixels);
    pthread_mutex_destroy(&c->writer);
    pthread_mutex_destroy(&c->r.stateLock);
    pthread_cond_destroy(&c->r.stateChangeFinishCond);
    pthread_spin_destroy(&c->r.bufferLock);
    c->~Client(); free(c);
}
#define JNI(name) extern "C" JNIEXPORT name
#define METHOD(name) Java_com_yukisoffd_lyra_x11_nativebackend_NativeDisplay_##name
JNI(jlong) METHOD(nativeCreate)(JNIEnv *env,jobject self,jstring socket,jint width,jint height) {
    auto *c=new(calloc(1,sizeof(Client)))Client;
    c->surfaces.desktopWidth=width; c->surfaces.desktopHeight=height;
    c->r.lyraSurfaceState=&c->surfaces; c->r.connFdPtr=&c->fd;
    c->r.init(env,self);
    pthread_mutex_lock(&c->r.stateLock);
    auto end=deadline(10);
    while(!c->surfaces.ready&&!c->surfaces.failed) if(pthread_cond_timedwait(&c->r.stateChangeFinishCond,&c->r.stateLock,&end)==ETIMEDOUT)break;
    bool ready=c->surfaces.ready;
    pthread_mutex_unlock(&c->r.stateLock);
    if(!ready) { dispose(env,c); return 0; }
    const char *path=env->GetStringUTFChars(socket,nullptr);
    sockaddr_un address{};address.sun_family=AF_UNIX;
    if(strlen(path)>=sizeof(address.sun_path)) {env->ReleaseStringUTFChars(socket,path);dispose(env,c);return 0;}
    strcpy(address.sun_path,path);env->ReleaseStringUTFChars(socket,path);
    c->fd=::socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);
    timeval timeout{2,0}; setsockopt(c->fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    if(connect(c->fd,(sockaddr*)&address,sizeof(address))) { dispose(env,c);return 0; }
    lorieEvent wake{};wake.type=EVENT_RENDERER_WAKEUP_COND;
    if(!sendEvent(c,wake)||ancil_send_fd(c->fd,c->r.getWakeupCondFd())) {dispose(env,c);return 0;}
    __atomic_store_n(&c->alive,true,__ATOMIC_RELEASE);
    pthread_create(&c->io,nullptr,+[](void *arg)->void* {
        auto *c=(Client*)arg;lorieEvent e{};
        while(readAll(c->fd,&e,sizeof(e))) {
            __atomic_add_fetch(&c->received,1,__ATOMIC_RELAXED);
            if(e.type==EVENT_SHARED_SERVER_STATE) {
                int fd=ancil_recv_fd(c->fd);struct stat st{};
                if(fd<0)break;
                // ASharedMemory may return an ashmem character fd (st_size == 0), not a memfd.
                if(ASharedMemory_getSize(fd)<sizeof(lorie_shared_server_state)){close(fd);break;}
                auto *state=(lorie_shared_server_state*)mmap(nullptr,sizeof(lorie_shared_server_state),PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);close(fd);
                if(state==MAP_FAILED)break;
                c->r.setSharedState(state);
            } else if(e.type==EVENT_ADD_BUFFER) {
                LorieBuffer *buffer=nullptr; LorieBuffer_recvHandleFromUnixSocket(c->fd,&buffer);
                if(!buffer)break;
                c->r.addBuffer(buffer);
                __atomic_add_fetch(&c->buffers,1,__ATOMIC_RELAXED);
            } else if(e.type==EVENT_REMOVE_BUFFER) c->r.removeBuffer(e.removeBuffer.id);
            else if(e.type==EVENT_CLIPBOARD_SEND) {
                if(e.clipboardSend.count>1024*1024)break;
                char bytes[4096];size_t left=e.clipboardSend.count;
                while(left) {size_t n=left<sizeof(bytes)?left:sizeof(bytes);if(!readAll(c->fd,bytes,n))goto done;left-=n;}
            }
        }
        done: __atomic_store_n(&c->alive,false,__ATOMIC_RELEASE);
        return nullptr;
    },c);
    return (jlong)c;
}
JNI(void) METHOD(nativeClose)(JNIEnv *env,jobject,jlong ptr) { if(ptr)dispose(env,(Client*)ptr); }
JNI(void) METHOD(nativeSurface)(JNIEnv *env,jobject,jlong ptr,jlong id,jobject surface) {
    auto *c=(Client*)ptr;if(!c)return;
    auto *window=surface?ANativeWindow_fromSurface(env,surface):nullptr;
    auto &r=c->r;pthread_mutex_lock(&r.stateLock);
    LyraTarget *target=nullptr;
    for(auto &t:c->surfaces.targets)if(t.id==id){target=&t;break;}
    if(!target&&window)for(auto &t:c->surfaces.targets)if(!t.id){target=&t;break;}
    if(target) {
        if(target->pending)ANativeWindow_release(target->pending);
        target->id=id;target->pending=window;target->changed=true;c->surfaces.dirty=true;
        pthread_cond_signal(r.stateCond);
        // Android's Surface destruction callback must finish after EGL releases that Surface.
        if(!surface) {auto end=deadline(2);while(target->changed&&!r.stopping)if(pthread_cond_timedwait(&r.stateChangeFinishCond,&r.stateLock,&end)==ETIMEDOUT)break;}
    } else if(window)ANativeWindow_release(window);
    pthread_mutex_unlock(&r.stateLock);
}
JNI(jboolean) METHOD(nativeAlive)(JNIEnv*,jobject,jlong ptr) {
    auto *c=(Client*)ptr;if(!c)return false;
    pthread_mutex_lock(&c->r.stateLock);
    bool alive=__atomic_load_n(&c->alive,__ATOMIC_ACQUIRE)&&!c->surfaces.failed;
    pthread_mutex_unlock(&c->r.stateLock);return alive;
}
JNI(void) METHOD(nativeFrame)(JNIEnv*,jobject,jlong ptr) {
    auto *c=(Client*)ptr;if(!c)return;lorieEvent e{};e.type=EVENT_FRAME;
    pthread_mutex_lock(&c->writer);sendEvent(c,e);pthread_mutex_unlock(&c->writer);
}
JNI(void) METHOD(nativeKey)(JNIEnv*,jobject,jlong ptr,jint sym,jboolean down) {
    auto *c=(Client*)ptr;if(!c)return;lorieEvent e{};e.type=EVENT_KEYSYM;e.keysym.sym=sym;e.keysym.down=down;
    pthread_mutex_lock(&c->writer);sendEvent(c,e);pthread_mutex_unlock(&c->writer);
}
JNI(void) METHOD(nativeScanCode)(JNIEnv*, jobject, jlong ptr, jint code, jboolean down) {
    auto *client = (Client*)ptr;
    if (!client || code < 1 || code > 247) return;
    lorieEvent event{};
    event.type = EVENT_KEY;
    event.key.key = code + 8; // XKB's evdev map reserves the first eight keycodes.
    event.key.state = down;
    pthread_mutex_lock(&client->writer);
    sendEvent(client, event);
    pthread_mutex_unlock(&client->writer);
}
JNI(void) METHOD(nativePointer)(JNIEnv*,jobject,jlong ptr,jint x,jint y,jint buttons) {
    auto *c=(Client*)ptr;if(!c)return;pthread_mutex_lock(&c->writer);
    lorieEvent e{};e.type=EVENT_MOUSE;e.mouse.x=x;e.mouse.y=y;sendEvent(c,e);
    for(int i=0;i<3;i++)if((c->buttons^buttons)&(1<<i)) {
        e={};e.type=EVENT_MOUSE;e.mouse.detail=i+1;e.mouse.down=(buttons>>i)&1;sendEvent(c,e);
    }
    if(buttons&0x78) {
        e={};e.type=EVENT_MOUSE;e.mouse.detail=4;
        e.mouse.x=(buttons&32)?-120:((buttons&64)?120:0);
        e.mouse.y=(buttons&8)?-120:((buttons&16)?120:0);sendEvent(c,e);
    }
    c->buttons=buttons;pthread_mutex_unlock(&c->writer);
}
JNI(void) METHOD(nativeScroll)(JNIEnv*, jobject, jlong ptr, jint x, jint y, jfloat horizontal, jfloat vertical) {
    auto *client = (Client*)ptr;
    if (!client) return;
    pthread_mutex_lock(&client->writer);
    lorieEvent event{};
    event.type = EVENT_MOUSE;
    event.mouse.x = x;
    event.mouse.y = y;
    sendEvent(client, event);
    event.mouse.detail = 4; // Lorie scroll packets use 120 units per wheel step.
    event.mouse.x = horizontal * 120;
    event.mouse.y = -vertical * 120;
    sendEvent(client, event);
    pthread_mutex_unlock(&client->writer);
}
JNI(jlongArray) METHOD(nativeStats)(JNIEnv *env,jobject,jlong ptr) {
    auto *c=(Client*)ptr; jlong v[12]{};
    if(c){auto &s=c->surfaces;v[0]=__atomic_load_n(&s.frames,__ATOMIC_RELAXED);v[1]=__atomic_load_n(&s.ahbFrames,__ATOMIC_RELAXED);v[2]=__atomic_load_n(&s.uploadFrames,__ATOMIC_RELAXED);v[3]=__atomic_load_n(&s.gpuMirrors,__ATOMIC_RELAXED);v[4]=__atomic_load_n(&s.readbacks,__ATOMIC_RELAXED);
        pthread_mutex_lock(&c->r.stateLock);for(auto &t:s.targets)if(t.window)v[5]++;v[6]=s.failed;
        v[7]=__atomic_load_n(&c->received,__ATOMIC_RELAXED);v[8]=__atomic_load_n(&c->buffers,__ATOMIC_RELAXED);
        if(!c->r.stopping&&!s.failed&&c->r.state){v[9]=c->r.state->surfaceAvailable;v[10]=c->r.state->drawRequested;v[11]=c->r.state->waitForNextFrame;}
        pthread_mutex_unlock(&c->r.stateLock);}
    auto out=env->NewLongArray(12);env->SetLongArrayRegion(out,0,12,v);return out;
}
JNI(jintArray) METHOD(nativeCapture)(JNIEnv *env,jobject,jlong ptr) {
    auto *c=(Client*)ptr;if(!c)return nullptr;auto &r=c->r;auto &cap=c->surfaces.capture;
    pthread_mutex_lock(&r.stateLock);
    if(c->surfaces.primary<0||cap.pending){pthread_mutex_unlock(&r.stateLock);return nullptr;}
    free(cap.pixels);cap={};cap.pending=true;pthread_cond_signal(r.stateCond);auto end=deadline(3);
    while(!cap.done&&!r.stopping)if(pthread_cond_timedwait(&r.stateChangeFinishCond,&r.stateLock,&end)==ETIMEDOUT)break;
    jintArray result=nullptr;
    if(cap.done&&cap.pixels){int n=cap.width*cap.height;result=env->NewIntArray(n+2);jint size[2]={cap.width,cap.height};env->SetIntArrayRegion(result,0,2,size);env->SetIntArrayRegion(result,2,n,(jint*)cap.pixels);}
    cap.pending=false;free(cap.pixels);cap={};pthread_mutex_unlock(&r.stateLock);return result;
}
