# Lyra Linux GUI 工具规范 v4

此规范适用于 Lyra 应用内的 `linux_gui` 和 `proot_command`，供 AI 配置、诊断 Linux 图形环境及展示应用。

## 必须遵守的会话契约

- 首次安装、修复或启动 GUI 前调用 `linux_gui(action="guide")`，用 `list` 获取环境与会话，明确 `linux_id`。不同 rootfs 必须分别检查、安装，不能将一个环境的软件、DISPLAY 或凭据用于另一个环境。
- 使用 `proot_command` 读取 `/etc/os-release`，分别检查 `xdpyinfo`、`dbus-run-session` 及拟用桌面/窗口管理器。原生后端的 X server、XKB 数据和 cookie 由 APK 提供，Linux 不需要安装 X server。只补齐缺失依赖，不要一次安装多个桌面。
- 默认 `display_backend="auto"` 优先使用内嵌 X.Org/Lorie DDX；`native` 强制原生；`vnc` 选择 TigerVNC 备用。自动回退仅发生在执行桌面/应用命令之前，避免重复执行用户命令。以 `status.display_backend.backend` 为实际后端。
- 原生后端在 Android 独立进程运行。每个环境共享一个 `/tmp/.X11-unix` 目录挂载，每个会话使用独立 DISPLAY、Xauthority 和私有控制通道。不开放 X11 TCP，不需要 VNC 密码。X11 客户端必须通过 cookie 认证；不要使用 `-ac`、`xhost +` 或修改认证代码。
- 只有需要 VNC 备用时才检查/安装 `Xtigervnc` 或 `Xvnc` 以及 `xauth`。备用后端禁用 RFB TCP，使用权限 0600 的应用私有 Unix socket，无 VNC 密码。不要安装 TightVNC、x11vnc、Vino、中继或显示管理器。
- 使用 `linux_gui(action="start")` 创建会话。不要手工启动替代 X server、重用旧 display/cookie/socket，或把另一个环境的凭据用于当前环境。
- Lyra 自动启动 session D-Bus，设置 DISPLAY、XAUTHORITY、XDG_RUNTIME_DIR。不要启动 systemd、显示管理器、system D-Bus，或使用 `-ac` / `xhost +` 来绕过 X11 认证。

## 最小安装方案

先确认发行版和已有软件。以下命令仅在缺少对应组件时执行；使用非交互参数，安装完成后再次 `command -v` 验证，检查实际退出码及日志。

### Debian / Ubuntu（推荐 LXDE）

共同依赖：

    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends x11-utils dbus-x11 fonts-dejavu-core

只选择一种用途：

- LXDE 桌面：`apt-get install -y --no-install-recommends lxde-core lxterminal`，预设 `lxde`（`startlxde`）。
- 应用预览：`apt-get install -y --no-install-recommends openbox`，传入 `app_command`。应用自己的 GTK/Qt/Python 等依赖按项目要求安装。
- 用户明确选用 Xfce：`apt-get install -y --no-install-recommends xfce4`，预设 `xfce`（`startxfce4`）。
- 用户明确选用 LXQt：安装发行版的 `lxqt-core`，预设 `lxqt`（`startlxqt`）；先检查当前发行版是否提供该包。
- 仅窗口管理器：`openbox`，预设 `openbox`（`openbox-session`）。

### Alpine（启用与当前版本相同的 community 仓库）

    apk add xdpyinfo dbus font-dejavu openbox

默认使用 `openbox` 预设或 `app_command`。需要完整桌面时，按用户选择再安装已确认可用的 `xfce4` 等包，不要混入其他 Alpine 版本或 edge 仓库。

### Arch / Arch ARM

    pacman -S --needed --noconfirm xorg-xdpyinfo dbus ttf-dejavu openbox

先使用 `openbox` 预设或应用预览。不要为了 GUI 做部分系统升级；若仓库/系统版本不一致，先报告包管理器的实际错误。

### Fedora 43+

    dnf install -y xdpyinfo dbus-x11 dejavu-sans-fonts openbox

先使用 `openbox` 预设或应用预览。较旧 Fedora 的 TigerVNC 包名可能不同，应核对发行版软件源后再安装。

### 其他发行版

根据本发行版官方包资料找到提供上述命令的包。只安装 xdpyinfo、session D-Bus、字体及所选桌面/窗口管理器。不要把 Debian 包名或安装脚本直接用于其他发行版。

VNC 备用依赖按需安装：Debian/Ubuntu `tigervnc-standalone-server xauth`；Alpine `tigervnc xauth`；Arch `tigervnc xorg-xauth`；Fedora 按发行版确认 `tigervnc-x11-server xorg-x11-xauth`。原生后端启动正常时无需这些软件。

## 原生显示管线与诊断

