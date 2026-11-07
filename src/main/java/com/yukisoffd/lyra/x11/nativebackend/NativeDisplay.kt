package com.yukisoffd.lyra.x11.nativebackend

import android.content.Context
import android.graphics.Bitmap
import android.system.Os
import android.view.Surface
import android.view.Choreographer
import android.os.Handler
import android.os.Looper
import com.yukisoffd.lyra.x11.*
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipInputStream
import kotlin.concurrent.read
import kotlin.concurrent.write

/** A Bionic X.Org DDX outside PRoot; shared AHardwareBuffer -> EGLImage -> Android Surface. */
class NativeDisplay private constructor(private val context: Context, private val bridge: File,
    private val sessionId: String, private val display: Int, private val options: GuiOptions,
    private val log: (String) -> Unit) : GuiDisplay {
    private val key = sessionId.replace("-", "").take(16)
    private val directory = File(bridge, "$key/xs")
    private val controlDirectory = File(context.cacheDir, "lxs/$key")
    private val closed = AtomicBoolean(false)
    private val lifetime = ReentrantReadWriteLock()
    private var handle = 0L
    private var server: Process? = null
    private var owned = false
    private var controlOwned = false
    private val commands = ArrayBlockingQueue<() -> Unit>(4096)
    private val pointerQueue = PointerQueue<Unit>({ command -> enqueue { command(Unit) } }) { _, x, y, mask ->
        lifetime.read { if (handle != 0L) nativePointer(handle, x, y, mask) }
    }
    private var writer: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private val owners = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val clockRunning = AtomicBoolean(false)
    private val framePending = AtomicBoolean(false)
    private val pacer=FramePacer(options.framesPerSecond)
    private val frameClock = object : Choreographer.FrameCallback {
        override fun doFrame(time: Long) {
            if(closed.get() || owners.isEmpty()) { clockRunning.set(false); return }
            if(pacer.frame(time) && framePending.compareAndSet(false, true)) {
                commands.offer {} // Wake the writer on vsync, even at 120 Hz.
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    @Volatile private var finalDiagnostics: Map<String, Any>? = null
    override val authority get() = "/run/lyra/$key/xs/auth"
    override val isAlive get() = !closed.get() && server?.isAlive == true && lifetime.read { handle != 0L && nativeAlive(handle) }
    override val hasFrame get() = lifetime.read { handle != 0L && nativeStats(handle)[0] > 0 }

    private fun start() {
        require(Regex("[a-f0-9-]{36}").matches(sessionId))
        try { loadLibrary() } catch(error: LinkageError) { throw IllegalStateException("Native X11 library unavailable for this ABI",error) }
        check(directory.mkdir()) { "Native display directory already exists" }; owned = true
        Os.chmod(directory.path, 448)
        controlDirectory.parentFile!!.mkdirs()
        check(controlDirectory.mkdir()) { "Native control directory already exists" }; controlOwned = true
        Os.chmod(controlDirectory.path, 448)
        val socketDirectory = File(bridge, ".X11-unix")
        check(socketDirectory.isDirectory || socketDirectory.mkdir())
        Os.chmod(socketDirectory.path, 448)
        val socket = File(controlDirectory, "c")
        require(socket.path.toByteArray().size < 104 && File(socketDirectory, "X$display").path.toByteArray().size < 104)
        val auth = File(directory, "auth")
        DataOutputStream(auth.outputStream()).use { out ->
            fun field(bytes: ByteArray) { out.writeShort(bytes.size); out.write(bytes) }
            out.writeShort(65535) // FamilyWild handles both PRoot hostname and Android hostname.
            field(byteArrayOf()); field(display.toString().toByteArray()); field("MIT-MAGIC-COOKIE-1".toByteArray())
            field(ByteArray(16).also { SecureRandom().nextBytes(it) })
        }
        Os.chmod(auth.path, 384)
        val binary = File(context.applicationInfo.nativeLibraryDir, "liblyra_xserver_exec.so")
        check(binary.canExecute()) { "Bundled native X server is missing for this ABI" }
        val xkb = installXkb(context)
        server = ProcessBuilder(binary.path, ":$display", "-nolisten", "tcp", "-auth", auth.path, "-noreset", "-dpi", "96").apply {
            redirectErrorStream(true)
            environment().apply {
                remove("LD_PRELOAD"); put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
                put("TMPDIR", bridge.path); put("XKB_CONFIG_ROOT", xkb.path)
                put("LYRA_X11_CONTROL", socket.path); put("LYRA_X11_WIDTH", options.width.toString())
                put("LYRA_X11_HEIGHT", options.height.toString()); put("LYRA_X11_FPS", options.framesPerSecond.toString())
            }
        }.start()
        val process = server!!
        Thread({ runCatching { process.inputStream.bufferedReader().use { reader ->
            val text = CharArray(2048)
            while (!closed.get()) { val n = reader.read(text); if(n < 0) break; log("X11: ${String(text, 0, n)}") }
        } } }, "lyra-xserver-log").apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (process.isAlive && (!socket.exists() || !File(socketDirectory, "X$display").exists()) && System.nanoTime() < deadline) Thread.sleep(20)
        check(process.isAlive && socket.exists() && File(socketDirectory,"X$display").exists()) { "Native X server did not start; inspect X11 log" }
        handle = nativeCreate(socket.path, options.width, options.height)
        check(handle != 0L) { "Native EGL renderer/control connection failed" }
        val bufferDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
        while(nativeAlive(handle) && nativeStats(handle)[8]==0L && System.nanoTime()<bufferDeadline) Thread.sleep(10)
        check(nativeAlive(handle) && nativeStats(handle)[8]>0L) { "Native X11 initial shared buffer was not received" }
        writer = Thread({
            var nextIdleTick = 0L
            try {
                while (!closed.get()) {
                    commands.poll(if(owners.isEmpty()) 250 else 8, TimeUnit.MILLISECONDS)?.invoke()
                    val now = System.nanoTime()
                    if(framePending.getAndSet(false) || (owners.isEmpty() && now >= nextIdleTick)) {
                        nextIdleTick = now + 500_000_000L
                        lifetime.read { if(handle != 0L) nativeFrame(handle) }
                    }
                }
            } catch (_: InterruptedException) { /* Session shutdown. */ }
        }, "lyra-x11-input").apply { isDaemon = true; start() }
        log("LYRA_DISPLAY: Native X11 ready; AHardwareBuffer/EGLImage rendering, VNC bypassed.")
    }

    private fun enqueue(command: () -> Unit) {
        if (!closed.get() && !commands.offer(command)) {
            log("LYRA_DISPLAY: Input queue overflow; closing session to release held keys")
            Thread { close() }.start()
        }
    }
    override fun key(keysym: Int, down: Boolean) = enqueue { lifetime.read { if(handle != 0L) nativeKey(handle, keysym, down) } }
    override fun scanCode(code: Int, down: Boolean) = enqueue { lifetime.read { if (handle != 0L) nativeScanCode(handle, code, down) } }
    override fun text(text: String) {
        val points = text.codePoints().limit(4096).toArray()
        enqueue { lifetime.read { if(handle != 0L) for(point in points) {
            val symbol = when(point) { 10,13 -> 0xff0d; 9 -> 0xff09; in 0x20..0xff -> point; else -> 0x01000000 or point }
            nativeKey(handle,symbol,true); nativeKey(handle,symbol,false)
        } } }
    }
    override fun pointer(x: Int, y: Int, mask: Int, motionOnly: Boolean) {
        pointerQueue.pointer(x.coerceIn(0, options.width - 1), y.coerceIn(0, options.height - 1), mask, motionOnly)
    }
    override fun scroll(x: Int, y: Int, mask: Int, horizontal: Float, vertical: Float) {
        pointerQueue.ordered {
            lifetime.read {
                if (handle != 0L) {
                    nativeScroll(handle, x.coerceIn(0, options.width - 1), y.coerceIn(0, options.height - 1), horizontal, vertical)
                }
            }
        }
    }
    override fun surface(id: Long, surface: Surface?) {
        lifetime.read { if(handle != 0L) nativeSurface(handle,id,surface) }
        if(surface == null) owners.remove(id) else owners.add(id)
        if(surface != null) enqueue {} // Wake a renderer whose background input poll is idle.
        if(!closed.get() && owners.isNotEmpty() && clockRunning.compareAndSet(false,true)) main.post {
            pacer.reset(); Choreographer.getInstance().postFrameCallback(frameClock)
        }
    }
    override fun capture(): Bitmap = lifetime.read {
        val data = if(handle != 0L) nativeCapture(handle) else null
        check(data != null) { "GUI_FRAME_NOT_READY: show the native display before capturing" }
        Bitmap.createBitmap(data,2,data[0],data[0],data[1],Bitmap.Config.ARGB_8888)
    }
    override fun diagnostics(): Map<String, Any> = finalDiagnostics ?: lifetime.read {
        val stats = if(handle != 0L) nativeStats(handle) else LongArray(12)
        mapOf("backend" to "native", "transport" to when { stats[1]>0 -> "ahardwarebuffer_eglimage"; stats[2]>0 -> "shared_memory_texture_upload"; else -> "waiting_for_surface" },
            "requested_backend" to options.displayBackend.id, "server_alive" to (server?.isAlive == true),
            "presented_frames" to stats[0], "ahb_frames" to stats[1], "uploaded_frames" to stats[2],
            "gpu_mirrors" to stats[3], "screenshot_readbacks" to stats[4], "surfaces" to stats[5], "renderer_stopped" to (stats[6]!=0L),
            "frame_clock" to "android_choreographer", "received_events" to stats[7], "received_buffers" to stats[8], "surface_available" to stats[9],
            "draw_requested" to stats[10], "waiting_for_vsync" to stats[11], "vnc_encoding" to false)
    }
    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        finalDiagnostics = diagnostics() + mapOf("server_alive" to false,"renderer_stopped" to true,"surfaces" to 0L,"closed" to true)
        commands.clear(); writer?.interrupt()
        owners.clear();main.post { Choreographer.getInstance().removeFrameCallback(frameClock) }
        lifetime.write { if(handle != 0L) { nativeClose(handle); handle = 0L } }
        server?.let { it.destroy(); if(!it.waitFor(1,TimeUnit.SECONDS)) it.destroyForcibly(); runCatching { it.inputStream.close() } }
        // Guest-visible directories may have been replaced by symlinks. Only unlink our own entries.
        if(owned && !Files.isSymbolicLink(directory.parentFile!!.toPath())) {
            if(!Files.isSymbolicLink(directory.toPath())) File(directory,"auth").delete()
            directory.delete()
        }
        if(controlOwned) {
            if(!Files.isSymbolicLink(controlDirectory.toPath())) File(controlDirectory,"c").delete()
            controlDirectory.delete()
        }
        // Only this display's stale filesystem entries. Never remove another session's socket.
        if(owned) {
            if(!Files.isSymbolicLink(File(bridge,".X11-unix").toPath())) File(bridge,".X11-unix/X$display").delete()
            File(bridge,".X$display-lock").delete()
        }
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    fun setRendererViewport(x: Int,y: Int,w: Int,h: Int,left: Float,top: Float,width: Float,height: Float) = Unit
    private external fun nativeCreate(socket: String,width: Int,height: Int): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeAlive(handle: Long): Boolean
    private external fun nativeFrame(handle: Long)
    private external fun nativeSurface(handle: Long,id: Long,surface: Surface?)
    private external fun nativeKey(handle: Long,symbol: Int,down: Boolean)
    private external fun nativeScanCode(handle: Long, code: Int, down: Boolean)
    private external fun nativePointer(handle: Long,x: Int,y: Int,mask: Int)
    private external fun nativeScroll(handle: Long, x: Int, y: Int, horizontal: Float, vertical: Float)
    private external fun nativeStats(handle: Long): LongArray
    private external fun nativeCapture(handle: Long): IntArray?

    companion object {
        @Synchronized private fun loadLibrary() = System.loadLibrary("lyra_xserver")
        fun create(context: Context,bridge: File,sessionId: String,display: Int,options: GuiOptions,log: (String)->Unit): NativeDisplay {
            val instance=NativeDisplay(context.applicationContext,bridge,sessionId,display,options,log)
            try { instance.start(); return instance } catch(error: Throwable) { instance.close(); throw error }
        }
        @Synchronized private fun installXkb(context: Context): File {
            val directory=File(context.filesDir,"lyra_x11/xkb-v1")
            val marker=File(directory,"complete")
            if(marker.isFile) return File(directory,"xkb")
            check(directory.isDirectory || directory.mkdirs())
            ZipInputStream(context.assets.open("lyra_x11/xkb-data.zip")).use { input ->
                while(true) {
                    val entry=input.nextEntry ?: break
                    val file=File(directory,entry.name).canonicalFile
                    require(file.toPath().startsWith(directory.canonicalFile.toPath()))
                    if(entry.isDirectory) file.mkdirs() else {
                        file.parentFile!!.mkdirs(); file.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            check(File(directory,"xkb/rules/evdev").length()>0)
            marker.writeText("1")
            return File(directory,"xkb")
        }
    }
}
