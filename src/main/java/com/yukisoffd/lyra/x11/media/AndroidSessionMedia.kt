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
    private var renderer: Process? = null
    private var audio: PcmAudioOutput? = null
    private var hostGpu = "software"
    private var gpuError = ""
    private var probe = emptyMap<String, String>()
    private var gpuReady = false
    @Volatile private var finalDiagnostics: Map<String, Any>? = null
    override val guest: GuestMedia get() = GuestMedia("/run/lyra/${key(sessionId)}", gpuReady)

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
            try {
                for (asset in listOf("virgl-probe.py", "mpv-egl-refresh", "mpv-egl-refresh.c", "chromium-webgl")) {
                    context.assets.open("lyra_x11/$asset").use { input ->
                        File(directory, asset).outputStream().use { output -> input.copyTo(output) }
                    }
                }
                startRenderer()
            }
            catch (error: Exception) {
                gpuError = error.message ?: error.javaClass.simpleName
                renderer?.destroy(); renderer = null
                log("LYRA_GPU: $gpuError")
                if (options.graphics == GraphicsBackend.VIRGL) throw IllegalStateException("VirGL unavailable: $gpuError", error)
            }
        }
    }

    private fun startRenderer() {
        val binary = File(context.applicationInfo.nativeLibraryDir, "liblyra_virgl_exec.so")
        check(binary.isFile && binary.canExecute()) { "Bundled VirGL executable is unavailable for this ABI; reinstall the complete APK" }
        // Child probes are isolated from the application's hardware accelerated Canvas/EGL.
        val output = File(directory, "egl.json")
        val process = ProcessBuilder(binary.absolutePath, "--lyra-probe").redirectErrorStream(true).redirectOutput(output).start()
        try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Android EGL probe timed out" }
            check(process.exitValue() == 0) { "Android EGL probe failed: ${readBounded(output)}" }
            val lines = readBounded(output).lineSequence().firstOrNull { it.startsWith('{') } ?: error("Invalid EGL probe output")
            val info = JSONObject(lines)
            probe = listOf("renderer", "vendor", "version").associateWith { info.optString(it) }
            val name = probe.getValue("renderer").lowercase()
            check(listOf("swiftshader", "llvmpipe", "softpipe", "software rasterizer").none { it in name }) { "Android EGL selected software rendering: $name" }
        } finally { if (process.isAlive) process.destroyForcibly() }
        val socket = File(directory, "virgl")
        require(socket.absolutePath.toByteArray().size < 104) { "VirGL socket path exceeds Unix socket limit" }
        val server = ProcessBuilder(binary.absolutePath, "--use-egl-surfaceless", "--use-gles",
            "--socket-path", socket.absolutePath).redirectErrorStream(true).apply {
            environment().remove("LD_PRELOAD")
        }.start()
        renderer = server
        Thread({
            runCatching { server.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(1024)
                while (!closed.get()) {
                    val count = reader.read(chars); if (count < 0) break
                    log("VirGL: ${String(chars, 0, count)}")
                }
            } }
        }, "lyra-virgl-log").apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!socket.exists() && server.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
        check(server.isAlive && socket.exists()) { "VirGL server failed to create its private socket" }
        Os.chmod(socket.absolutePath, 384)
        gpuReady = true; hostGpu = "egl_gles_virgl"
        log("LYRA_GPU: Host renderer ready: ${probe["renderer"]}; validating guest Mesa virpipe next.")
    }

    override fun diagnostics(): Map<String, Any> = finalDiagnostics ?: snapshot()
    private fun rendererExitCode() = renderer?.let { runCatching(it::exitValue).getOrNull() }
    private fun snapshot(exitCode: Int? = rendererExitCode()): Map<String, Any> {
        val guestGraphics = readStatus("graphics.status", if (gpuReady) "validating" else "software")
        return mapOf(
            "requested_audio" to options.audio.id, "audio_backend" to readStatus("audio.status", "starting"),
            "audio_output" to (audio?.diagnostics() ?: mapOf("output" to if (options.audio == AudioBackend.OFF) "off" else "unavailable")),
            "requested_graphics" to options.graphics.id, "host_graphics" to hostGpu,
            "graphics" to if (exitCode != null && guestGraphics != "validation_failed") "renderer_exited" else guestGraphics,
            "graphics_probe" to readStatus("graphics.probe", if (gpuReady) "pending" else "skipped"),
            "host_gpu" to probe, "renderer_alive" to (renderer?.isAlive == true), "gpu_error" to gpuError,
            "guest_graphics_log" to readBounded(File(directory, "graphics.log")),
            "guest_audio_log" to readBounded(File(directory, "audio.log")),
        ) + if (exitCode != null) mapOf("renderer_exit_code" to exitCode) else emptyMap()
    }
    private fun readStatus(name: String, fallback: String) = readBounded(File(directory, name)).trim().ifEmpty { fallback }
    override fun mute(muted: Boolean) { audio?.mute(muted) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val exitCode = rendererExitCode()
        audio?.close()
        renderer?.let { process ->
            process.destroy()
            runCatching { if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly() }
            runCatching { process.inputStream.close() }
        }
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
