package com.yukisoffd.lyra.x11.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** One focus request for all GUI sessions. Silent desktops do not take focus from music apps. */
internal object AudioFocusCoordinator {
    val attributes: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val active = mutableSetOf<Any>()
    private var manager: AudioManager? = null
    private var request: AudioFocusRequest? = null
    private var gain = 0f
    private var permanentLoss = false
    private var requested = false
    private var lastAttempt = 0L
    @Synchronized fun volume(context: Context, owner: Any, playing: Boolean): Float {
        if (playing) active += owner else active -= owner
        if (active.isEmpty()) { abandon(); return 0f }
        if (manager == null) manager = context.applicationContext.getSystemService(AudioManager::class.java)
        if (request == null) request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change -> synchronized(this) {
                gain = when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> 1f
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> 0.2f
                    else -> 0f
                }
                if (change == AudioManager.AUDIOFOCUS_LOSS) { permanentLoss = true; requested = false }
            } }, Handler(Looper.getMainLooper())).build()
        val now = System.nanoTime()
        if (!requested && !permanentLoss && now - lastAttempt > 1_000_000_000L) {
            lastAttempt = now
            requested = runCatching { manager!!.requestAudioFocus(request!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)
            gain = if (requested) 1f else 0f
        }
        return gain
    }
    @Synchronized fun resume() { permanentLoss = false; lastAttempt = 0 }
    @Synchronized fun remove(owner: Any) { active -= owner; if (active.isEmpty()) abandon() }
    private fun abandon() {
        request?.let { runCatching { manager?.abandonAudioFocusRequest(it) } }
        requested = false; gain = 0f; permanentLoss = false
    }
}
