# Lyra X11

[English](README.md) · **简体中文**

Lyra X11 是一个独立的 Android library（`:lyra_x11`），把 Linux X11 桌面搬进应用：桌面画面本身，以及 PRoot 内 X11 程序的键盘、鼠标、触控与输入法交互。

默认后端是运行在独立 Android 进程中的内置 **X.Org/Lorie DDX**。根窗口像素以 **AHardwareBuffer → EGLImage → SurfaceView** 的形式直达界面，常规显示路径不做压缩、不做 RFB 解码，也不读回 CPU。guest 通过标准 X11 socket 与 Xauthority 连接，PRoot 只提供通用目录绑定。私有 **TigerVNC** 管线作为可选备用后端保留。

```text
Linux X11 应用 → /tmp/.X11-unix/X100 → 原生 X server（Android 进程）
                                     ↓ 共享 AHardwareBuffer + fence
                                 EGLImage → SurfaceView
                                          ↘ GPU 镜像 → 悬浮窗
备用：Linux TigerVNC → 私有 Unix RFB → Android Bitmap
```

`display_backend="auto"` 在执行桌面命令之前启动原生服务器，只有失败时才切到 VNC；`native` 直接返回自身错误，`vnc` 显式使用旧管线。该选择与分辨率、帧率和最近命令一起按 Linux 环境记忆。CPU 读回只在主动截图时打开。每个会话拥有自己的服务器和最多 8 个 Surface，当前界面使用全屏页面与悬浮窗。

## 特性

- 应用窗口内的原生 X11 桌面，不需要 Termux 应用、root 或后台守护进程。
- 跨环境、跨 display 最多 4 个会话，运行中随时切换。
- 触控、鼠标、滚轮、物理键盘与输入法，键盘布局由 Linux 的 XKB 处理。
- 会话音频经 PulseAudio 或 PipeWire 输出到 Android `AudioTrack`。
- 可选的 VirGL、Venus、Turnip 与 ANGLE 后端，软件渲染作为兼容性基线。
- AI 工具 `linux_gui` 负责引导安装、应用预览、截图与悬浮窗。

## 开始使用

1. 在应用的 PRoot Linux 设置中，点环境旁边的桌面图标。
2. 让 AI 调用 `linux_gui(action="guide")`，按统一的[安装与排错规范](src/main/assets/lyra_x11/GUI_TOOL_SPEC.md)检查该环境，再用 `proot_command` 安装最小依赖。Debian/Ubuntu 的 LXDE 方案例如：

   ```sh
   apt update
   apt install --no-install-recommends x11-utils dbus-x11 fonts-dejavu-core lxde-core lxterminal
   ```

3. 在“预设 / 最近使用”中选择 LXDE、Xfce、LXQt、Openbox 或历史命令，也可以自定义。每个环境独立记忆上次命令、分辨率、帧率和最近 8 条命令。连接成功后工具栏自动收起，右上角小按钮可重新展开。
4. 从同一环境的其他终端启动应用时，复制“会话日志”顶部的 `DISPLAY` 与 `XAUTHORITY` 两行，再启动应用。

Android 管理原生 X server，guest 脚本运行在 `dbus-run-session` 下。依赖 systemd、显示管理器、system bus 或登录管理器的桌面需要在 rootfs 内自行准备，本模块不会安装或修改导入的发行版软件。

## 会话模型

一个 Linux 会话对应一个 Android 桌面 Surface，会话内的窗口管理器负责排列它的 Linux 窗口。不同环境、同一环境的不同 display 都是会话列表中的独立会话，可随时切换。容量为 4 个并发会话、合并桌面面积 16,588,800 像素、单个桌面最大 3840×2160。

全屏页面自动横屏并隐藏系统栏。工具栏以浮层显示，展开时不挤压画面。轻点为左键，长按为右键，拖动为左键拖拽，双指滚动。支持带滚轮的 USB/蓝牙鼠标、物理键盘与修饰键。桌面区域隐藏 Android 系统指针，按帧缓冲比例绘制 Linux 游标。输入法在确认候选词后发送 Unicode keysym，并处理退格与回车。焦点丢失或切换会话时释放按键与鼠标按钮。少数系统按键与快捷键由 Android 保留。Linux 侧装好覆盖对应文字的字体后，文字即可正常显示。

