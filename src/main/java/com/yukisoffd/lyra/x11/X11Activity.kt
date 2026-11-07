package com.yukisoffd.lyra.x11

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Landscape desktop with controls overlaid on the framebuffer, collapsed after connection. */
abstract class X11Activity : Activity() {
    protected abstract fun obtainSessions(): GuiSessions
    private lateinit var sessions: GuiSessions
    private lateinit var profiles: GuiPreferences
    private lateinit var display: X11DisplayView
    private lateinit var controls: LinearLayout
    private lateinit var toggle: Button
    private lateinit var sessionPicker: Spinner
    private lateinit var environmentPicker: Spinner
    private lateinit var configuration: LinearLayout
    private lateinit var command: AutoCompleteTextView
    private lateinit var resolution: Spinner
    private lateinit var fps: Spinner
    private lateinit var audio: Spinner
    private lateinit var graphics: Spinner
    private lateinit var displayBackend: Spinner
    private lateinit var status: TextView
    private var observation: AutoCloseable? = null
    private var signature = ""
    private var selectedId: String? = null
    private var boundEnvironment: String? = null
    private var pendingOverlay: String? = null
    private var environments = emptyList<GuiEnvironment>()
    private var listed = emptyList<GuiSession>()
    private val connectedSeen = mutableSetOf<String>()
    private var sizes = emptyList<String>()
    private var rates = emptyList<Int>()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sessions = obtainSessions()
        profiles = GuiPreferences(this)
        selectedId = savedInstanceState?.getString("session") ?: intent.getStringExtra(EXTRA_SESSION)
        pendingOverlay = savedInstanceState?.getString("pending_overlay")
        connectedSeen += sessions.snapshot().filter { it.status == GuiStatus.CONNECTED }.map { it.id }
        val root = FrameLayout(this)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        display = X11DisplayView(this)
        root.addView(display, FrameLayout.LayoutParams(-1, -1))
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xf021252d.toInt())
        }
        val toolbar = LinearLayout(this)
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; textSize = 12f; setOnClickListener { action() }
        }
        toolbar.addView(button(getString(R.string.x11_hide_controls)) { expand(false) })
        toolbar.addView(button(getString(R.string.x11_back)) { finish() })
        toolbar.addView(button(getString(R.string.x11_new)) {
            refreshEnvironments(); configuration.visibility = View.VISIBLE; expand(true)
        })
        toolbar.addView(button(getString(R.string.x11_keyboard)) { expand(false); display.showKeyboard() })
        toolbar.addView(button("Esc") { display.sendSpecial(0xff1b) })
        toolbar.addView(button("Tab") { display.sendSpecial(0xff09) })
        toolbar.addView(button("Ctrl+C") { display.sendChord(0xffe3, 'c'.code) })
        toolbar.addView(button("Alt+Tab") { display.sendChord(0xffe9, 0xff09) })
        toolbar.addView(button(getString(R.string.x11_overlay)) { display.session?.let { showOverlay(it.id) } })
        toolbar.addView(button(getString(R.string.x11_screenshot)) { capture() })
        toolbar.addView(button(getString(R.string.x11_audio_mute)) { display.session?.let { it.muteAudio(true) } })
        toolbar.addView(button(getString(R.string.x11_audio_resume)) { display.session?.let { it.muteAudio(false) } })
        toolbar.addView(button(getString(R.string.x11_media_status)) { showText(getString(R.string.x11_media_status), "${display.session?.displayDiagnostics()}\n\n${display.session?.mediaDiagnostics()}") })
        toolbar.addView(button(getString(R.string.x11_log)) { showText(getString(R.string.x11_log), display.session?.logText().orEmpty()) })
        toolbar.addView(button(getString(R.string.x11_help)) { showText(getString(R.string.x11_help), GuiToolSpec.text(this)) })
        toolbar.addView(button(getString(R.string.x11_stop)) { display.session?.let(sessions::remove) })
        controls.addView(HorizontalScrollView(this).apply { addView(toolbar) })
        sessionPicker = Spinner(this)
        controls.addView(sessionPicker)
        configuration = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
        environmentPicker = Spinner(this).apply { contentDescription = getString(R.string.x11_environment) }
        configuration.addView(environmentPicker)
        val commandRow = LinearLayout(this)
        command = AutoCompleteTextView(this).apply {
            setSingleLine(); threshold = 1; hint = getString(R.string.x11_desktop_command)
        }
        commandRow.addView(command, LinearLayout.LayoutParams(0, -2, 1f))
        commandRow.addView(button(getString(R.string.x11_presets)) { choosePreset() })
        configuration.addView(commandRow)
        val row = LinearLayout(this)
        resolution = Spinner(this).apply { contentDescription = getString(R.string.x11_resolution) }
        fps = Spinner(this).apply { contentDescription = getString(R.string.x11_frame_rate) }
        row.addView(resolution, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(fps, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button(getString(R.string.x11_start)) { startDesktop() })
        configuration.addView(row)
        val mediaRow = LinearLayout(this)
        audio = Spinner(this).apply { adapter = adapter(resources.getStringArray(R.array.x11_audio_modes).toList()) }
        graphics = Spinner(this).apply { adapter = adapter(resources.getStringArray(R.array.x11_graphics_modes).toList()) }
        mediaRow.addView(audio, LinearLayout.LayoutParams(0, -2, 1f))
        mediaRow.addView(graphics, LinearLayout.LayoutParams(0, -2, 1f))
        configuration.addView(mediaRow)
        displayBackend = Spinner(this).apply { adapter = adapter(resources.getStringArray(R.array.x11_display_modes).toList()) }
        configuration.addView(displayBackend)
        configuration.addView(TextView(this).apply { text = getString(R.string.x11_intro); textSize = 12f })
        controls.addView(configuration)
        status = TextView(this).apply { setPadding(dp(8), 0, dp(8), dp(4)); textSize = 12f }
        controls.addView(status)
        root.addView(ScrollView(this).apply { addView(controls); isFillViewport = false }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        toggle = button("≡") { expand(true) }.apply {
            contentDescription = getString(R.string.x11_show_controls)
            setTextColor(Color.WHITE); setBackgroundColor(0x991c2430.toInt())
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0)
        }
        root.addView(toggle, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END))
        setContentView(root)
        refreshEnvironments()
        val matching = sessions.snapshot().lastOrNull { it.environment.id == intent.getStringExtra(EXTRA_ENVIRONMENT) }
        if (selectedId == null) selectedId = matching?.id
        refresh()
        val requested = sessions.snapshot().firstOrNull { it.id == selectedId }
        val showForm = intent.getStringExtra(EXTRA_SESSION) == null && matching == null && savedInstanceState == null
        configuration.visibility = if (showForm || requested == null || requested.virglValidationFailed) View.VISIBLE else View.GONE
        expand(savedInstanceState?.getBoolean("expanded") ?: (showForm || requested?.status != GuiStatus.CONNECTED))
        sessionPicker.onItemSelectedListener = selected { position ->
            listed.getOrNull(position)?.let {
                if (selectedId != it.id) {
                    selectedId = it.id; display.session = it; configuration.visibility = View.GONE
                    if (it.status == GuiStatus.CONNECTED) expand(false)
                    if (it.virglValidationFailed) reopenGraphicsProfile(it)
                    updateStatus()
                }
                updateVisibility()
            }
        }
        environmentPicker.onItemSelectedListener = selected { position ->
            environments.getOrNull(position)?.id?.let { if (it != boundEnvironment) loadProfile(it) }
        }
        immersive()
    }

    private fun selected(action: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
    }
    private fun adapter(items: List<String>) = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
    private fun refreshEnvironments() {
        val before = environments.getOrNull(environmentPicker.selectedItemPosition)?.id
        environments = sessions.runtime.environments()
        environmentPicker.adapter = adapter(environments.map { it.name })
        val preferred = before ?: intent.getStringExtra(EXTRA_ENVIRONMENT)
        val position = environments.indexOfFirst { it.id == preferred }.coerceAtLeast(0)
        if (environments.isNotEmpty()) {
            environmentPicker.setSelection(position)
            if (boundEnvironment != environments[position].id) loadProfile(environments[position].id)
        }
    }
    private fun loadProfile(environmentId: String) {
        boundEnvironment = environmentId
        val profile = profiles.load(environmentId)
        command.setText(profile.desktopCommand, false)
        command.setAdapter(adapter((profiles.history(environmentId) + DesktopPresets.entries.map { it.command }).distinct()))
        sizes = (listOf("1280×720", "1600×900", "1920×1080", "1024×768", "2560×1440", "3840×2160") + "${profile.width}×${profile.height}").distinct()
        resolution.adapter = adapter(sizes)
        resolution.setSelection(sizes.indexOf("${profile.width}×${profile.height}"))
        rates = (listOf(30, 60, 90, 120, 15) + profile.framesPerSecond).distinct()
        fps.adapter = adapter(rates.map { getString(R.string.x11_fps_value, it) }); fps.setSelection(rates.indexOf(profile.framesPerSecond))
        audio.setSelection(profile.audio.ordinal); graphics.setSelection(profile.graphics.ordinal)
        displayBackend.setSelection(profile.displayBackend.ordinal)
    }
    private fun choosePreset() {
        val history = boundEnvironment?.let(profiles::history).orEmpty().filter { recent -> DesktopPresets.entries.none { it.command == recent } }
        val labels = DesktopPresets.entries.map { "${it.title} · ${it.command}" } + history
        val commands = DesktopPresets.entries.map { it.command } + history
        AlertDialog.Builder(this).setTitle(R.string.x11_presets).setItems(labels.toTypedArray()) { _, position ->
            command.setText(commands[position], false); command.setSelection(command.length())
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun startDesktop() {
        try {
            val environment = environments.getOrNull(environmentPicker.selectedItemPosition) ?: error(getString(R.string.x11_no_environment))
            val size = sizes[resolution.selectedItemPosition].split('×').map(String::toInt)
            val options = GuiOptions(size[0], size[1], rates[fps.selectedItemPosition], command.text.toString(),
                audio = AudioBackend.entries[audio.selectedItemPosition], graphics = GraphicsBackend.entries[graphics.selectedItemPosition],
                displayBackend = DisplayBackend.entries[displayBackend.selectedItemPosition])
            X11KeepAliveService.start(this, sessions, javaClass.name)
            val session = sessions.start(environment.id, options)
            profiles.save(environment.id, options)
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(command.windowToken, 0)
            command.clearFocus()
            selectedId = session.id; configuration.visibility = View.GONE
            refresh()
        } catch (error: Exception) { showText(getString(R.string.x11_error), error.message.orEmpty()) }
    }
    private fun refresh() {
        if (isFinishing || isDestroyed) return
        val current = sessions.snapshot()
        val newSignature = current.joinToString { "${it.id}:${it.status}" }
        if (newSignature != signature) {
            signature = newSignature; listed = current
            sessionPicker.adapter = adapter(current.map { "${it.title} · ${statusName(it.status)}" })
            val index = current.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: current.lastIndex
            if (index >= 0) {
                sessionPicker.setSelection(index); selectedId = current[index].id; display.session = current[index]
                if (current[index].status == GuiStatus.CONNECTED && connectedSeen.add(current[index].id)) expand(false)
                if (current[index].status == GuiStatus.ERROR) {
                    if (current[index].virglValidationFailed) reopenGraphicsProfile(current[index])
                    expand(true)
                }
            } else {
                selectedId = null; display.session = null; configuration.visibility = View.VISIBLE; expand(true)
            }
            updateStatus()
            updateVisibility()
        }
        display.postInvalidateOnAnimation()
    }
    private fun reopenGraphicsProfile(session: GuiSession) {
        val position = environments.indexOfFirst { it.id == session.environment.id }
        if (position < 0) return
        environmentPicker.setSelection(position)
        if (boundEnvironment != session.environment.id) loadProfile(session.environment.id)
        configuration.visibility = View.VISIBLE
    }
    private fun updateStatus() {
        val current = display.session
        status.text = current?.let {
            val summary = if (it.virglValidationFailed) getString(R.string.x11_virgl_retry) else statusName(it.status)
            "${it.title} · $summary"
        }.orEmpty()
        status.visibility = if (current?.status in listOf(null, GuiStatus.CONNECTED)) View.GONE else View.VISIBLE
    }
    private fun statusName(value: GuiStatus) = getString(when (value) {
        GuiStatus.STARTING -> R.string.x11_starting; GuiStatus.CONNECTED -> R.string.x11_connected
        GuiStatus.ERROR -> R.string.x11_failed; GuiStatus.STOPPED -> R.string.x11_stopped
    })
    private fun expand(expanded: Boolean) {
        controls.visibility = if (expanded) View.VISIBLE else View.GONE
        toggle.visibility = if (expanded) View.GONE else View.VISIBLE
        if (!expanded) display.requestFocus()
    }
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
    private fun updateVisibility() {
        sessions.snapshot().forEach { it.setViewerVisible(this, hasWindowFocus() && it.id == selectedId) }
        val rate = display.session?.takeUnless { it.closed.get() }?.options?.framesPerSecond ?: 0
        val preferredRate = if (rate > 60 && hasWindowFocus()) rate.toFloat() else 0f
        val attributes = window.attributes
        if (attributes.preferredRefreshRate != preferredRate) {
            attributes.preferredRefreshRate = preferredRate
            window.attributes = attributes
        }
    }
    private fun showOverlay(sessionId: String) {
        if (!Settings.canDrawOverlays(this)) {
            pendingOverlay = sessionId
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        try { X11KeepAliveService.start(this, sessions, javaClass.name, sessionId); finish() }
        catch (error: Exception) { showText(getString(R.string.x11_error), error.message.orEmpty()) }
    }
    private fun capture() {
        val target = display.session ?: return
        Thread({
            val result = runCatching { X11Screenshots.save(applicationContext, target) }
            runOnUiThread { Toast.makeText(this, result.fold({ getString(R.string.x11_screenshot_saved) }, { it.message }), Toast.LENGTH_SHORT).show() }
        }, "lyra-x11-capture").apply { isDaemon = true; start() }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::sessions.isInitialized) { updateVisibility(); if (hasFocus) immersive() }
    }
    override fun onStart() {
        super.onStart(); observation = sessions.observe { runOnUiThread { refresh() } }
        display.refreshFramebuffer(); refreshEnvironments(); refresh()
    }
    override fun onResume() {
        super.onResume()
        pendingOverlay?.takeIf { Settings.canDrawOverlays(this) }?.let { pendingOverlay = null; showOverlay(it) }
    }
    override fun onStop() {
        observation?.close(); observation = null; display.releaseInput()
        sessions.snapshot().forEach { it.setViewerVisible(this, false) }
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("session", selectedId); outState.putBoolean("expanded", controls.visibility == View.VISIBLE)
        outState.putString("pending_overlay", pendingOverlay); super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { observation?.close(); display.session = null; super.onDestroy() }
    private fun showText(title: String, text: String) {
        val view = TextView(this).apply { this.text = text; setTextIsSelectable(true); setPadding(dp(16), dp(12), dp(16), dp(12)) }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(view) }).setPositiveButton(android.R.string.ok, null).show()
    }
    companion object {
        const val EXTRA_ENVIRONMENT = "lyra_x11.environment"
        const val EXTRA_SESSION = "lyra_x11.session"
    }
}
