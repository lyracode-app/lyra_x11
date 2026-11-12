package com.yukisoffd.lyra.x11

import com.yukisoffd.lyra.x11.rfb.PixelBuffer
import com.yukisoffd.lyra.x11.rfb.RfbClient
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class GuiStatus { STARTING, CONNECTED, ERROR, STOPPED }

class GuiSession internal constructor(val id: String, val environment: GuiEnvironment,
    val display: Int, val options: GuiOptions) {
    @Volatile var status = GuiStatus.STARTING; internal set
    @Volatile internal var virglValidationFailed = false
    @Volatile var client: RfbClient? = null; internal set
    @Volatile var nativeDisplay: GuiDisplay? = null; internal set
    @Volatile internal var displayFallback = ""
    val input: GuiInput? get() = nativeDisplay ?: client
    val hasFrame: Boolean get() = nativeDisplay?.hasFrame == true || framebuffer != null
    fun displayDiagnostics(): Map<String, Any> = nativeDisplay?.diagnostics() ?: mapOf(
        "backend" to "vnc", "transport" to "private_unix_rfb", "requested_backend" to options.displayBackend.id,
        "fallback_reason" to displayFallback, "vnc_encoding" to true)
    @Volatile var framebuffer: PixelBuffer? = null; internal set
    @Volatile internal var visible = false; private set
    private val viewers = mutableSetOf<Any>()
    @Synchronized fun setViewerVisible(owner: Any, shown: Boolean) {
        if (shown) viewers += owner else viewers -= owner
        visible = viewers.isNotEmpty()
        client?.active = visible
    }
    @Volatile internal var process: GuiProcess? = null
    internal var graphicsProbe: GuiProcess? = null
    @Volatile internal var media: GuiMedia = GuiMedia.None
    fun mediaDiagnostics(): Map<String, Any> = media.diagnostics()
    @Volatile var audioMuted = false; private set
    @Synchronized fun muteAudio(muted: Boolean) { audioMuted = muted; media.mute(muted) }
    internal val closed = AtomicBoolean(false)
    internal val released = AtomicBoolean(false)
    private val log = StringBuilder()
    private val recentPointerEvents = ArrayDeque<String>(24)
    @Synchronized internal fun append(text: String) {
        log.append(text)
        if (log.length > 16384) log.delete(0, log.length - 16384)
    }
    @Synchronized internal fun tracePointer(message: String) {
        if (recentPointerEvents.size == 24) recentPointerEvents.removeFirst()
        recentPointerEvents.addLast("LYRA_INPUT: $message")
    }
    fun environmentExports(): String = "export DISPLAY=:$display\nexport XAUTHORITY=${nativeDisplay?.authority ?: "/tmp/lyra-x11-$id/Xauthority"}" +
        (media.guest?.let { "\n${it.exports}" } ?: "")
    @Synchronized fun logText(): String = buildString {
        append("${environmentExports()}\n\n${displayDiagnostics()}\n\n${mediaDiagnostics()}\n\n$log")
        recentPointerEvents.forEach { append('\n').append(it) }
    }
    val title: String get() = "${environment.name} :$display"
}

