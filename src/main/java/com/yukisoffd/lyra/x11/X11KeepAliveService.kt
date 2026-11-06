package com.yukisoffd.lyra.x11

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.Context
import android.provider.Settings

/** Gives user-started desktops explicit Android foreground lifetime and an explicit Stop action. */
class X11KeepAliveService : Service() {
    private var observation: AutoCloseable? = null
    private val main = Handler(Looper.getMainLooper())
    private var overlay: X11OverlayWindow? = null
    private var activityClass: String? = null
    override fun onCreate() {
        super.onCreate()
        overlay = pendingOverlay; pendingOverlay = null
        instance = this
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig); overlay?.configurationChanged()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.x11_title), NotificationManager.IMPORTANCE_LOW))
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.x11_title))
            .setContentText(getString(R.string.x11_notification))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.x11_stop_all), PendingIntent.getService(this, 1,
                Intent(this, X11KeepAliveService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        intent?.getStringExtra("activity")?.let { activityClass = it }
        activityClass?.let { className ->
            val resumeSession = intent?.getStringExtra("session") ?: overlay?.shownSessionId ?: sessions?.snapshot()?.lastOrNull()?.id
            val resume = Intent().setClassName(this, className).putExtra(X11Activity.EXTRA_SESSION, resumeSession)
            builder.setContentIntent(PendingIntent.getActivity(this, 0, resume,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        startForeground(7111, builder.build())
        if (intent?.action == "stop") { sessions?.stopAll(); stopSelf(); return START_NOT_STICKY }
        if (sessions == null) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == SHOW_OVERLAY) {
            val session = sessions?.snapshot()?.firstOrNull { it.id == intent.getStringExtra("session") }
            if (session != null && Settings.canDrawOverlays(this)) {
                if (overlay == null) overlay = X11OverlayWindow(this, sessions!!, activityClass.orEmpty())
                runCatching { if (overlay?.targetSessionId != session.id) overlay?.show(session) }.onFailure {
                    android.util.Log.e("LyraX11", "Unable to show GUI overlay", it)
                    android.widget.Toast.makeText(this, it.message ?: getString(R.string.x11_error), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        observation?.close()
        observation = sessions?.observe {
            main.post {
                overlay?.refresh()
                if (sessions?.hasRunningSessions() != true) stopSelf()
            }
        }
        // Session start follows the service request on the Activity main thread.
        main.postDelayed({ if (sessions?.hasRunningSessions() != true) stopSelf() }, 2000)
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        observation?.close()
        overlay?.close()
        overlay = null
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        sessions?.stopAll()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "lyra_x11_desktops"
        private const val SHOW_OVERLAY = "lyra_x11.show_overlay"
        @Volatile internal var sessions: GuiSessions? = null
        @Volatile private var instance: X11KeepAliveService? = null
        // Temporary owner uses applicationContext only; adoption, cancellation and failures clear it.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var pendingOverlay: X11OverlayWindow? = null
        fun prepareOverlay(context: Context, manager: GuiSessions, activityClass: String, sessionId: String) {
            check(Looper.myLooper() == Looper.getMainLooper()) { "GUI presentation must run on the main thread" }
            check(Settings.canDrawOverlays(context)) { "OVERLAY_PERMISSION_REQUIRED" }
            val target = manager.snapshot().firstOrNull { it.id == sessionId } ?: error("Unknown GUI session")
            check(target.status == GuiStatus.STARTING || target.status == GuiStatus.CONNECTED) { "GUI session has ended; create a new session" }
            val service = instance
            val window = service?.overlay ?: pendingOverlay ?: X11OverlayWindow(context.applicationContext, manager, activityClass)
            try { window.show(target) }
            catch (error: Exception) {
                if (service == null) { if (pendingOverlay === window) pendingOverlay = null; window.close() }
                throw error
            }
            if (service != null) service.overlay = window else pendingOverlay = window
        }
        fun start(context: Context, manager: GuiSessions, activityClass: String, overlaySession: String? = null) {
            if (overlaySession != null) check(Settings.canDrawOverlays(context)) { "OVERLAY_PERMISSION_REQUIRED" }
            if (overlaySession != null && (instance?.overlay ?: pendingOverlay)?.targetSessionId != overlaySession) {
                prepareOverlay(context, manager, activityClass, overlaySession)
            }
            sessions = manager
            val intent = Intent(context, X11KeepAliveService::class.java).putExtra("activity", activityClass)
            if (overlaySession != null) intent.setAction(SHOW_OVERLAY).putExtra("session", overlaySession)
            try { context.startForegroundService(intent) }
            catch (error: Exception) {
                if (instance == null) { pendingOverlay?.close(); pendingOverlay = null }
                throw error
            }
        }
        fun hideOverlay() {
            val action = {
                instance?.overlay?.hide()
                pendingOverlay?.close(); pendingOverlay = null
            }
            if (Looper.myLooper() == Looper.getMainLooper()) action() else Handler(Looper.getMainLooper()).post { action() }
        }
        fun overlaySessionId(): String? = instance?.overlay?.shownSessionId ?: pendingOverlay?.shownSessionId
    }
}
