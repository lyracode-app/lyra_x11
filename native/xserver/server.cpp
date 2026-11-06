/* Native process entry. X.Org globals never enter the Android UI process's session lifetime. */
#include "server_core.inc"
#include <sys/un.h>
#include <signal.h>

char *xtrans_unix_path_x11 = nullptr;
char *xtrans_unix_dir_x11 = nullptr;
static int listener = -1;
static void acceptControl(int fd, int ready, void*) {
    if (ready & X_NOTIFY_ERROR) return;
    int connection = accept4(fd, nullptr, nullptr, SOCK_CLOEXEC);
    if (connection < 0) return;
    if (conn_fd != -1) { close(connection); return; }
    conn_fd = connection;
    InputThreadRegisterDev(connection, handleLorieEvents, nullptr);
    QueueWorkProc(+[](ClientPtr, void*) -> Bool { lorieActivityConnected(); return TRUE; }, nullptr, nullptr);
    lorieWakeServer();
}

// Called when X.Org's input dispatch is ready. Private filesystem socket only.
void lorieListenForKnocks(int, const char*) {
    const char *path = getenv("LYRA_X11_CONTROL");
    if (!path || strlen(path) >= sizeof(sockaddr_un::sun_path)) FatalError("Invalid private control socket\n");
    sockaddr_un address{}; address.sun_family = AF_UNIX;
    strcpy(address.sun_path, path);
    listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (listener < 0 || bind(listener, (sockaddr*)&address, sizeof(address)) || listen(listener, 1)) FatalError("Unable to bind private control socket: %s\n", strerror(errno));
    chmod(path, 0600);
    InputThreadRegisterDev(listener, acceptControl, nullptr);
}

bool lyraProbeHardwareBuffer();
// CPU write mappings end before GPU reads; never double-lock across processes.
void rendererTestCapabilities(int *legacy, int *relock) {
    *legacy=!lyraProbeHardwareBuffer();
    if(getenv("LYRA_X11_LEGACY")) *legacy=1;
    // Root CPU mappings are released explicitly; the renderer never double-locks them.
    *relock = 0;
}
int rendererRelockProbeChild(int) { return 1; } // Not used by Lyra's ownership protocol.

extern "C" __attribute__((visibility("default"))) int lyra_server_main(int argc, char **argv) {
    umask(0077);
    const char *tmp = getenv("TMPDIR");
    if (!tmp || tmp[0] != '/') return 2;
    asprintf(&xtrans_unix_path_x11, "%s/.X11-unix/X", tmp);
    asprintf(&xtrans_unix_dir_x11, "%s/.X11-unix/", tmp);
    defaultFontPath = (char*) "built-ins";
    xorg_list_init(&registeredBuffers);
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    XkbBaseDirectory = getenv("XKB_CONFIG_ROOT");
    return dix_main(argc,argv,environ);
}
