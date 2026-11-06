# 真机验收

本清单属于后续设备验证工作，不能将未执行项视为已通过。记录应用 revision、Android 版本、SoC/GPU、ABI、rootfs 发行版及包版本。

## 功能

- 进入桌面页面自动横屏，系统栏与上方工具栏在连接后收起；展开控制层不缩小 Linux 画面，启动时不遗留 Android 输入法。
- 在 Debian 选 LXDE、自定义命令、1920×1080/30 FPS，退出并重开后保留；切换另一环境验证预设、历史及设置独立，单次应用预览不覆盖桌面命令。
- 确认 RFB 只创建私有 Unix socket（0600）且没有 TCP listener，不再询问 VNC 密码。用另一次 PRoot 终端的 DISPLAY/XAUTHORITY 连接同一桌面，确认状态文件未被隔离在单次 bind 内。
- 通过 `linux_gui` 读取规范、启动一个 Openbox 应用预览。验证悬浮权限未授予、取消、授予、运行中撤销的处理，工具的 connected/has_frame/overlay_visible 与真实状态相符。
- 浮窗拖动、缩放、旋转、最小化、键鼠及 IME、全屏打开、隐藏和停止。浮窗与 Activity 同时显示时都及时更新；离开 Activity 后浮窗仍按活动会话刷新。
- 截图按钮与工具直接输出 Linux 画面 PNG 到相册，验证不包含 Android 控制栏、没有借助 Linux 截图命令。
- 外接鼠标在桌面区域只显示按 Linux 帧尺寸绘制的游标，不叠加 Android 原生大指针。
- Android 8/10/13/16+：启动桌面、转屏、分屏、输入法显示/隐藏时内容与触点坐标保持一致。
- 至少 Debian/Ubuntu（glibc）、Alpine（musl）各启动 Xfce 或 Openbox；确认无 systemd 即可运行。
- 同一环境开两个会话，另一环境再开一个会话。各开终端与两个窗口，确认窗口焦点、移动、遮挡、独立 DISPLAY/XAUTHORITY 与环境切换。
- 在 terminal 中复制会话日志的 export 命令并打开 xterm，确认出现在正确桌面；错误 cookie 连接应失败。
- 触摸轻点/长按/拖动/双指捲动、蓝牙/USB 鼠标左右中键及横纵滚轮、物理键盘左右 Ctrl/Alt/Shift、方向键、F1–F12。
- 中文拼音候选、连续输入、取消组合、退格、换行、emoji。组合输入期间切换会话，确认旧组合不会发送到新会话。
- 键盘/鼠标按住时切换会话、弹出系统对话框、返回，确认无卡住的修饰键或鼠标按钮。
- 停止一个会话、通知停止全部、禁用/删除一个环境；确认其他环境仍运行、目标 PRoot/Xvnc/桌面均退出，临时目录清除。
- 背景运行、系统收回应用、重启应用，确认不错误恢复已失效连接；人为制造 X display/端口冲突后检查明确错误日志。

## 性能

每个设备从 1280×720/30 FPS 开始，再测 1080p 与 60 FPS 上限。分别测终端滚动、窗口拖动、浏览器、软件 OpenGL；记录实际帧率、输入到显示延迟 p50/p95、CPU、RSS、温度及耗电。对比 1 个活动会话与 3 个后台会话，检查后台节流。帧率上限不是保证值，未测得的数据不填写估计值。

对 VirGL 单独记录 guest renderer（`status.media.guest_graphics_log` / `glxinfo -B`）、host_gpu、实际 GPU 与 driver、AUTO 回退和强制模式报错。仅观察到 Android View 使用 GPU 不能证明 Linux OpenGL 获得 GPU 加速。

## 音频 / GPU

- RMX6688 / Dimensity 9400 / Android 17：先保持 1920×1080/30 FPS，以 audio=auto、graphics=auto 创建新会话。旧终端重新打开一次，source `environment_exports`。
- PulseAudio：播放一个已知音频文件，检查 heard/received_frames/written_frames、左右声道和音调；分别验证扬声器、蓝牙、USB、媒体音量、静音/恢复、切换其他音乐应用及电话后的音频焦点。
- PipeWire：确认 WirePlumber 0.5+ 与 pipe-tunnel 模块，显式 audio=pipewire，分别测试原生 PipeWire 客户端、Pulse 客户端、ALSA pulse 插件客户端。仅显示 daemon/socket 就绪不能作为端到端音频通过。
- 同一环境两个会话加另一个环境一个会话，同时播放不同音频；单会话静音/停止只影响目标，后台/最小化保持音频，停止全部后服务与 FIFO 消失。
- 故意缺失音频组件、错误 PipeWire 版本、音频服务提前退出时，桌面仍可使用，诊断保留具体原因。停止/删除环境时没有 AudioTrack、PRoot、PA/PW/WirePlumber 或 VirGL 残留。
- GPU 以 graphics=software 与 auto/virgl 对照同一 OpenGL 场景，记录实际 renderer 与帧时间。Dimensity 不安装 Turnip；未通过 guest virgl 检测时不得声称加速。
- 拔插外屏/切换前后台、长时间运行、旋转、同时多个 OpenGL 窗口、多个会话分别验证。主动结束目标 renderer 时应报告其退出，重建该会话或选择 software；不承诺运行中无缝迁移既有 GL context。
- 原生 Android 测试：`:lyra_x11:connectedDebugAndroidTest` 检查文件系统 Unix socket、AudioTrack FIFO/静音/关闭、隔离 EGL 与两个 vtest 客户端。测试会播放短测试音，模拟器测试不能替代手机实际音频路由或 Immortalis 驱动验证。
- Linux 音频测试：配置模块/Kotlin CLASSPATH 后运行 `tools/audio-smoke.sh`，验证真实 PA/PW 到 FIFO 的 48 kHz 双声道 440 Hz 音频；可指定 `LYRA_MEDIA_EXEC_WRAPPER` 进行 PRoot bind 测试。测试不会安装软件或修改系统服务。
