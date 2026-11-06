package com.yukisoffd.lyracode.x11

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.yukisoffd.lyra.x11.DesktopPresets
import com.yukisoffd.lyra.x11.GuiOptions
import com.yukisoffd.lyra.x11.GuiLaunchRequest
import com.yukisoffd.lyra.x11.GuiPreferences
import com.yukisoffd.lyra.x11.GuiSession
import com.yukisoffd.lyra.x11.GuiStatus
import com.yukisoffd.lyra.x11.GuiToolSpec
import com.yukisoffd.lyra.x11.X11KeepAliveService
import com.yukisoffd.lyra.x11.X11Screenshots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** AI tool integration stays with the GUI module, independent of agent-provider implementations. */
internal class LinuxGuiToolHandler(context: Context) {
    private val context = context.applicationContext
    private val sessions get() = LyraX11Activity.getSessions(context)
    private val profiles = GuiPreferences(context)
    fun commandForApproval(args: JSONObject): String? = if (args.optString("action") == "start") {
        GuiLaunchRequest.parse(args, profiles.load(args.optString("linux_id"))).options.desktopCommand
    } else null

    suspend fun execute(args: JSONObject): JSONObject {
        val action = args.getString("action")
        return when (action) {
            "guide" -> inventory().put("specification", GuiToolSpec.text(context))
                .put("presets", JSONArray(DesktopPresets.entries.map { JSONObject().put("id", it.id).put("command", it.command) }))
            "list" -> inventory()
            "status" -> args.optString("session_id").takeIf(String::isNotBlank)?.let { describe(session(it)) } ?: inventory()
            "request_overlay_permission" -> {
                if (Settings.canDrawOverlays(context)) JSONObject().put("status", "granted")
                else {
                    withContext(Dispatchers.Main) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    JSONObject().put("status", "user_action_required").put("note", "Requested Android's overlay permission page. The user must grant permission; check list again before starting a preview. If Android keeps Lyra in the background, use the floating display button inside Lyra to open the permission page.")
                }
            }
            "start" -> start(args)
            "show" -> {
                val target = session(args.getString("session_id"))
                check(target.status == GuiStatus.CONNECTED || target.status == GuiStatus.STARTING) { "Session has ended; create a new session" }
                show(target, args.optString("mode", "overlay"))
                describe(target)
            }
            "hide" -> {
                withContext(Dispatchers.Main) { X11KeepAliveService.hideOverlay() }
                JSONObject().put("status", "hidden").put("note", "GUI sessions continue running.")
            }
            "stop" -> {
                val target = session(args.getString("session_id"))
                sessions.remove(target)
                JSONObject().put("status", "stopping").put("session_id", target.id)
            }
            "audio" -> {
                val target = session(args.getString("session_id"))
                require(args.has("muted")) { "audio action requires muted=true or false" }
                target.muteAudio(args.getBoolean("muted"))
                describe(target)
            }
            "screenshot" -> {
                val target = session(args.getString("session_id"))
                val capture = withContext(Dispatchers.IO) { X11Screenshots.save(context, target) }
                JSONObject().put("status", "saved").put("uri", capture.uri).put("width", capture.width).put("height", capture.height)
                    .put("note", "Captured the live Linux framebuffer directly. The PNG is saved on the phone; no shell screenshot command was run.")
            }
            else -> error("Unknown linux_gui action: $action")
        }
    }

    private fun inventory(): JSONObject {
        val all = sessions.snapshot()
        val active = all.filter { it.status == GuiStatus.STARTING || it.status == GuiStatus.CONNECTED }
        val recent = all.filterNot { it in active }.takeLast(8)
        return JSONObject().put("schema", "lyra_linux_gui_v4")
        .put("transport", "private_unix_socket").put("vnc_password_required", false)
        .put("overlay_permission", Settings.canDrawOverlays(context))
        .put("environments", JSONArray(sessions.runtime.environments().map { JSONObject().put("linux_id", it.id).put("name", it.name) }))
        .put("session_count", all.size)
        .put("sessions", JSONArray((active + recent).map { describe(it, includeLog = false) }))
    }

