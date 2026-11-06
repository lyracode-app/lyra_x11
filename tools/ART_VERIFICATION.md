# Release GUI / ART 回归验证

GUI 会话控制代码必须验证最终 R8 APK；仅通过 JVM 测试、Debug APK 或直接调用 NativeDisplay，不能排除优化后 DEX 被 ART 拒绝的问题。

## 本次问题与修复

2026-11-07 保存的旧 Release 中，`GuiSessions` 的启动/停止/移除任务被 R8 合并到 `w32.run()`。ART 在该类加载时就拒绝它，所以原生 X11 与 VNC 都无法启动，尚未执行 guest 桌面命令。

`GuiProcess`、`GuiDisplay` 和 `GuiMedia` 原来只继承 `AutoCloseable.close()`。启用核心库脱糖的宿主 App 在这些调用处插入了通用 `ExecutorService` 关闭兼容分支。旧 DEX 中，`monitor-enter` 位于 `0x35`，`0x67` 的 `TimeUnit.DAYS` 指令所在区间 `[0x67, 0x6d)` 仅有 `InterruptedException` 处理器，没有持锁时必需的 catch-all。Android 17 ART 实际报告：

```text
Verifier rejected class w32: void w32.run() failed to verify:
[0x67] expected to be within a catch-all for an instruction where a monitor is held
```

修复是在三个 GUI 资源接口中显式声明 `override fun close()`，让调用解析到自身资源契约，避免在会话锁内插入上述无关分支。保留会话资源清理的同步与生命周期屏障，保留 R8、资源压缩及核心库脱糖。

## 验证命令

先构建，再将 APK 与**同一次构建**的 mapping 一起传给验证脚本。设备应为专用测试设备或模拟器，推荐覆盖用户报告的 Android 版本。没有 Release 签名密钥的测试环境可显式使用以下 init script 生成未签名优化包，它只影响本次调用，不修改项目签名配置。已有签名构建流程可直接使用其 APK 和对应 mapping。

```powershell
.\gradlew.bat :lyra_x11:testDebugUnitTest :app:assembleRelease --init-script lyra_x11/tools/unsigned-release.gradle
python lyra_x11/tools/verify-art.py --sdk G:/sdk --serial emulator-5582 --apk app/build/outputs/apk/release/app-release-unsigned.apk --mapping app/build/outputs/mapping/release/mapping.txt
```

脚本从 mapping 读取 `GuiSession*` 类与合成任务的真实名称，也包括被合并到其他类或被内联的会话方法，构建一个独立 DEX 验证器，在设备临时目录中以只读 APK 加载最终产物，并实例化 Runnable 捕获容器以触发 ART 校验。它不执行 Runnable，不启动 Linux 命令，不安装 APK，也不更改应用数据；结束后删除本次上传的临时文件。未发现任务、类加载错误或 VerifyError 均使命令失败。原始 ART 输出保存在 `.cache/lyra-art-verify/art-verification.log`。

这项检查用于验证优化产物的可加载性；实际桌面、音频、驱动与键鼠体验仍按 [DEVICE_VALIDATION.md](../DEVICE_VALIDATION.md) 验收。APK 是否已签名不影响这项 DEX 检查；安装 Release 时仍需使用原有应用签名。

## 2026-11-07 验证记录

Android 17 / API 37 / x86_64 / 16 KB 页模拟器上的实际结果：

| 产物 | ART 结果 |
| --- | --- |
| 原签名 Release，`w32.run()` | 稳定复现 `0x67` catch-all VerifyError |
| 修复后的 R8 Release | `ART_GUI_VERIFY classes=24 tasks=2 failures=0` |

修复包的主任务现在位于 `v32.run()`；被合并到其他组件名下的日志读取任务 `ts` 也通过验证。不能跨构建硬编码混淆名。对比实际 DEX，旧主任务含 9 处 `TimeUnit.DAYS` / `awaitTermination` 兼容分支，修复后的主任务均为 0。

同时通过 39 项 JVM 测试及 5 项 Android instrumentation 测试，后者覆盖真实 X11 协议、多 Surface、键鼠输入、Unix socket、AudioTrack 和 EGL/vtest。测试未连接用户的 RMX6688，不能据此声明已完成该真机验收。

产物 SHA-256：

```text
原 app/release/app-release.apk（保留未覆盖）
c80fa6c444e440e727e177bcf0cd46aa173f39b498ad31b783cfcda533d4615a

修复 app/build/outputs/apk/release/app-release-unsigned.apk
18ba0adf3d79c115cc8ffd2a3ffc2d439022a354950fa89a77e25e84199b818c

修复 app/build/outputs/apk/debug/app-debug.apk
30145891c2210b34e049977fb35ecdb11bcc502f6ef4f06fadd782677de8f927
```

本次原始日志及 DEX 对比位于 `.cache/lyra-art-monitor`、`.cache/lyra-art-baseline`、`.cache/lyra-art-verify`，后续构建应重新生成验证结果及 hash。Debug 使用独立包名和应用数据；复测已有 Linux 环境应以原发布密钥签名修复后的 Release 并覆盖安装。
