package com.yukisoffd.lyra.x11.media

import android.app.Activity
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.sin
import com.yukisoffd.lyra.x11.AudioBackend
import com.yukisoffd.lyra.x11.GraphicsBackend
import com.yukisoffd.lyra.x11.GuiOptions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

class MediaTestActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text = "Lyra media instrumentation" })
    }
}

@RunWith(AndroidJUnit4::class)
class NativeMediaTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun duplicateMediaPreparationCannotDeleteAnExistingSession() {
        val bridge = File(context.cacheDir, "media-owner-${System.nanoTime().toString(16)}").apply { mkdir() }
        val token = "b8992841-0b1a-448f-a5da-b110dc0e64fd"
        val options = GuiOptions(audio = AudioBackend.OFF, graphics = GraphicsBackend.SOFTWARE)
        val media = AndroidSessionMedia.create(context, bridge, token, options) {}
        val owned = File(bridge, "b89928410b1a448f")
        val marker = File(owned, "keep").apply { writeText("owned") }
        try {
            assertThrows(IllegalStateException::class.java) { AndroidSessionMedia.create(context, bridge, token, options) {} }
            assertEquals("owned", marker.readText())
        } finally { media.close(); bridge.delete() }
        assertFalse(owned.exists())
    }

    @Test fun nativeEglAndVtestCapsWorkInTheApplicationUid() {
        val binary = File(context.applicationInfo.nativeLibraryDir, "liblyra_virgl_exec.so")
        assertTrue("Native PIE must be extracted and executable", binary.canExecute())
        val probeLog = File(context.cacheDir, "egl-test.json")
        val probe = ProcessBuilder(binary.absolutePath, "--lyra-probe").redirectErrorStream(true).redirectOutput(probeLog).start()
        try {
            assertTrue(probe.waitFor(10, TimeUnit.SECONDS))
            val output = probeLog.readText()
            assertEquals(output, 0, probe.exitValue())
            val info = JSONObject(output.lineSequence().first { it.startsWith('{') })
            assertTrue(info.getString("renderer").isNotBlank())
            android.util.Log.i("LyraMediaTest", "EGL_PROBE_OK $info")
        } finally { probe.destroyForcibly(); probeLog.delete() }
        val socket = File(context.cacheDir, "vtest-test")
        val log = File(context.cacheDir, "vtest-test.log")
        val server = ProcessBuilder(binary.absolutePath, "--use-egl-surfaceless", "--use-gles",
            "--socket-path", socket.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!socket.exists() && server.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(log.readText(), server.isAlive && socket.exists())
            fun words(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                .apply { values.forEach(::putInt) }.array()
            fun LocalSocket.begin() {
                connect(LocalSocketAddress(socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                soTimeout = 5000
                val name = "lyra-test\u0000".toByteArray()
                outputStream.write(words(name.size, 8)) // VCMD_CREATE_RENDERER
                outputStream.write(name)
                outputStream.write(words(1, 11, 3)) // VCMD_PROTOCOL_VERSION
                outputStream.flush()
                val input = DataInputStream(inputStream)
                assertEquals(1, Integer.reverseBytes(input.readInt()))
                assertEquals(11, Integer.reverseBytes(input.readInt()))
                assertEquals(3, Integer.reverseBytes(input.readInt()))
            }
            fun LocalSocket.checkCaps() {
                outputStream.write(words(0, 1)) // VCMD_GET_CAPS
                outputStream.flush()
                val input = DataInputStream(inputStream)
                val length = Integer.reverseBytes(input.readInt())
                assertEquals(1, Integer.reverseBytes(input.readInt()))
                assertTrue("caps size=$length", length in 2..8192)
                val caps = ByteArray(length - 1)
                input.readFully(caps)
                assertTrue(caps.any { it != 0.toByte() })
            }
            fun workerPids() = Regex("LYRA_VTEST_WORKER_START pid=(\\d+)").findAll(log.readText())
                .map { it.groupValues[1].toInt() }.toList()
            LocalSocket().use { healthy ->
                healthy.begin()
                healthy.outputStream.write(words(1, 6, 0)) // VCMD_SUBMIT_CMD with VIRGL_CCMD_NOP
                healthy.checkCaps()
                LocalSocket().use { faulty ->
                    faulty.begin()
                    faulty.checkCaps()
                    // A CLEAR header without the required payload fails in the decoder.
                    faulty.outputStream.write(words(1, 6, 7))
                    faulty.outputStream.flush()
                    assertEquals(-1, faulty.inputStream.read())
                    assertTrue(log.readText().contains("VTEST_CLIENT_ERROR_COMMAND_DISPATCH"))
                }
                val previousWorkers = workerPids()
                LocalSocket().use { crashing ->
                    crashing.begin()
                    crashing.checkCaps()
                    val pid = (workerPids() - previousWorkers.toSet()).single()
                    Os.kill(pid, OsConstants.SIGKILL)
                    assertEquals(-1, crashing.inputStream.read())
                    val exit = "LYRA_VTEST_WORKER_EXIT pid=$pid signal=${OsConstants.SIGKILL}"
                    val reaped = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (exit !in log.readText() && System.nanoTime() < reaped) Thread.sleep(20)
                    assertTrue(log.readText(), exit in log.readText())
                }
                healthy.outputStream.write(words(1, 6, 0))
                healthy.checkCaps()
                assertTrue(server.isAlive)
                LocalSocket().use { reconnect ->
                    reconnect.begin()
                    reconnect.checkCaps()
                }
                server.destroy()
                assertTrue(server.waitFor(2, TimeUnit.SECONDS))
                assertEquals(-1, healthy.inputStream.read())
            }
            android.util.Log.i("LyraMediaTest", "VTEST_CLIENT_ISOLATION_OK")
        } catch (error: Exception) { throw AssertionError(log.readText(), error) }
        finally { server.destroy(); if (!server.waitFor(2, TimeUnit.SECONDS)) server.destroyForcibly(); socket.delete(); log.delete() }
    }

    @Test fun fifoFeedsRealAudioTrackAndCloses() {
        val activity = instrumentation.startActivitySync(Intent(context, MediaTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val fifo = File(context.cacheDir, "pcm-test")
        Os.mkfifo(fifo.absolutePath, 384)
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val player = PcmAudioOutput(context, fifo, messages::add)
        try {
            val writer = Os.open(fifo.absolutePath, OsConstants.O_WRONLY, 0)
            try {
                val tone = ByteBuffer.allocate(1920).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until 480) { val sample = (10000 * sin(2 * Math.PI * 400 * i / 48000)).toInt().toShort(); tone.putShort(sample).putShort(sample) }
                repeat(100) { Os.write(writer, tone.array(), 0, 1920); Thread.sleep(10) }
                assertTrue(messages.toString() + player.diagnostics(), (player.diagnostics()["written_frames"] as Long) > 12000)
                player.mute(true); Thread.sleep(50)
                val before = player.diagnostics()["written_frames"]
                repeat(10) { Os.write(writer, tone.array(), 0, 1920); Thread.sleep(10) }
                assertEquals(before, player.diagnostics()["written_frames"])
                player.mute(false)
                repeat(20) { Os.write(writer, tone.array(), 0, 1920); Thread.sleep(10) }
                assertTrue((player.diagnostics()["written_frames"] as Long) > before as Long)
                android.util.Log.i("LyraMediaTest", "AUDIO_TRACK_OK ${player.diagnostics()}")
            } finally { Os.close(writer) }
        } finally { player.close(); fifo.delete(); instrumentation.runOnMainSync { activity.finish() } }
        assertEquals("closed", player.diagnostics()["output"])
    }
}
