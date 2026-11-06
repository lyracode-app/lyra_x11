package com.yukisoffd.lyra.x11.media

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 48 kHz stereo S16LE FIFO -> normal Android media output (speaker/USB/Bluetooth). */
internal class PcmAudioOutput(private val context: Context, fifo: File, private val log: (String) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val queue = PcmQueue()
    private val received = AtomicLong()
    private val written = AtomicLong()
    @Volatile private var muted = false
    @Volatile private var state = "waiting"
    @Volatile private var error = ""
    @Volatile private var underruns = 0
    // Linux O_CLOEXEC exists on API 26 too; the Java constant was exposed in 27.
    private val fd = Os.open(fifo.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or
        (if (android.os.Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else 0x80000), 0)
    private val reader = Thread({ readLoop() }, "lyra-pcm-reader").apply { isDaemon = true; start() }
    private val player = Thread({ playLoop() }, "lyra-pcm-output").apply { isDaemon = true; start() }

    fun mute(value: Boolean) { muted = value; queue.clear(); if (!value) AudioFocusCoordinator.resume() }
    fun diagnostics(): Map<String, Any> = mapOf("output" to state, "muted" to muted, "sample_rate" to 48000,
        "channels" to 2, "received_frames" to received.get() / 4, "written_frames" to written.get() / 4,
        "dropped_frames" to queue.droppedBytes / 4, "queued_ms" to queue.queuedBytes() / 192,
        "underruns" to underruns, "error" to error)

    private fun readLoop() {
        val bytes = ByteArray(8192)
        val poll = StructPollfd().apply { fd = this@PcmAudioOutput.fd; events = OsConstants.POLLIN.toShort() }
        try {
            while (!closed.get()) {
                if (Os.poll(arrayOf(poll), 50) > 0 && poll.revents.toInt() and OsConstants.POLLIN != 0) {
                    try {
                        val count = Os.read(fd, bytes, 0, bytes.size)
                        if (count > 0) { received.addAndGet(count.toLong()); queue.offer(bytes, count) }
                        else Thread.sleep(10)
                    } catch (e: ErrnoException) { if (e.errno != OsConstants.EAGAIN && !closed.get()) throw e }
                } else Thread.sleep(10) // A FIFO without a writer reports HUP immediately.
            }
        } catch (e: Exception) { if (!closed.get()) fail(e) }
        finally { runCatching { Os.close(fd) } }
    }
    private fun playLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        var track: AudioTrack? = null
        val bytes = ByteArray(1920) // 10 ms, bounded pending write.
        var lastSignal = 0L
        try {
            while (!closed.get()) {
                val count = queue.read(bytes)
                if (count == 0) {
                    if (System.nanoTime() - lastSignal > 2_000_000_000L) {
                        AudioFocusCoordinator.volume(context, this, false)
                        track?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) { it.pause(); it.flush() } }
                        state = if (muted) "muted" else "idle"
                    }
                    Thread.sleep(5); continue
                }
                if ((0 until count).any { bytes[it] != 0.toByte() }) lastSignal = System.nanoTime()
                val playing = !muted && System.nanoTime() - lastSignal < 2_000_000_000L
                val volume = AudioFocusCoordinator.volume(context, this, playing)
                if (volume <= 0f) {
                    state = if (muted) "muted" else if (playing) "focus_suspended" else "idle"
                    track?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) { it.pause(); it.flush() } }
                    continue
                }
                if (track == null) {
                    val minimum = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                    check(minimum > 0) { "Android does not support 48 kHz stereo PCM" }
                    track = AudioTrack.Builder().setAudioAttributes(AudioFocusCoordinator.attributes)
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, 3840))
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
                    check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
                }
                val output = checkNotNull(track)
                output.setVolume(volume)
                if (output.playState != AudioTrack.PLAYSTATE_PLAYING) output.play()
                state = "playing"
                var offset = 0
                val deadline = System.nanoTime() + 30_000_000L
                while (offset < count && !closed.get() && !muted) {
                    val amount = output.write(bytes, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
                    if (amount == AudioTrack.ERROR_DEAD_OBJECT) {
                        output.release(); track = null; queue.clear(); break // Audio route/service recreation.
                    }
                    check(amount >= 0) { "AudioTrack write failed: $amount" }
                    offset += amount; written.addAndGet(amount.toLong())
                    if (amount == 0) { if (System.nanoTime() >= deadline) { queue.clear(); break }; Thread.sleep(2) }
                }
                underruns = track?.underrunCount ?: underruns
            }
        } catch (e: Exception) { if (!closed.get()) fail(e) }
        finally { runCatching { track?.pause() }; runCatching { track?.flush() }; track?.release(); AudioFocusCoordinator.remove(this) }
    }
    private fun fail(e: Exception) { error = e.message ?: e.javaClass.simpleName; state = "error"; log("LYRA_AUDIO: $error") }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reader.interrupt(); player.interrupt()
        // Reader polls with a short timeout, then owns its final close. Never close
        // a descriptor under a running read and risk reading a reused descriptor.
        if (Thread.currentThread() !== reader) runCatching { reader.join(200) }
        if (Thread.currentThread() !== player) runCatching { player.join(200) }
        queue.clear(); state = "closed"
    }
}