返回后桌面继续运行，前台服务通知提供返回入口与“停止全部桌面”。关闭单个会话会终止其 PRoot supervisor 及 tracees。禁用或删除环境会先关闭其图形会话。应用进程被系统终止后不再恢复图形会话。若系统残留旧的 X server，启动日志会报告 display 占用，在对应 Linux 环境清理或重启应用运行环境即可。

## AI 应用预览与悬浮窗口

`linux_gui` 提供 `guide`、`list`、`start`、`status`、`show`、`hide`、`stop`、`screenshot` 和 `request_overlay_permission`。工具定义、路由适配、规范与界面都在本模块维护，自定义启动命令沿用应用现有的 PRoot 命令审批规则。

```json
{"action":"start","linux_id":"debian","app_command":"cd /workspace/demo && python3 main.py","mode":"overlay","width":1024,"height":768,"fps":30}
```

应用预览会自动启动 Openbox 与 session D-Bus，启动后即可交互，用户记忆的桌面命令保持不变。Openbox 与项目所需的 GUI 库需要提前安装。悬浮模式使用 Android 的“显示在其他应用上层”权限；缺少权限时工具会打开系统授权页，授权后重试即可。

悬浮窗可拖动标题栏、拖动右下角调整大小、最小化、弹出输入法、全屏打开或关闭会话。`hide` 只隐藏浮窗，`stop` 终止所选会话。

