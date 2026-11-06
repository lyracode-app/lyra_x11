package com.yukisoffd.lyracode.x11

import android.content.Context
import android.content.Intent
import com.yukisoffd.lyra.x11.GuiEnvironment
import com.yukisoffd.lyra.x11.GuiProcess
import com.yukisoffd.lyra.x11.GuiRuntime
import com.yukisoffd.lyra.x11.GuiSessions
import com.yukisoffd.lyra.x11.GuiOptions
import com.yukisoffd.lyra.x11.media.AndroidSessionMedia
import com.yukisoffd.lyra.x11.nativebackend.NativeDisplay
import com.yukisoffd.lyra.x11.X11Activity
import com.yukisoffd.lyra.x11.GuestBootstrap
import com.yukisoffd.lyra.x11.UnixRfbTransport
import com.yukisoffd.lyracode.debian.ProotCommandProcess
import com.yukisoffd.lyracode.debian.ProotLinuxManager
import com.yukisoffd.lyracode.debian.ProotOperationPhase
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Host glue is compiled with :app, so PRoot implementation details remain internal to :app. */
class LyraX11Activity : X11Activity() {
    override fun obtainSessions(): GuiSessions = getSessions(applicationContext)

    companion object {
        @Volatile private var sessions: GuiSessions? = null
        internal fun getSessions(context: Context): GuiSessions = sessions ?: synchronized(this) {
            sessions ?: GuiSessions(ProotGuiRuntime(context.applicationContext)).also { sessions = it }
        }
        fun open(context: Context, environmentId: String) {
            context.startActivity(Intent(context, LyraX11Activity::class.java).putExtra(EXTRA_ENVIRONMENT, environmentId))
        }
        internal fun openSession(context: Context, sessionId: String) {
            context.startActivity(Intent(context, LyraX11Activity::class.java)
                .putExtra(EXTRA_SESSION, sessionId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private class ProotGuiRuntime(private val context: Context) : GuiRuntime {
    private val manager = ProotLinuxManager.getInstance(context)
    override fun environments(): List<GuiEnvironment> =
        if (manager.state.value.phase == ProotOperationPhase.DELETING) emptyList()
        else manager.activeInstances().map { GuiEnvironment(it.id, it.name) }
    override fun beforeEnvironmentRemoved(listener: (String) -> Unit) = manager.addBeforeDeleteListener(listener)
    override fun prepareMedia(environmentId: String, sessionId: String, options: GuiOptions, log: (String) -> Unit) =
        AndroidSessionMedia.create(context, manager.runtimeBridgeDirectory(environmentId), sessionId, options, log)
    override fun prepareDisplay(environmentId: String, sessionId: String, display: Int, options: GuiOptions, log: (String) -> Unit) =
        NativeDisplay.create(context, manager.runtimeBridgeDirectory(environmentId), sessionId, display, options, log)
    private fun bridge(sessionId: String): File {
        require(Regex("[a-f0-9-]{36}").matches(sessionId))
        return File(context.cacheDir, "lx/${sessionId.replace("-", "").take(16)}")
    }
    override fun transport(sessionId: String) = UnixRfbTransport(File(bridge(sessionId), "rfb").absolutePath)

    override fun launch(environmentId: String, sessionId: String, command: String): GuiProcess {
        require(Regex("[a-f0-9-]{36}").matches(sessionId))
        val rootfs = manager.instance(environmentId).rootfsDir
        val privateDirectory = bridge(sessionId)
        check(privateDirectory.mkdirs()) { "Unable to create private GUI bridge" }
        android.system.Os.chmod(privateDirectory.absolutePath, 448) // 0700
        val guestDirectory = File(rootfs, GuestBootstrap.socketDirectory(sessionId).removePrefix("/"))
        try {
            val parent = guestDirectory.parentFile!!.toPath().toRealPath()
            check(parent.startsWith(rootfs.toPath().toRealPath())) { "Linux /tmp must resolve inside its rootfs" }
            check(guestDirectory.mkdir()) { "Unable to create Linux GUI bridge mount point" }
        } catch (error: Exception) { privateDirectory.delete(); throw error }
        val executionId = ids.getAndDecrement()
        val started = try {
            manager.startCommand(environmentId, null, "/root", "exec /bin/sh -c ${GuestBootstrap.quote(command)}",
                executionId, background = false, extraBinds = listOf(privateDirectory to GuestBootstrap.socketDirectory(sessionId)), mergeError = true)
        } catch (error: Exception) { guestDirectory.delete(); privateDirectory.delete(); throw error }
        // startCommand keeps detached descendants. This handle retains and explicitly stops its exact tracer.
        return ProotGuiProcess(manager, started, rootfs, sessionId, privateDirectory)
    }
    companion object { private val ids = AtomicLong(-System.currentTimeMillis()) }
}

private class ProotGuiProcess(private val manager: ProotLinuxManager, private val started: ProotCommandProcess,
    private val rootfs: File, private val sessionId: String, private val bridge: File) : GuiProcess {
    private val closed = AtomicBoolean(false)
    override val input: InputStream get() = started.process.inputStream
    override val output: OutputStream get() = started.process.outputStream
    override val isAlive: Boolean get() = !closed.get() && started.process.isAlive
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val process = started.process
        // The system-shell launcher execs PRoot with the same PID; fall back if its PID file was not yet written.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        var pid: Int? = null
        while (process.isAlive && System.nanoTime() < deadline) {
            pid = runCatching { started.supervisorPidFile.readText().trim().toInt() }.getOrNull()?.takeIf { it > 1 }
            if (pid != null) break
            Thread.sleep(5)
        }
        manager.terminateCommandProcess(process, pid)
        runCatching { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        started.completionFile.delete()
        started.supervisorPidFile.delete()
        // PRoot's forced shutdown may bypass the guest shell trap. Never follow guest symlinks while cleaning up.
        runCatching {
            val root = rootfs.toPath().toRealPath()
            val temp = File(rootfs, "tmp").toPath().toRealPath()
            if (!temp.startsWith(root)) return@runCatching
            val directory = temp.resolve("lyra-x11-$sessionId")
            if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return@runCatching
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
                }
            })
        }
        runCatching { File(rootfs, GuestBootstrap.socketDirectory(sessionId).removePrefix("/")).delete() }
        runCatching {
            Files.walkFileTree(bridge.toPath(), object : SimpleFileVisitor<Path>() {
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
}