    private fun session(id: String) = sessions.snapshot().firstOrNull { it.id == id } ?: error("Unknown GUI session_id; call linux_gui action=list")

    private fun describe(target: GuiSession, includeLog: Boolean = true): JSONObject = JSONObject()
        .put("session_id", target.id).put("linux_id", target.environment.id).put("display", ":${target.display}")
        .put("status", target.status.name.lowercase()).put("has_frame", target.hasFrame)
        .put("width", target.options.width).put("height", target.options.height).put("fps_limit", target.options.framesPerSecond)
        .put("command", target.options.desktopCommand).put("app_preview", target.options.appMode)
        .put("overlay_visible", X11KeepAliveService.overlaySessionId() == target.id)
        .put("environment_exports", target.environmentExports())
        .put("media", JSONObject(target.mediaDiagnostics()))
        .put("display_backend", JSONObject(target.displayDiagnostics()))
        .apply { if (includeLog) put("log", target.logText()) }

    private suspend fun start(args: JSONObject): JSONObject {
        val request = GuiLaunchRequest.parse(args, profiles.load(args.getString("linux_id")))
        val environmentId = request.environmentId
        val mode = request.mode
        if (mode == "overlay" && !Settings.canDrawOverlays(context)) return permissionRequired()
        val options = request.options
        val target = withContext(Dispatchers.Main) {
            if (mode == "fullscreen") X11KeepAliveService.start(context, sessions, LyraX11Activity::class.java.name)
            sessions.start(environmentId, options).also { profiles.save(environmentId, options) }
        }
        try { show(target, mode) }
        catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.Main) {
                if (mode == "overlay") X11KeepAliveService.hideOverlay()
                sessions.stop(target)
            }
            if (error is CancellationException) throw error
            throw IllegalStateException("${error.message}\n${target.logText()}", error)
        }
        val deadline = System.nanoTime() + 12_000_000_000L
        while (target.status == GuiStatus.STARTING && System.nanoTime() < deadline) delay(100)
        return describe(target).put("ok", target.status in setOf(GuiStatus.STARTING, GuiStatus.CONNECTED))
    }
    private suspend fun show(target: GuiSession, mode: String) {
        require(mode in setOf("overlay", "fullscreen")) { "mode must be overlay or fullscreen" }
        try {
            withContext(Dispatchers.Main) {
                if (mode == "overlay") {
                    check(Settings.canDrawOverlays(context)) { "OVERLAY_PERMISSION_REQUIRED: request_overlay_permission first" }
                    // Android 15+ requires an already visible overlay to start an FGS from the background.
                    X11KeepAliveService.prepareOverlay(context, sessions, LyraX11Activity::class.java.name, target.id)
                } else LyraX11Activity.openSession(context, target.id)
            }
            if (mode == "overlay") {
                val deadline = System.nanoTime() + 2_000_000_000L
                while (X11KeepAliveService.overlaySessionId() != target.id && System.nanoTime() < deadline) delay(50)
                check(X11KeepAliveService.overlaySessionId() == target.id) { "Overlay could not be displayed; check Android overlay permission and foreground state" }
                withContext(Dispatchers.Main) {
                    check(X11KeepAliveService.overlaySessionId() == target.id) { "Overlay was closed before presentation completed" }
                    X11KeepAliveService.start(context, sessions, LyraX11Activity::class.java.name, target.id)
                }
            }
        } catch (error: Exception) {
            if (mode == "overlay") withContext(NonCancellable + Dispatchers.Main) { X11KeepAliveService.hideOverlay() }
            throw error
        }
    }
    private fun permissionRequired(): JSONObject = JSONObject().put("ok", false).put("status", "overlay_permission_required")
        .put("note", "No GUI session was started. Call request_overlay_permission, let the user grant Android overlay access, then retry start.")
}