- 原生 DDX 通过私有 Unix 控制 socket 传递共享缓冲区句柄，Android 使用 AHardwareBuffer/EGLImage 和 SurfaceView 显示，没有 RFB 编码、解码、全帧 Bitmap 上传。Linux 应用和窗口管理器仍使用标准 X11 协议。
- `display_backend.transport=ahardwarebuffer_eglimage` 且 `ahb_frames` 增长才表示实际走了 AHardwareBuffer。`shared_memory_texture_upload` 表示共享内存纹理上传兼容路径，不能描述为零拷贝。`vnc_encoding=true` 表示正在使用 VNC 备用。
- 同一会话可同时显示在主页面和悬浮窗，第二个 Surface 使用 GPU 镜像。不同会话有独立服务器、输入和缓冲区。隐藏所有 Surface 后停止画面呈现，Linux 应用、音频保留运行。
- `screenshot_readbacks` 只应在主动截图时增长；普通显示不应 CPU 读回。原生截图需要先显示会话，按完整桌面分辨率离屏合成并按需 GPU 读回；悬浮窗大小不影响截图分辨率，截图不参与持续传输。
- 原生 X11 减少桌面传输开销，不代表所有 guest 绘制都使用 GPU。普通 X11 2D 绘制仍可能由 CPU 执行；通用发行版 Mesa virpipe 也可能有应用级读回。现阶段不承诺任意 Linux Vulkan、DRI3 驱动或视频硬解。
- 若自动回退，先看 `fallback_reason` 和会话日志，再按需补齐 TigerVNC 备用依赖。原生运行中断开会明确报错，不会悄悄重跑应用命令。也可新建 `display_backend="vnc"` 会话排查。

## 启动与便携预览

- 桌面示例：`linux_gui(action="start", linux_id="debian", preset="lxde", mode="fullscreen", width=1920, height=1080, fps=30)`。
- GUI 开发预览示例：`linux_gui(action="start", linux_id="debian", app_command="cd /workspace/demo && python3 main.py", mode="overlay")`。Lyra 自动在同一 session D-Bus 内启动 Openbox 和应用，原有桌面会话不受影响。
- `app_command` 可以是应用的长期运行命令，允许交互；不通过命令行工具的后台模式启动。不要使用无头浏览器或截图命令代替可交互 GUI。
- 悬浮窗需要 Android“显示在其他应用上层”权限。先查看 `guide` / `list` 的 `overlay_permission`；缺失时可调用 `request_overlay_permission` 打开系统授权页，用户授权后再调用 `start` / `show`。不能假设已授权。
- `start` 返回 session_id、状态和实际 DISPLAY/XAUTHORITY。`starting` 表示仍在启动，必须检查 `status` 至 `connected`，并确认 `has_frame=true` 后才能报告画面已就绪。
- 同一会话里的多个 X11 窗口由 Linux 窗口管理器处理。多个环境/会话可同时运行；`show` 选择需要展示的会话。不要把关闭悬浮显示与停止会话混淆：`hide` 保留后台会话，`stop` 停止其 GUI 与对应 PRoot supervisor。
- `screenshot` 直接保存当前 Linux 帧缓冲为 PNG，不启动额外截图软件，不包含 Android 工具栏。用户也可以使用悬浮窗或全屏工具栏的截图按钮。图像保存位置由返回值给出。
- 对当前 desktop 会话启动额外程序，可在同一 `linux_id` 的 `proot_command` 使用 `status` 返回的 export 命令；不要把会话 cookie 内容粘贴到对话里。

## 排错顺序

1. `linux_gui(action="status", session_id=...)` 获取有界日志和状态。
2. 先处理缺失命令、桌面命令不存在、Xvnc 版本/选项不兼容、display 占用、应用启动异常；禁止先改认证或重装整个桌面。
3. 私有通道若报告 VNC 密码认证，说明连接/会话不是此版本管理的正常通道；停止旧会话并重新创建，不修改 VNC 二进制或放宽系统配置。
4. “Could not find any render nodes / Failed to initialize DRI3”本身不表示 RFB 连接失败，也不表示整个桌面不可用。以实际 `has_frame`、应用日志与画面为准。
5. 1080p/30 FPS 可以作为用户已验证设备的设置；其他设备从 720p/30 FPS 开始。Android 画面合成不代表 Linux OpenGL/Vulkan 已获得 GPU 加速。按下述媒体诊断确认实际后端。

## 音频输出