/** App-process lifetime. Each session owns its tracer, X server, authentication and framebuffer. */
class GuiSessions(val runtime: GuiRuntime) : AutoCloseable {
    private val sessions = CopyOnWriteArrayList<GuiSession>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "lyra-x11-session").apply { isDaemon = true } }
    private val deleteListener = runtime.beforeEnvironmentRemoved { environment ->
        val removed = synchronized(this) {
            sessions.filter { it.environment.id == environment }.also { sessions.removeAll(it.toSet()) }
        }
        removed.forEach(::stopNow)
        changed()
    }
    fun snapshot(): List<GuiSession> = sessions.toList()
    fun observe(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
    fun hasRunningSessions() = sessions.any { !it.closed.get() && (it.status == GuiStatus.STARTING || it.status == GuiStatus.CONNECTED) }
    private fun changed() { listeners.forEach { runCatching(it) } }

    @Synchronized fun start(environmentId: String, options: GuiOptions): GuiSession {
        val environment = runtime.environments().firstOrNull { it.id == environmentId }
            ?: error("Linux environment is unavailable")
        val active = sessions.filter { !it.released.get() }
        require(active.size < 4) { "At most four graphical sessions may run simultaneously" }
        require(active.sumOf { it.options.width.toLong() * it.options.height } + options.width.toLong() * options.height <= 16_588_800) {
            "Combined desktop memory limit reached; stop a session or use a smaller resolution"
        }
        val display = (100..999).firstOrNull { candidate -> sessions.none { !it.released.get() && it.display == candidate } }
            ?: error("No available X display")
        val session = GuiSession(UUID.randomUUID().toString(), environment, display, options)
        sessions += session
        executor.execute { runSession(session) }
        changed()
        return session
    }

    private fun runSession(session: GuiSession) {
        try {
            val process = synchronized(session) {
                if (session.closed.get()) return
                session.media = runtime.prepareMedia(session.environment.id, session.id, session.options) { session.append("$it\n"); changed() }
                if (session.audioMuted) session.media.mute(true)
                if(session.options.displayBackend != DisplayBackend.VNC) {
                    try {
                        session.nativeDisplay = runtime.prepareDisplay(session.environment.id, session.id, session.display, session.options) { session.append("$it\n"); changed() }
                        check(session.nativeDisplay != null || session.options.displayBackend != DisplayBackend.NATIVE) { "Native X11 is unavailable in this runtime" }
                    } catch(error: Exception) {
                        if(session.options.displayBackend == DisplayBackend.NATIVE) throw error
                        session.displayFallback = error.message ?: error.javaClass.simpleName
                        session.append("LYRA_DISPLAY: Native X11 unavailable; using VNC fallback: ${session.displayFallback}\n")
                    }
                }
                val command = GuestBootstrap.command(session.display, session.options, session.id, session.media.guest, session.nativeDisplay?.authority)
                runtime.launch(session.environment.id, session.id, command).also { session.process = it }
            }
            process.output.close()
            executor.execute {
                try {
                    process.input.bufferedReader().use { reader ->
                        val chars = CharArray(2048)
                        while (true) {
                            val n = reader.read(chars)
                            if (n < 0) break
                            session.append(String(chars, 0, n))
                            changed()
                        }
                    }
                } catch (_: Exception) { /* Closing the supervisor closes its log stream. */ }
            }
            if (session.options.graphics.requiresVulkan && session.media.guest != null) {
                probeGraphics(session, process)
            }
            if (session.closed.get()) return
            session.nativeDisplay?.let { display ->
                session.status = GuiStatus.CONNECTED
                changed()
                while(!session.closed.get()) {
                    check(process.isAlive) { "Linux graphical process exited. See session log." }
                    check(display.isAlive) { "Native X11 connection closed. See session log or select VNC fallback." }
                    Thread.sleep(250)
                }
                return
            }
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!session.closed.get()) {
                check(process.isAlive) { "Linux graphical process exited. See session log and installation instructions." }
                val transport = runtime.transport(session.id)
                val client = RfbClient(transport) { buffer ->
                    synchronized(session) {
                        if (!session.closed.get()) {
                            session.framebuffer = buffer
                            session.status = GuiStatus.CONNECTED
                        }
                    }
                    changed()
                }
                synchronized(session) {
                    if (session.closed.get()) { client.close(); return }
                    client.active = session.visible
                    session.client = client
                }
                try {
                    client.run()
                    break
                } catch (error: IOException) {
                    // Unix sockets may not exist while Xvnc is starting; never retry an established session.
                    if (session.status == GuiStatus.CONNECTED || System.nanoTime() >= deadline) throw error
                    Thread.sleep(100)
                } finally { client.close() }
            }
            if (!session.closed.get()) error("Display connection closed")
        } catch (error: Exception) {
            if (!session.closed.get()) {
                session.virglValidationFailed = session.mediaDiagnostics()["graphics"] == "validation_failed"
                val message = if (session.virglValidationFailed)
                    "LYRA_GPU: Guest VirGL startup validation failed. Retry or select software rendering."
                else error.message ?: error.javaClass.simpleName
                session.append("\n$message\n")
                session.status = GuiStatus.ERROR
            }
        } finally {
            synchronized(session) {
                session.closed.set(true)
                session.client?.close()
                session.graphicsProbe?.close()
                session.process?.close()
                session.nativeDisplay?.close()
                session.media.close()
                session.process = null
                session.graphicsProbe = null
                session.client = null
                session.framebuffer = null
                session.released.set(true)
            }
            changed()
        }
    }

    private fun probeGraphics(session: GuiSession, desktop: GuiProcess) {
        var result = "probe_not_started"
        var supervisorPid: Int? = null
        val probeOutput = StringBuffer()
        try {
            val readyDeadline = System.nanoTime() + 40_000_000_000L
            while (!session.closed.get() && desktop.isAlive && !session.media.graphicsProbeRequested &&
                System.nanoTime() < readyDeadline) Thread.sleep(50)
            if (!desktop.isAlive || !session.media.graphicsProbeRequested) {
                result = if (desktop.isAlive) "probe_not_requested" else "desktop_exited"
                return
            }
            val probe = synchronized(session) {
                if (session.closed.get()) return
                val command = GuestBootstrap.graphicsProbeCommand(session.display, session.id, checkNotNull(session.media.guest),
                    session.options.graphics, session.nativeDisplay?.authority)
                runtime.launchGraphicsProbe(session.environment.id, command)?.also { session.graphicsProbe = it }
            } ?: return
            probe.output.close()
            val logging = executor.submit {
                runCatching {
                    probe.input.bufferedReader().use { reader ->
                        val chars = CharArray(1024)
                        while (true) {
                            val size = reader.read(chars)
                            if (size < 0) break
                            probeOutput.append(chars, 0, size)
                            if (probeOutput.length > 4096) probeOutput.delete(0, probeOutput.length - 4096)
                        }
                    }
                }
            }
            val deadline = System.nanoTime() + 85_000_000_000L
            while (!session.closed.get() && desktop.isAlive && probe.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            result = when {
                !desktop.isAlive -> "desktop_exited"
                probe.isAlive -> "probe_timeout"
                else -> "exit_${probe.exitCode ?: "unknown"}"
            }
            if (!probe.isAlive) runCatching { logging.get(1, java.util.concurrent.TimeUnit.SECONDS) }
            supervisorPid = probe.supervisorPid
        } catch (error: Exception) {
            result = "probe_error"
            session.append("LYRA_GPU: Isolated graphics probe failed: ${error.message}\n")
        } finally {
            synchronized(session) {
                runCatching { session.graphicsProbe?.close() }.onFailure {
                    session.append("LYRA_GPU: Unable to close graphics probe: ${it.message}\n")
                }
                session.graphicsProbe = null
                if (!session.closed.get()) {
                    val output = "PRoot tracer pid=${supervisorPid ?: "unavailable"} result=$result\n$probeOutput"
                    if (output.isNotBlank()) session.append("Vulkan probe:\n$output\n")
                    session.append("LYRA_GPU: Isolated graphics probe finished ($result).\n")
                    runCatching { session.media.graphicsProbeFinished(result, output) }.onFailure {
                        session.append("LYRA_GPU: Unable to publish graphics probe result: ${it.message}\n")
                    }
                }
            }
            changed()
        }
    }

    fun stop(session: GuiSession) { executor.execute { stopNow(session); changed() } }
    fun remove(session: GuiSession) { executor.execute { stopNow(session); sessions.remove(session); changed() } }
    fun stopAll() { sessions.forEach(::stop); }

    private fun stopNow(session: GuiSession) {
        synchronized(session) {
            session.closed.set(true)
            session.client?.close()
            session.graphicsProbe?.close()
            session.process?.close()
            session.nativeDisplay?.close()
            session.media.close()
            session.process = null
            session.graphicsProbe = null
            session.client = null
            session.framebuffer = null
            session.status = GuiStatus.STOPPED
            session.released.set(true)
        }
    }

    override fun close() {
        sessions.forEach(::stopNow)
        sessions.clear()
        deleteListener.close()
        executor.shutdownNow()
        changed()
    }
}