后台 AI 发起悬浮预览时，先显示真实预览窗口，再启动其前台服务，从而满足 Android 15+ 的可见悬浮窗条件；两步中任一失败都会返回错误并停止本次新会话。[Android 前台服务限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

截图按钮和 `screenshot` 直接保存 Linux 帧缓冲，无需在 Linux 内安装截图工具，画面里也不会出现 Android 工具栏。Android 10+ 保存到 `Pictures/LyraCode`，旧版本保存到应用的外部图片目录，工具返回实际 URI 或路径。

## 模块边界

| 位置 | 职责 |
| --- | --- |
| `src/main/.../GuiRuntime.kt` | 宿主接口：环境清单、启动进程、环境删除通知 |
| `src/main/.../GuestBootstrap.kt` | 独立 POSIX guest 启动协议、Unix socket、依赖检查、Xauth、D-Bus |
| `src/main/.../GuiSessions.kt` | 多环境/多 display 生命周期、认证、容量限制、日志 |
| `src/main/.../rfb/` | 可在 JVM 测试的 RFB、VNC 认证、像素与 ZRLE 解码 |
| `src/main/.../X11DisplayView.kt` | 局部位图上传、Android 画面合成、触控、物理键鼠、IME |
| `src/main/.../X11Activity.kt` | 容器无关的会话界面 |
| `src/main/.../X11OverlayWindow.kt` | 服务管理的可拖动/缩放悬浮 GUI |
| `src/main/assets/lyra_x11/GUI_TOOL_SPEC.md` | AI 安装与排错规范的唯一来源 |
| `host/java/` | 唯一的 PRoot 宿主适配层，作为 `:app` 源码编译以使用其 internal API |
| `src/test/` | 协议、压缩、输入消息、认证、隔离及关闭竞态测试 |

模块代码、资源、测试与宿主适配代码都保存在此目录。根工程注册该项目，app 声明依赖、宿主源码目录、Activity、设置入口和 AI 工具路由。PRoot 负责可选的目录绑定，以及启动前撤销禁用环境的生命周期接口；图形协议与驱动实现全部集中在本模块，与显示界面同处一处。

## 传输、输入与性能

- 支持 RFB 3.3/3.7/3.8，协商 ZRLE、CopyRect、Raw，以及 DesktopSize、Cursor、LastRect 扩展。
- 每连接复用 zlib 状态。ZRLE 以 16 KiB 缓冲流式解码并按整块填充纯色区域，无需分配整屏解压副本；CopyRect 支持重叠区域。
- 帧缓冲按脏矩形更新，Android View 使用硬件加速 Canvas 合成。帧内更新合并成一次界面重绘，网络读取与解压都在主线程之外完成。
- 每连接只保留一个增量更新请求。非当前会话最多约 2 次/秒请求更新，当前会话由自身的帧率上限控制。15、30、60、90、120 FPS 档位同时适用于两个后端，90/120 FPS 会向 Android 请求对应刷新率。实际帧率取决于屏幕能力与负载。
- 指针移动合并，按键与按钮边沿按序传输。有界输入队列溢出时关闭连接，按钮释放事件因此不会被静默丢弃。桌面有焦点时，实体键盘优先转发到 Linux；原生路径使用扫描码并交给 XKB 处理布局，屏幕键盘继续通过 Android 输入法提交文本。
- 解码尺寸、矩形、压缩长度、解压量、游标和字符串长度都有上限。停止会话即释放帧缓冲，错误日志上限 16 KiB。

RFB 需要 guest 像素编码、host 解码与位图上传，每帧多一次拷贝。它适合通用 2D 桌面；视频、游戏、CAD 与 4K/60 场景请使用原生后端。用户反馈在设备上运行 LXDE、1920×1080/30 FPS 表现流畅。

## 音频输出

每个会话独立运行 guest PulseAudio，或 PipeWire + pipewire-pulse + WirePlumber 0.5+。标准 pipe sink/tunnel 把 48 kHz 双声道 S16LE 写入私有 FIFO，再由 Android `AudioTrack` 播放。整条链路没有音频 TCP 监听、用户密码、systemd 单元或录音权限，ALSA 程序使用会话生成的 pulse 插件配置，全局配置保持不变。

`audio=auto` 优先复用已有 PulseAudio，再尝试完整 PipeWire 栈；也可以显式选择 `pulseaudio`、`pipewire` 或 `off`，结果按环境记忆。Android 输出队列上限 80 ms，过载时丢弃最旧的整帧。写入不阻塞界面，音频焦点、静音、多会话混音与 `AudioTrack` 服务重建都已处理。扬声器/蓝牙/USB 路由由 Android 决定，手机媒体音量控制输出电平。端到端延迟还包含 guest 服务、内核 FIFO 与输出设备本身。

工具栏、悬浮窗和 `linux_gui(action=audio, session_id=..., muted=...)` 都能控制静音。`status.media` 返回所选后端、收到/写入/丢弃帧数、队列深度、underrun、音频焦点和错误。安装命令与诊断流程见[工具规范](src/main/assets/lyra_x11/GUI_TOOL_SPEC.md)。

## Linux GPU 加速

模块已内置 ARM64/x86_64 的 virglrenderer 1.3.0 + libepoxy 1.5.10 Android 服务，使用 Android vendor EGL/GLES，无需 Termux 安装、root 或直接访问 GPU 设备节点。源码归档、采用的 Termux Android 补丁、许可证、16 KiB 对齐的可执行文件和重建脚本都在本模块内，详见 [native/README.md](native/README.md)。

`graphics=auto` 先在独立进程探测 Android GLES 3 与 renderer，再验证容器 Mesa virpipe：guest 优先做 EGL 像素读回，必要时改用 `glxinfo -B` 或 `glxgears -info`。`graphics=virgl` 要求验证通过，`software` 跳过宿主 GPU 服务。

Android 设备的系统版本、CPU/GPU 厂商与驱动栈各不相同，GL 与 Vulkan 能力和驱动成熟度差异很大。**软件渲染是兼容性最好的选择**，也是未知设备上的稳妥默认值：`graphics=auto` 在验证不通过时回退到软件渲染，`graphics=software` 完全不启动宿主 GPU 服务。GPU 加速在驱动配合的设备上收益明显，下面各模式分别面向一个硬件家族。

- **Mali / Immortalis（天玑）**：`graphics=venus` 是实验路径，容器 Mesa Venus 经私有 vtest socket 使用 Android 系统 Vulkan，桌面 OpenGL 优先尝试 Zink。Debian 13 安装 `mesa-vulkan-drivers vulkan-tools mesa-utils python3`，并保留现有 Mesa EGL、GLX、DRI 包。可用版本取决于设备驱动和 X11 呈现结果。
- **Adreno（骁龙）**：`graphics=turnip` 会在容器内安装与发行版、架构、GPU 对应的 KGSL Turnip 驱动包，沿用应用原有权限；`linux_gui(action="guide")` 会带 AI 找到匹配的包。Vulkan 直接使用 Turnip，OpenGL 优先走 Zink。[驱动包来源](https://github.com/lfdevs/mesa-for-android-container/releases)
- Venus 启动时用小型探针检查硬件驱动 ID、设备创建、提交与 fence，Turnip 仍通过 `vulkaninfo` 枚举。两种模式都要求 `vkcube` 完成 X11 交换链测试；默认呈现失败会退回 CPU 拷贝，3D 渲染仍在 GPU 上。细节见 `status.media.vulkan`、`vulkan_presentation` 与 `guest_vulkan_log`。Zink 完成桌面 GLSL 绘制、像素读回和 GLX 检查后，`status.media.graphics` 显示 `zink`，否则 guest OpenGL 回到 VirGL。实际 GL 版本用 `glxinfo -B` 与 `eglinfo -B -p x11` 确认。

`graphics=virgl_angle` 在 vtest 进程内运行 Vulkan 版 ANGLE。当前构建只在 Mali-G925 renderer 上启用，会先检查内置 ANGLE 库能否加载，并要求 guest 先创建核心态桌面 OpenGL 3.3 上下文、绘制 GLSL 3.30 着色器并读回，再启动桌面，因此容器需要 Python 3、`libegl1`、`libgl1` 和 `libgl1-mesa-dri`。原生 `virgl` 不需要 Vulkan，走纯 GLES 路径。内嵌 X11 上 `glxinfo -B` 报 `X_GetImage BadMatch` 时，用 `eglinfo -B -p x11` 查询版本，再用 `glxgears -info` 单独验证 GLX 窗口。

Vulkan 验证失败会退役原 Venus listener，换成纯 VirGL listener，下次启动仍能得到可用的图形路径。设备或 fence 阶段失败时，可以尝试关闭反馈并使用同步提交，仍以实际 X11 出帧为准。复测时保存 `vulkan_probe_stage`、`vulkan_probe_supervisor_log`、`guest_venus_probe_log`、`guest_venus_probe_retry_log` 和会话日志里的 `LYRA_VENUS_*`，重试 Venus 请新建会话。[Mesa Venus](https://docs.mesa3d.org/drivers/venus.html)

每个会话拥有独立 renderer 进程，内部支持多个客户端，停止会话只结束它自己拥有的原生进程。`status.media.host_gpu` 与 `guest_graphics_log` 提供实际驱动信息，`graphics=virgl` 表示 guest 侧验证通过。发行版 Mesa 缺少 virpipe 时自动回退。

## 本地访问与清理

Xvnc 以 `-rfbport -1 -rfbunixpath ... -rfbunixmode 0600 -SecurityTypes None` 启动，RFB 不打开任何 TCP 端口。宿主在应用私有缓存内创建短路径目录（0700），只把这一会话的 socket 目录绑定到对应 guest。Android 通过文件系统 Unix socket 连接，全程无需生成、传递或询问 VNC 密码。X11 同样禁用 TCP，用每个会话独立随机的 MIT-MAGIC-COOKIE-1 认证客户端，并且与 Android 剪贴板保持独立。

私有 Unix transport 是唯一接受 RFB `SecurityTypes None` 的通道，保留的 TCP 兼容代码始终要求认证，也没有匿名 TCP 或远程地址配置。旧的密码文件生成器已修正 TigerVNC d3des 的密钥位序，并用真实 `tigervncpasswd -f` 验证过，生产流量走私有通道。隔离范围覆盖显示与媒体通道，安全边界与 PRoot 自身一致：同一 UID 下的 Linux 环境处在同一个信任域。

不同环境不共享 `/tmp`。所有新 PRoot 进程都会为该环境挂载稳定的 `/run/lyra` 与 `/tmp/.X11-unix`。原生 Xauthority 位于宿主私有会话目录，VNC 备用的 Xauthority 位于该 rootfs 的会话状态目录。各 GUI 会话独立维护音频/GPU socket 与环境导出文件，其他命令先执行 `status.environment_exports` 即可接入；升级前的旧终端重开一次即可。RFB 保留独立的短路径绑定。宿主终止对应 supervisor、原生 X server 与音频/renderer，清理会话目录时不跟随 guest 符号链接。崩溃或强杀可能留下临时文件，清理时按需删除。

## 构建与验证

```powershell
.\gradlew.bat :lyra_x11:testDebugUnitTest :lyra_x11:lintDebug :app:assembleDebug
```

JVM 测试覆盖私有通道免密码握手、拒绝匿名 TCP、像素与输入消息、ZRLE、CopyRect、尺寸限制、独立视图刷新、环境隔离、关闭竞态、启动参数与命令记忆。`src/androidTest` 提供真实 Android 文件系统上的 Unix socket 测试：用 `:lyra_x11:assembleDebugAndroidTest` 构建测试 APK，连接设备后运行 `:lyra_x11:connectedDebugAndroidTest`。

Release 还需执行 [ART 回归验证](tools/ART_VERIFICATION.md)：`tools/verify-art.py` 使用实际 R8 APK 与对应 mapping，在 Android 上验证 GUI 会话与合成任务，覆盖 Debug 测试发现不了的优化后类加载问题。

媒体验证覆盖 PCM 分帧与积压丢弃、音频/GPU 参数与配置记忆、脚本生成，以及真实 Android 上的 AudioTrack、EGL/vtest 多客户端和资源目录所有权测试。原生通道已在 Android 17 / targetSdk 37 / 16 KB 页模拟器验证；PA 17.0、PipeWire 1.2.7 + WirePlumber 0.5.8 到 FIFO 的音调与声道验证使用项目 PRoot 源码的 Linux 构建、root 模式和私有 bind，WSL 环境关闭了 seccomp 快速路径，相关数据描述的是 Linux 环境。声音路由与 GPU 性能随设备与 SoC 不同，请在目标机型上验证。

`tools/tigervnc-smoke.sh` 用构建产物中的真实 Kotlin 解码器连接原版 TigerVNC，复用同一份 guest 启动脚本、Xauth 与 session D-Bus，验证动态画面和密码文件兼容性。把 `LYRA_X11_CLASSPATH` 指向模块 `classes.jar` 与 Kotlin stdlib，Linux/JDK17+ 环境需具备工具规范中的共同依赖、Python3/libX11 和 `setsid`。设置 `LYRA_X11_EXEC_WRAPPER` 可以测试 PRoot 的 socket 目录绑定，wrapper 接收 `LYRA_X11_HOST_BRIDGE`、`LYRA_X11_GUEST_BRIDGE` 后执行后续命令。输入法、悬浮权限、旋转与 GPU 驱动通过真机验证。

## 原生 X11 源码

源码压缩包、固定 revision、构建脚本、适配代码与许可证都在 [native/xserver](native/xserver/README.md)。状态页与 AI 的 `status.display_backend` 提供 `ahb_frames`、`uploaded_frames`、`gpu_mirrors`、`screenshot_readbacks` 和实际 transport，当前路径从计数上即可读出。

原生 2D X11 绘制、VirGL 应用读回和 Vulkan 的 CPU 拷贝呈现都会占用 CPU。对比 Android 设备上的 VirGL 与 Venus/Zink 时，固定分辨率、帧率上限和场景，记录实际驱动版本、呈现路径、进程 CPU 占用与帧时间，并把首轮着色器编译与缓存预热后的表现分开记录。

## 参考与许可

本模块代码遵循 GPL-3.0 许可。下列项目为设计与接口提供参考，Termux-X11 的原生 DDX 及构建依赖已按固定来源集成：

- [Termux-X11](https://github.com/termux/termux-x11)：原生 Android X server、共享 tmp、输入与渲染设计参考（调研 revision `f1d9834236341a5b3723cd7e4fe65601673a2d5a`）。
- [TigerVNC Xvnc 文档](https://tigervnc.org/doc/Xvnc.html)：guest X server 命令接口。
- [RFB 协议规范](https://github.com/rfbproto/rfbproto/blob/master/rfbproto.rst)：网络格式、VNC authentication、ZRLE 与扩展定义。
- 安装命令参照 [Debian 包](https://packages.debian.org/en/stable/x11/tigervnc-standalone-server)、[Alpine 包](https://pkgs.alpinelinux.org/package/edge/community/aarch64/tigervnc)、[Arch 工具包](https://archlinux.org/packages/extra/x86_64/xorg-xdpyinfo/) 和 [Fedora X11 server 包](https://packages.fedoraproject.org/pkgs/tigervnc/tigervnc-x11-server/)。Fedora 43+ 将 `tigervnc-x11-server` 与 `xdpyinfo` 拆分为不同包。

VirGL/libepoxy 的固定来源与 Android 补丁及其 MIT 许可位于 [native](native/README.md)，完整源码随模块保留，许可证文本同时打包进 assets/lyra_x11/licenses。TigerVNC、PulseAudio/PipeWire、桌面、字体与 guest Mesa 由发行版包管理器提供。