- `start` 支持 `audio="auto" | "pulseaudio" | "pipewire" | "off"`。省略时使用该环境的记忆设置；首次默认 auto，优先 PulseAudio，失败后尝试完整 PipeWire 栈。不要同时安装两套栈来试错，优先复用已有工具。
- Lyra 为每个会话创建独立音频服务及私有 Unix socket/FIFO。Linux 服务混音为 48 kHz、双声道 S16LE，Android AudioTrack 播放并交给 Android 路由至扬声器、蓝牙或 USB。无需音频密码、TCP 端口、systemd、Linux ALSA 声卡、root 或录音权限。
- Debian/Ubuntu 推荐只补齐 `pulseaudio pulseaudio-utils`。ALSA 应用另需 `libasound2-plugins`。示例：`apt-get install -y --no-install-recommends pulseaudio pulseaudio-utils libasound2-plugins`。先查询包是否存在并检查已有服务；不修改全局 PulseAudio/ALSA 配置，不启动系统服务。
- 用户已使用 PipeWire 时，检查 `pipewire`、`pipewire-pulse`、`wireplumber`（0.5+）及 `libpipewire-module-pipe-tunnel`、SPA audioconvert/support 模块。Debian/Ubuntu 对应 `pipewire pipewire-pulse wireplumber libspa-0.2-modules libpipewire-0.3-modules`，先核对当前源是否提供。Lyra 启动独立配置，关闭硬件枚举，不要求 systemd。旧 WirePlumber 0.4 环境优先选择 PulseAudio。
- Alpine/Arch/Fedora 按本发行版官方包查找同名命令、pipe sink/tunnel 和 ALSA pulse 插件；PipeWire 的 Pulse 兼容包在 Fedora 名为 `pipewire-pulseaudio`，不可照搬 Debian 命令。
- `status.media.audio_backend` 应为 pulseaudio 或 pipewire；`audio_output` 的 received_frames/written_frames 在播放时增长，output=playing 只表示已写入 Android 音频设备。不能凭服务进程存在便声称用户已听到声音。静音、系统音量、蓝牙路由及音频焦点仍需检查。
- 使用 `linux_gui(action="audio", session_id=..., muted=true/false)` 或工具栏/悬浮窗按钮静音、恢复。切到其他应用不暂停声音；失去 Android 音频焦点时停止输出并丢弃旧积压，恢复后播放当前声音。长期失焦后可点“恢复声音”重新获取焦点。
- 会话中的新应用继承音频变量。同一环境的 `proot_command` / 新终端必须先执行 `status.environment_exports`，它会 source `/run/lyra/<会话>/environment`。不要手写或沿用另一会话的 PULSE_SERVER/PIPEWIRE_REMOTE。升级前已运行的旧终端需重开一次，以获得新的 `/run/lyra` 稳定挂载。
- 音频启动失败不会破坏当前可工作的图形桌面。先看 `status.media.guest_audio_log` 和 audio_output.error，再补齐指定组件并重建会话；不要安装远程音频服务器或开放 `auth-anonymous` TCP。匿名认证仅用于应用私有 Unix socket。

## GPU 加速

- `start` 支持 `graphics="auto" | "virgl" | "software"`，首次默认 auto。应用打包独立的 Android EGL/GLES VirGL 服务（ARM64、x86_64），每会话一个进程和私有 vtest socket。Mali/Immortalis、Adreno 和 x86 GPU 均先检测 Android vendor EGL，不能仅由 CPU/手机型号推断兼容性。
- Dimensity 9400/Immortalis 使用该 EGL/GLES 路径。不要安装面向 Adreno 的 Turnip，不写内核驱动、不访问受限 `/dev/dri`/`/dev/kgsl`，不修改 SELinux。
- guest 必须有启用 VirGL/virpipe 的 Mesa 和 `glxinfo`、`timeout`。Debian/Ubuntu 推荐补齐 `libgl1-mesa-dri libglx-mesa0 mesa-utils coreutils`；Mesa 依赖版本必须来自同一发行版软件源。其他发行版查找 Mesa Gallium/GLX、mesa-utils/mesa-demos 与 coreutils。
- Lyra 先运行隔离的 GLES 3 能力探测，再让 guest 使用 `GALLIUM_DRIVER=virpipe LIBGL_ALWAYS_SOFTWARE=1 VTEST_SOCKET_NAME=...` 运行有超时的 `glxinfo -B`。这里 SOFTWARE=1 选择 Mesa 的软件加载入口，真正执行的是远端 virpipe；不能仅凭此变量判断是否为 CPU 渲染。
- 只有 `status.media.graphics=virgl` 且 guest_graphics_log 的 OpenGL renderer string 包含 virgl 才表示验证通过。host_gpu 展示 Android 实际 renderer/vendor/version。AUTO 失败后去掉 GPU 变量、回退现有软件显示并保留原因；强制 virgl 验证失败会明确报错。不要伪造 `MESA_GL_VERSION_OVERRIDE` / GLES 扩展来使检测“成功”。
- 成功后应用程序继承这些变量；额外命令仍需 source `environment_exports`。每个环境的 Mesa 单独适配。发行版 Mesa 未编译 virpipe 时，报告实际错误并选择 software，不能把安装任意 Mesa 包说成已加速；需要使用该发行版可维护的启用 virgl/vtest 的 Mesa 构建。
- 这条路径加速兼容的 Linux OpenGL 调用，与显示后端分别选择。原生 X11 通过共享 AHardwareBuffer 消除了 RFB 桌面传输；VNC 备用仍需编码/解码。guest OpenGL 的 virpipe 路径仍可能需要应用级读回。帧率设置是上限，实际性能以设备和应用测量为准。
