# Lyra X11

独立 Android library（`:lyra_x11`），为 PRoot 内的 Linux X11 应用提供应用内桌面、键鼠与触控/输入法交互。

默认使用 **Android 独立进程中的 X.Org/Lorie DDX + AHardwareBuffer/EGLImage + SurfaceView**，集成 Termux-X11 的原生服务器与缓冲区实现，不依赖 Termux 应用。Linux 发行版通过标准 X11 socket 和 Xauthority 连接；PRoot 只提供通用目录绑定。VNC 保留为可选备用后端。

```text
Linux X11 应用 → /tmp/.X11-unix/X100 → Android 原生 X server
                                      ↓ 共享 AHardwareBuffer / fence
                                  EGLImage → SurfaceView
                                           ↘ GPU 镜像 → 悬浮窗
备用：Linux TigerVNC → 私有 Unix RFB → Android Bitmap
```

“自动”在执行桌面命令之前优先初始化原生后端，失败才选择 VNC；“原生 X11”失败时保留错误；“VNC 备用”明确使用旧管线。配置按 Linux 环境记忆。普通显示不进行 GPU→CPU 读回；用户主动截图才读回。每会话独立服务器，最多 8 个 Surface，当前界面使用主页面和悬浮窗。

## 使用

1. 在应用的 PRoot Linux 设置中，点环境旁边的桌面图标。
2. 让 AI 调用 `linux_gui(action="guide")`，按统一的[安装工具规范](src/main/assets/lyra_x11/GUI_TOOL_SPEC.md)检查该环境，再用 `proot_command` 安装最小依赖。Debian/Ubuntu 的 LXDE 方案例如：

   ```sh
   apt update
   apt install --no-install-recommends x11-utils dbus-x11 fonts-dejavu-core lxde-core lxterminal
   ```

3. 在“预设 / 最近使用”选择 LXDE、Xfce、LXQt、Openbox 或历史命令，也可自定义。每个环境独立记忆上次命令、分辨率、帧率和最近 8 条命令。连接成功后自动收起工具栏，右上角小按钮可重新展开。
4. 从同一环境的其他终端启动应用时，复制“会话日志”顶部的 `DISPLAY` 与 `XAUTHORITY` 两行，再启动应用。

Android 管理原生 X server，guest 脚本运行 `dbus-run-session`，不通过 systemd、显示管理器、system bus 或登录管理器。桌面自身若强制要求这些设施，仍不能在普通无 root PRoot 中运行；该模块不自动安装或修改导入的 rootfs 软件。

同一会话中的 Linux 窗口由其窗口管理器管理。不同环境、同一环境的不同 display 都作为独立会话，可在列表中切换。当前不将每一个 X11 顶层窗口拆成 Android Activity。最多同时运行 4 个会话，初始总桌面面积最多 16,588,800 像素；单桌面最大 3840×2160。

全屏页面自动横屏并隐藏系统栏；工具栏以浮层显示，展开时不会挤压桌面。轻点为左键，长按为右键，拖动为左键拖拽，双指滚动。支持 USB/蓝牙鼠标、滚轮、物理键盘和修饰键；在桌面区域隐藏 Android 的系统指针，按帧缓冲比例绘制 Linux 游标。手机输入法在确认候选词后发送 Unicode keysym，支持删除和回车。焦点丢失及切换会话时释放按键和鼠标按钮。部分系统按键/快捷键由 Android 保留。输入法不读取远程编辑器的上下文；Linux 应用仍须安装能显示相应文字的字体。

返回后桌面继续运行，前台服务通知提供返回入口与“停止全部桌面”。关闭单个会话会终止其 PRoot supervisor 及 tracees。禁用/删除环境先关闭其图形会话。应用进程被系统终止后不恢复图形会话。若系统留下异常残留的旧 X server，启动日志会报告 display 占用，需要在对应 Linux 环境清理或重启应用运行环境。

## AI 应用预览与悬浮窗口

`linux_gui` 提供 `guide`、`list`、`start`、`status`、`show`、`hide`、`stop`、`screenshot` 和 `request_overlay_permission`。定义、路由适配、规范与界面均在本模块目录维护；自定义启动命令沿用应用现有的 PRoot 命令审批规则。

```json
{"action":"start","linux_id":"debian","app_command":"cd /workspace/demo && python3 main.py","mode":"overlay","width":1024,"height":768,"fps":30}
```

