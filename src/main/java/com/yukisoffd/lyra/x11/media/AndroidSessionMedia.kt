package com.yukisoffd.lyra.x11.media

import android.content.Context
import android.system.Os
import com.yukisoffd.lyra.x11.AudioBackend
import com.yukisoffd.lyra.x11.GraphicsBackend
import com.yukisoffd.lyra.x11.GuestMedia
import com.yukisoffd.lyra.x11.GuiMedia
import com.yukisoffd.lyra.x11.GuiOptions
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Android-owned renderer/audio lifetime. Only the stable directory bind depends on the container. */
class AndroidSessionMedia private constructor(private val context: Context, private val directory: File,
    private val sessionId: String, private val options: GuiOptions, private val log: (String) -> Unit) : GuiMedia {
    private val closed = AtomicBoolean(false)
    private var directoryOwned = false
    @Volatile private var renderer: Process? = null
    private var audio: PcmAudioOutput? = null
    @Volatile private var hostGpu = "software"
    @Volatile private var gpuError = ""
    private var probe = emptyMap<String, String>()
    @Volatile private var gpuReady = false
    @Volatile private var finalDiagnostics: Map<String, Any>? = null
    override val guest: GuestMedia get() = GuestMedia("/run/lyra/${key(sessionId)}", gpuReady)
    override val graphicsProbeRequested: Boolean get() = File(directory, "vulkan.request").isFile

    override fun graphicsProbeFinished(result: String, output: String) {
        // Release the waiting desktop even if the optional diagnostic log cannot be saved.
        runCatching { File(directory, "vulkan.supervisor.log").writeText(output) }
        if (options.graphics == GraphicsBackend.VENUS && !File(directory, "vulkan.env").isFile) {
            val tryVirgl = gpuReady
            gpuReady = false
            hostGpu = "software"
            runCatching {
                stopRenderer()
                Files.deleteIfExists(File(directory, "virgl").toPath())
                if (tryVirgl) startRenderer(venus = false)
            }.onFailure { error ->
                gpuError = error.message ?: error.javaClass.simpleName
                log("LYRA_GPU: Unable to replace the failed Venus listener: $gpuError")
            }
        }
        val pending = File(directory, "vulkan.supervisor.tmp")
        pending.writeText(result)
        Files.move(pending.toPath(), File(directory, "vulkan.supervisor").toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun start() {
        check(directory.mkdir()) { "Unable to create media bridge" }
        directoryOwned = true
        Os.chmod(directory.absolutePath, 448)
        if (options.audio != AudioBackend.OFF) {
            try {
                val fifo = File(directory, "audio.pcm")
                Os.mkfifo(fifo.absolutePath, 384)
                audio = PcmAudioOutput(context, fifo, log)
            } catch (error: Exception) { log("LYRA_AUDIO: Android output unavailable: ${error.message}") }
        }
        if (options.graphics != GraphicsBackend.SOFTWARE) {
            if (options.graphics.requiresVulkan) copyAssets("vulkan-graphics.sh", "virgl-probe.py")
            if (options.graphics == GraphicsBackend.VENUS) copyAssets("venus-probe.py")
            try {
                copyAssets("mpv-egl-refresh", "mpv-egl-refresh.c", "chromium-webgl")
                if (!options.graphics.requiresVulkan) copyAssets("virgl-probe.py")
                startRenderer()
            }
            catch (error: Exception) {
                gpuError = error.message ?: error.javaClass.simpleName
                renderer?.destroy(); renderer = null
                log("LYRA_GPU: $gpuError")
                if (options.graphics.requiresVirgl || options.graphics == GraphicsBackend.VENUS) {
                    throw IllegalStateException("${options.graphics.id} unavailable: $gpuError", error)
                }
            }
        }
    }

    private fun copyAssets(vararg names: String) {
        for (name in names) {
            context.assets.open("lyra_x11/$name").use { input ->
                File(directory, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun ProcessBuilder.rendererEnvironment(angleDirectory: File?) = apply {
        environment().remove("LD_PRELOAD")
        environment().remove("LYRA_VIRGL_ANGLE")
        if (angleDirectory != null) {
            environment()["LYRA_VIRGL_ANGLE"] = "1"
            environment()["LD_LIBRARY_PATH"] = angleDirectory.absolutePath
        }
    }

    private fun probeEgl(binary: File, angleDirectory: File? = null): JSONObject {
        // Child probes are isolated from the application's hardware accelerated Canvas/EGL.
        val output = File(directory, "egl.json")
        val process = ProcessBuilder(binary.absolutePath, "--lyra-probe").rendererEnvironment(angleDirectory)
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Android EGL probe timed out" }
            check(process.exitValue() == 0) { "Android EGL probe failed: ${readBounded(output)}" }
            val lines = readBounded(output).lineSequence().firstOrNull { it.startsWith('{') } ?: error("Invalid EGL probe output")
            val info = JSONObject(lines)
            val name = info.getString("renderer").lowercase()
            check(listOf("swiftshader", "llvmpipe", "softpipe", "software rasterizer").none { it in name }) { "Android EGL selected software rendering: $name" }
            return info
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private fun startRenderer(venus: Boolean = options.graphics == GraphicsBackend.VENUS) {
        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(nativeDirectory, "liblyra_virgl_exec.so")
        check(binary.isFile && binary.canExecute()) { "Bundled vtest executable is unavailable for this ABI; reinstall the complete APK" }
        val venusServer = File(nativeDirectory, "liblyra_venus_exec.so")
        if (venus) check(venusServer.isFile && venusServer.canExecute()) { "Bundled Venus render server is unavailable for this ABI" }
        val nativeEgl = try {
            probeEgl(binary)
        } catch (error: IllegalStateException) {
            if (!venus) throw error
            gpuError = error.message.orEmpty()
            log("LYRA_GPU: Starting Venus without VirGL: $gpuError")
            null
        }
        val angleDirectory = nativeDirectory.takeIf { options.graphics == GraphicsBackend.VIRGL_ANGLE }
        val egl = if (angleDirectory == null) nativeEgl else {
            val nativeRenderer = nativeEgl?.optString("renderer").orEmpty()
            check(nativeRenderer.startsWith("Mali-G925")) { "VirGL ANGLE currently supports Mali-G925; detected $nativeRenderer" }
            check(listOf("libEGL_angle.so", "libGLESv2_angle.so").all { File(angleDirectory, it).isFile }) {
                "Bundled ANGLE Vulkan libraries are unavailable for this ABI"
            }
            probeEgl(binary, angleDirectory).also { angle ->
                val name = angle.getString("renderer")
                check(name.startsWith("ANGLE (ARM, Vulkan ") && nativeRenderer in name && angle.optBoolean("desktop_gl_features")) {
                    "Required desktop GL features unavailable on $name"
                }
            }
        }
        val eglReady = egl != null
        probe = egl?.let { info -> listOf("renderer", "vendor", "version").associateWith { info.optString(it) } }.orEmpty()
        val socket = File(directory, "virgl")
        require(socket.absolutePath.toByteArray().size < 104) { "vtest socket path exceeds Unix socket limit" }
        val command = mutableListOf(binary.absolutePath, "--socket-path", socket.absolutePath)
        command += if (eglReady) listOf("--use-egl-surfaceless", "--use-gles") else listOf("--no-virgl")
        if (venus) command += "--venus"
        val server = ProcessBuilder(command).rendererEnvironment(angleDirectory).redirectErrorStream(true).apply {
            if (venus) environment()["RENDER_SERVER_EXEC_PATH"] = venusServer.absolutePath
        }.start()
        renderer = server
        Thread({
            runCatching { server.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(1024)
                while (!closed.get()) {
                    val count = reader.read(chars); if (count < 0) break
                    log("vtest: ${String(chars, 0, count)}")
                }
            } }
        }, "lyra-virgl-log").apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!socket.exists() && server.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
        check(server.isAlive && socket.exists()) { "vtest failed to create its private socket" }
        Os.chmod(socket.absolutePath, 384)
        gpuReady = eglReady
        hostGpu = when {
            venus && eglReady -> "egl_gles_virgl_venus"
            venus -> "venus"
            angleDirectory != null -> "egl_angle_vulkan_virgl"
            else -> "egl_gles_virgl"
        }
        log("LYRA_GPU: Host $hostGpu listener ready. Guest validation pending.")
    }

    override fun diagnostics(): Map<String, Any> = finalDiagnostics ?: snapshot()
    private fun rendererExitCode() = renderer?.let { runCatching(it::exitValue).getOrNull() }
    private fun snapshot(exitCode: Int? = rendererExitCode()): Map<String, Any> {
        val pending = if (closed.get()) "interrupted" else "validating"
        val guestGraphics = readStatus("graphics.status", if (gpuReady || options.graphics.requiresVulkan) pending else "software")
        val guestVulkan = readStatus("vulkan.status", if (options.graphics.requiresVulkan) pending else "off")
        val rendererExited = exitCode != null && (guestGraphics == "virgl" ||
            (options.graphics == GraphicsBackend.VENUS && guestGraphics == "zink"))
        return mapOf(
            "requested_audio" to options.audio.id, "audio_backend" to readStatus("audio.status", "starting"),
            "audio_output" to (audio?.diagnostics() ?: mapOf("output" to if (options.audio == AudioBackend.OFF) "off" else "unavailable")),
            "requested_graphics" to options.graphics.id, "host_graphics" to hostGpu,
            "graphics" to if (rendererExited) "renderer_exited" else guestGraphics,
            "vulkan" to if (exitCode != null && guestVulkan == "venus") "renderer_exited" else guestVulkan,
            "vulkan_presentation" to readStatus("vulkan.presentation", "unavailable"),
            "vulkan_probe_stage" to readStatus("vulkan.stage", "unavailable"),
            "vulkan_probe_supervisor" to readStatus("vulkan.supervisor", "unavailable"),
            "vulkan_probe_supervisor_log" to readBounded(File(directory, "vulkan.supervisor.log")),
            "graphics_probe" to readStatus("graphics.probe", if (gpuReady) "pending" else "skipped"),
            "host_gpu" to probe, "renderer_alive" to (renderer?.isAlive == true), "gpu_error" to gpuError,
            "guest_graphics_log" to readBounded(File(directory, "graphics.log")),
            "guest_vulkan_log" to readBounded(File(directory, "vulkan.log")),
            "guest_vulkaninfo_log" to readBounded(File(directory, "vulkaninfo.log")),
            "guest_venus_probe_log" to readBounded(File(directory, "venus-probe.log")),
            "guest_venus_probe_retry_log" to readBounded(File(directory, "venus-probe-retry.log")),
            "guest_zink_fallback_log" to readBounded(File(directory, "zink.log")),
            "guest_audio_log" to readBounded(File(directory, "audio.log")),
        ) + if (exitCode != null) mapOf("renderer_exit_code" to exitCode) else emptyMap()
    }
    private fun readStatus(name: String, fallback: String) = readBounded(File(directory, name)).trim().ifEmpty { fallback }
    override fun mute(muted: Boolean) { audio?.mute(muted) }
    private fun stopRenderer() {
        val process = renderer ?: return
        process.destroy()
        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(500, TimeUnit.MILLISECONDS)) { "vtest listener did not exit" }
        }
        runCatching { process.inputStream.close() }
        renderer = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val exitCode = rendererExitCode()
        audio?.close()
        runCatching { stopRenderer() }
        finalDiagnostics = snapshot(exitCode) + mapOf("closed" to true, "renderer_alive" to false)
        if (directoryOwned) runCatching {
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
                }
            })
        }
    }
    companion object {
        private fun key(id: String): String {
            require(Regex("[a-f0-9-]{36}").matches(id))
            return id.replace("-", "").take(16)
        }
        private fun readBounded(file: File): String = runCatching {
            file.inputStream().use { stream ->
                val bytes = ByteArray(4096)
                var size = 0
                while (size < bytes.size) { val n = stream.read(bytes, size, bytes.size - size); if (n < 0) break; size += n }
                String(bytes, 0, size, Charsets.UTF_8)
            }
        }.getOrDefault("")
        fun create(context: Context, runtimeBridge: File, sessionId: String, options: GuiOptions, log: (String) -> Unit): GuiMedia {
            val media = AndroidSessionMedia(context.applicationContext, File(runtimeBridge, key(sessionId)), sessionId, options, log)
            try { media.start(); return media }
            catch (error: Exception) { media.close(); throw error }
        }
    }
}
