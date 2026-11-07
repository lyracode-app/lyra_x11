package com.yukisoffd.lyra.x11

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Service-owned, movable/resizable native GUI preview. No accessibility or screen capture permission required. */
internal class X11OverlayWindow(private val context: Context, private val sessions: GuiSessions,
    private val activityClass: String) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "lyra-x11-capture").apply { isDaemon = true } }
    private var root: LinearLayout? = null
    private var display: X11DisplayView? = null
    private var title: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var session: GuiSession? = null
    private var minimized = false
    @Volatile var shownSessionId: String? = null; private set
    val targetSessionId: String? get() = session?.id
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // Window coordinates use physical screen pixels, matching the drag gesture's rawX/rawY.
    @android.annotation.SuppressLint("RtlHardcoded")
    private fun layout(width: Int, height: Int) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    // This focus hook does not consume taps; X11DisplayView handles performClick and Linux input.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun focusOnTouch(view: X11DisplayView) {
        view.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_DOWN) focus(); false }
    }

    fun show(target: GuiSession) {
        hide()
        val metrics = context.resources.displayMetrics
        val width = minOf((metrics.widthPixels * .92f).toInt(), dp(900))
        val height = minOf((width.toFloat() * target.options.height / target.options.width).toInt() + dp(48), metrics.heightPixels - dp(40))
        val layout = layout(width, height).apply {
            x = (metrics.widthPixels - width) / 2; y = dp(48)
            if (target.options.framesPerSecond > 60) preferredRefreshRate = target.options.framesPerSecond.toFloat()
        }
        val panel = object : LinearLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    this@X11OverlayWindow.display?.releaseInput()
                    this@X11OverlayWindow.display?.let { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(it.windowToken, 0) }
                    layout.flags = layout.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    update(); return true
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff171b23.toInt()) }
        val header = LinearLayout(context)
        val label = object : Button(context) { override fun performClick(): Boolean = super.performClick() }.apply {
            text = target.title; setTextColor(-1); textSize = 12f
            gravity = Gravity.CENTER_VERTICAL; setSingleLine(); setPadding(dp(8), 0, 0, 0)
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setBackgroundColor(android.graphics.Color.TRANSPARENT)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { focus() }
        }
        header.addView(label, LinearLayout.LayoutParams(0, dp(34), 1f))
        fun button(text: String, description: Int, action: () -> Unit) {
            header.addView(Button(context).apply {
                this.text = text; textSize = 12f; minWidth = 0; minimumWidth = 0
                minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0)
                contentDescription = context.getString(description)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
        }
        button("⌨", R.string.x11_keyboard) { focus(); display?.showKeyboard() }
        button("♫", R.string.x11_audio_toggle) { target.muteAudio(!target.audioMuted) }
        button("▣", R.string.x11_screenshot) { capture() }
        button("−", R.string.x11_minimize) {
            minimized = !minimized
            display?.visibility = if (minimized) android.view.View.GONE else android.view.View.VISIBLE
            layout.height = if (minimized) dp(48) else minOf((layout.width.toFloat() * target.options.height / target.options.width).toInt() + dp(48), context.resources.displayMetrics.heightPixels)
            target.setViewerVisible(this, !minimized)
            update()
        }
        button("↗", R.string.x11_fullscreen) {
            context.startActivity(Intent().setClassName(context, activityClass)
                .putExtra(X11Activity.EXTRA_SESSION, target.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hide()
        }
        button("×", R.string.x11_stop) { sessions.remove(target); hide() }
        panel.addView(header)
        val view = X11DisplayView(context).apply { session = target }
        focusOnTouch(view)
        panel.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        val grip = object : Button(context) { override fun performClick(): Boolean = super.performClick() }.apply {
            text = "◢"; setTextColor(0xffb0bac8.toInt()); textSize = 12f; gravity = Gravity.END
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = context.getString(R.string.x11_resize)
            setOnClickListener {
                if (!minimized) {
                    layout.width = minOf(layout.width + dp(40), context.resources.displayMetrics.widthPixels)
                    configurationChanged()
                }
            }
        }
        panel.addView(grip, LinearLayout.LayoutParams(-1, dp(14)))
        var startX = 0f; var startY = 0f; var originX = 0; var originY = 0
        label.setOnTouchListener { handle, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; originX = layout.x; originY = layout.y }
                MotionEvent.ACTION_MOVE -> {
                    val bounds = context.resources.displayMetrics
                    layout.x = (originX + event.rawX - startX).toInt().coerceIn(0, maxOf(0, bounds.widthPixels - layout.width))
                    layout.y = (originY + event.rawY - startY).toInt().coerceIn(0, maxOf(0, bounds.heightPixels - dp(48)))
                    update()
                }
                MotionEvent.ACTION_UP -> if (kotlin.math.abs(event.rawX - startX) < dp(4) && kotlin.math.abs(event.rawY - startY) < dp(4)) handle.performClick()
            }
            true
        }
        var initialWidth = width
        grip.setOnTouchListener { handle, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; initialWidth = layout.width }
                MotionEvent.ACTION_MOVE -> if (!minimized) {
                    val bounds = context.resources.displayMetrics
                    layout.width = (initialWidth + event.rawX - startX).toInt().coerceIn(minOf(dp(240), bounds.widthPixels), bounds.widthPixels)
                    configurationChanged()
                }
                MotionEvent.ACTION_UP -> if (kotlin.math.abs(event.rawX - startX) < dp(4)) handle.performClick()
            }
            true
        }
        root = panel; display = view; params = layout; session = target; title = label
        try { windows.addView(panel, layout) }
        catch (error: Exception) { hide(); throw error }
        var firstDraw = true
        val observer = object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (!firstDraw) return
                firstDraw = false
                if (root === panel && session === target) shownSessionId = target.id
                panel.post { if (panel.viewTreeObserver.isAlive) panel.viewTreeObserver.removeOnDrawListener(this) }
            }
        }
        panel.viewTreeObserver.addOnDrawListener(observer)
        target.setViewerVisible(this, true)
        refresh()
    }
    private fun update() { root?.let { panel -> params?.let { runCatching { windows.updateViewLayout(panel, it) } } } }
    private fun focus() {
        params?.let { it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() }
        update(); display?.requestFocus()
    }
    fun refresh() {
        val target = session ?: return
        if (sessions.snapshot().none { it.id == target.id }) { hide(); return }
        title?.text = if (target.status == GuiStatus.STARTING) context.getString(R.string.x11_starting_title, target.title) else target.title
        display?.postInvalidateOnAnimation()
    }
    fun configurationChanged() {
        val target = session ?: return
        val layout = params ?: return
        val bounds = context.resources.displayMetrics
        layout.width = minOf(layout.width, bounds.widthPixels)
        layout.height = if (minimized) dp(48) else minOf((layout.width.toFloat() * target.options.height / target.options.width).toInt() + dp(48), bounds.heightPixels)
        layout.x = layout.x.coerceIn(0, maxOf(0, bounds.widthPixels - layout.width))
        layout.y = layout.y.coerceIn(0, maxOf(0, bounds.heightPixels - dp(48)))
        update()
    }
    private fun capture() {
        val target = session ?: return
        io.execute {
            val result = runCatching { X11Screenshots.save(context, target) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, result.fold({ context.getString(R.string.x11_screenshot_saved) },
                    { it.message ?: context.getString(R.string.x11_error) }), Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun hide() {
        session?.setViewerVisible(this, false)
        display?.session = null
        root?.let { runCatching { windows.removeViewImmediate(it) } }
        root = null; display = null; params = null; title = null; session = null; shownSessionId = null; minimized = false
    }
    fun close() { hide(); io.shutdown() }
}