应用预览自动启动 Openbox 与 session D-Bus，可直接交互，不覆盖用户记忆的桌面命令。需先安装 Openbox 和项目本身所需的 GUI 库。悬浮模式使用 Android 的“显示在其他应用上层”权限；缺少权限时工具不启动新会话，可打开系统授权页，授权后重试。

悬浮窗可拖动标题栏、拖动右下角调整大小、最小化、弹出输入法、全屏打开或关闭会话。`hide` 只隐藏浮窗；`stop` 终止所选会话。

后台 AI 发起悬浮预览时，先显示真实预览窗口，再启动其前台服务，满足 Android 15+ 的可见悬浮窗条件；显示或服务启动失败会返回错误并停止本次新会话。[Android 前台服务限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

截图按钮和 `screenshot` 直接保存 Linux 帧缓冲，无需在 Linux 安装截图工具，也不包含 Android 工具栏。Android 10+ 保存到 `Pictures/LyraCode`，旧版保存到应用的外部图片目录。工具返回实际 URI/路径。图形内容必须已经就绪。

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
| `src/main/assets/lyra_x11/GUI_TOOL_SPEC.md` | AI 安装和排错的唯一规范来源 |
| `host/java/` | 唯一 PRoot 宿主适配层，作为 `:app` 源码编译以使用其 internal API |
| `src/test/` | 协议、压缩、输入消息、认证、隔离及关闭竞态测试 |

模块代码、资源、测试及宿主适配代码全部保存在此目录。根工程注册项目，app 声明依赖、宿主源码目录、Activity、设置入口和 AI 工具路由。PRoot 提供可选的目录绑定以及启动前撤销禁用环境的生命周期接口；不把图形协议或驱动实现复制到 app。

## 传输与性能

- 支持 RFB 3.3/3.7/3.8，协商 ZRLE、CopyRect、Raw，以及 DesktopSize、Cursor、LastRect 扩展。
- 每连接复用 zlib 状态。ZRLE 以 16 KiB 缓冲流式解码，不分配整屏解压副本；纯色块批量填充，CopyRect 支持重叠区域。
- 帧缓冲按脏矩形更新，Android View 使用硬件加速 Canvas 合成。帧内更新合并成一次界面重绘；不在主线程做网络读取和解压。
- 每连接只保留一个增量更新请求。非当前会话最多约 2 次/秒请求更新；当前会话由 Xvnc 的 15/30/60 FPS 上限控制，实际帧率取决于设备及负载。
- 指针移动合并，按键和按钮边沿按序传输。有界输入队列溢出时关闭连接，避免静默丢失释放事件。IME 长文本作为一批消息发送。
- 解码尺寸、矩形、压缩长度、解压量、游标和字符串长度均有上限。停止后释放会话帧缓冲，错误日志上限 16 KiB。

RFB 需要 guest 像素编码、host 解码和位图上传，存在额外拷贝。它适合通用 2D 桌面兼容后端，不能保证视频、游戏、CAD 或 4K/60 FPS 性能。部分用户已反馈其设备运行 LXDE、1920×1080/30 FPS 无明显问题，可以参考。

### 音频输出

每会话独立运行 guest PulseAudio，或 PipeWire + pipewire-pulse + WirePlumber 0.5+；标准 pipe sink/tunnel 将 48 kHz 双声道 S16LE 写入私有 FIFO，Android `AudioTrack` 播放。没有音频 TCP 监听、用户密码、systemd 或录音权限。ALSA 程序使用会话生成的 pulse 插件配置，不改全局配置。

首次默认 `audio=auto`，优先已有 PulseAudio，再尝试完整 PipeWire 栈；可显式选择 pulseaudio、pipewire 或 off，并按环境记忆。Android 输出队列上限 80 ms，过载丢弃最旧整帧；写入不阻塞 UI，处理音频焦点、静音、多会话混音与 AudioTrack 服务重建。Android 决定扬声器/蓝牙/USB 路由，手机媒体音量控制输出。实际延迟还包括 guest 服务、内核 FIFO、Android 输出设备与蓝牙链路，80 ms 不是总延迟承诺。

工具栏、悬浮窗和 `linux_gui(action=audio, session_id=..., muted=...)` 可控制静音。`status.media` 返回所选后端、收到/写入/丢弃帧数、队列、underrun、音频焦点和错误。安装命令及诊断流程见 [工具规范](src/main/assets/lyra_x11/GUI_TOOL_SPEC.md)。

### Linux GPU 加速 （这个不用看，残次品还用不了）

现已打包 ARM64/x86_64 的 virglrenderer 1.3.0 + libepoxy 1.5.10 Android 服务，使用 Android vendor EGL/GLES，不依赖 Termux 安装、root 或直接 GPU 节点访问。源码归档、采用的 Termux Android 补丁、许可证、16 KB 对齐可执行文件和重建脚本全部位于本模块。详见 [native/README.md](native/README.md)。

`graphics=auto` 先在独立进程探测 GLES 3 与实际 Android renderer，再通过 guest Mesa virpipe 和有超时的 `glxinfo -B` 验证。通过后才给桌面/应用导出 GPU 变量；失败保留软件显示与日志。`graphics=virgl` 强制要求验证成功，`software` 完全绕过原生服务。禁止通过虚报 GL 版本使检查通过。

Dimensity 9400/Immortalis 使用通用 EGL/GLES 路径；Adreno、Intel/AMD 等同样以实际 EGL 能力为准。Turnip 不是 Mali 驱动。原生 Android DDX 使用 AHardwareBuffer 共享显示表面，VNC 备用仍有 RFB 编解码开销；通用 guest DRM/DRI3 驱动、Vulkan 与视频硬解尚未提供。VirGL 加速兼容的 Linux OpenGL 工作，与显示后端分别报告。

每个会话拥有独立 renderer 进程，内部支持多个客户端；停止会话只结束它拥有的原生进程，不使用全局 pkill。`status.media.host_gpu` 和 guest_graphics_log 提供实际驱动信息；只有 graphics=virgl 才表示 guest 验证成功。无可用 virpipe 的发行版 Mesa 会回退。

## 本地访问与清理

Xvnc 使用 `-rfbport -1 -rfbunixpath ... -rfbunixmode 0600 -SecurityTypes None`，RFB 不开放 TCP 端口。宿主在应用私有缓存内创建短路径目录（0700），仅将这一会话的 socket 目录绑定到对应 guest。Android 使用文件系统 Unix socket 连接；不生成、传递或询问 VNC 密码。X11 同样禁用 TCP，并以独立随机 MIT-MAGIC-COOKIE-1 认证客户端，不自动共享 Android 剪贴板。

只有私有 Unix transport 接受 RFB SecurityTypes None。保留的 TCP 协议兼容代码始终要求认证，不提供匿名 TCP 或远程地址配置。旧密码文件生成器已修复 TigerVNC d3des 的密钥位序，并用真实 `tigervncpasswd -f` 验证，生产私有通道不再依赖它。PRoot 不是恶意 guest 的安全沙盒，同 UID 的 Linux 环境不构成强安全隔离。

不同环境不共享 `/tmp`。所有新 PRoot 进程为该环境挂载稳定的 `/run/lyra` 和 `/tmp/.X11-unix`。原生 Xauthority 位于宿主私有会话目录，VNC 备用的 Xauthority 位于该 rootfs 的会话状态目录。各 GUI 会话独立维护音频/GPU socket 与环境导出文件；其他命令先执行 `status.environment_exports` 即可连接。升级前的旧终端需重开一次。RFB 保留独立短路径绑定。宿主终止对应 supervisor、native X server 与音频/renderer，清理本会话目录时不跟随 guest 符号链接。崩溃/强杀可能留下临时文件，当前不跨进程恢复或自动删除未知旧会话。

## 构建与验证

```powershell
.\gradlew.bat :lyra_x11:testDebugUnitTest :lyra_x11:lintDebug :app:assembleDebug
```

JVM 测试覆盖私有通道免密码握手、拒绝匿名 TCP、像素/输入消息、ZRLE、CopyRect、尺寸限制、独立视图刷新、环境隔离、关闭竞态、启动参数与命令记忆。`src/androidTest` 提供真实 Android 文件系统 Unix socket 测试；测试 APK 可通过 `:lyra_x11:assembleDebugAndroidTest` 构建，连接设备后运行 `:lyra_x11:connectedDebugAndroidTest`。

Release 还需执行 [ART 回归验证](tools/ART_VERIFICATION.md)：`tools/verify-art.py` 使用实际 R8 APK 和对应 mapping，在 Android 上验证 GUI 会话及合成任务，覆盖 Debug 测试无法发现的优化后类加载问题。

媒体验证新增 PCM 分帧/积压丢弃、音频/GPU 参数和配置记忆、脚本生成测试，以及真实 Android AudioTrack、EGL/vtest 多客户端、资源目录所有权测试。已在 Android 17 / targetSdk 37 / 16 KB 页模拟器验证原生通道；PA 17.0、PipeWire 1.2.7 + WirePlumber 0.5.8 到 FIFO 的音调/声道验证使用项目 PRoot 源码的 Linux 构建、root 模式和私有 bind。WSL 测试关闭其 seccomp 快速路径，不能据此代表 Android 内核表现。用户的 RMX6688/Dimensity 9400 尚需真机验证声音路由和 GPU 性能。

`tools/tigervnc-smoke.sh` 使用构建产物中的真实 Kotlin 解码器连接原版 TigerVNC，运行相同 guest 启动脚本、Xauth 和 session D-Bus，验证动态画面与密码文件兼容性。设置 `LYRA_X11_CLASSPATH` 为模块 `classes.jar` 与 Kotlin stdlib，Linux/JDK17+ 环境需已具备该工具规范中的共同依赖、Python3/libX11 和 `setsid`。可通过 `LYRA_X11_EXEC_WRAPPER` 测试 PRoot 的 socket 目录绑定；wrapper 接收 `LYRA_X11_HOST_BRIDGE`、`LYRA_X11_GUEST_BRIDGE` 并执行后续命令。该测试不能替代 Android 输入法、悬浮权限、旋转及 GPU 驱动的真机验证。

## 参考与许可

本模块代码遵循 GPL-3.0 许可。以下项目为设计/接口参考，Termux-X11 的原生 DDX 和构建依赖已集成并固定来源，tiny_container 与 X11Libre 仅为参考：

- [Termux-X11](https://github.com/termux/termux-x11)：原生 Android X server、共享 tmp、输入与渲染设计参考（调研 revision `f1d9834236341a5b3723cd7e4fe65601673a2d5a`）。
- [tiny_container](https://github.com/Cateners/tiny_container)：多容器与内嵌 VNC/X11、图形加速的职责分离参考（`5f15dd1986b2a0569791f42e3838a823049206d1`）。
- [X11Libre/xserver](https://github.com/X11Libre/xserver)：候选原生 X server 项目（远端 revision `ee4bb3f96eec3b21c1961d54fe45cb33dc402d00`，未集成或测试）。
- [TigerVNC Xvnc 文档](https://tigervnc.org/doc/Xvnc.html)：guest X server 命令接口。
- [RFB 协议规范](https://github.com/rfbproto/rfbproto/blob/master/rfbproto.rst)：网络格式、VNC authentication、ZRLE 和扩展定义。
- 安装命令参照 [Debian 包](https://packages.debian.org/en/stable/x11/tigervnc-standalone-server)、[Alpine 包](https://pkgs.alpinelinux.org/package/edge/community/aarch64/tigervnc)、[Arch 工具包](https://archlinux.org/packages/extra/x86_64/xorg-xdpyinfo/) 和 [Fedora X11 server 包](https://packages.fedoraproject.org/pkgs/tigervnc/tigervnc-x11-server/)。Fedora 43+ 使用拆分后的 `tigervnc-x11-server` 与 `xdpyinfo`。

新增 VirGL/libepoxy 与 Android 补丁的固定来源和 MIT 许可位于 [native](native/README.md)，完整源码随模块保留，许可证同时打包进 assets/lyra_x11/licenses。TigerVNC、PulseAudio/PipeWire、桌面、字体及 guest Mesa 由发行版包管理器提供，不作为 APK 二进制分发。

## 原生 X11 源码与验证

源码压缩包、固定 revision、构建脚本、适配代码和许可证均在 [native/xserver](native/xserver/README.md)。状态页及 AI `status.display_backend` 提供 `ahb_frames`、`uploaded_frames`、`gpu_mirrors`、`screenshot_readbacks` 和实际 transport；不能只凭启用选项声称走了 AHardwareBuffer。

原生 2D X11 绘制仍可能使用 CPU；guest Mesa virpipe 的应用级读回并未普遍消除。Vulkan、任意发行版 DRI3 驱动和视频硬解不在本次实现范围。
