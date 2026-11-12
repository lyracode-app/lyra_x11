package com.yukisoffd.lyra.x11

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicLong
import android.view.PointerIcon
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.yukisoffd.lyra.x11.rfb.CursorImage
import kotlin.math.abs
import kotlin.math.roundToInt

/** GPU-composited Android View. The Linux renderer remains a separate, guest-side concern. */
class X11DisplayView(context: Context) : FrameLayout(context) {
    private val surfaceId = nextSurfaceId.incrementAndGet()
    private val tracePointerEvents = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var attachedDisplay: GuiDisplay? = null
    private var surfaceReady = false
    private val nativeSurface = SurfaceView(context).apply {
        isFocusable = false; isClickable = false
        pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
        visibility = GONE
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; syncNativeSurface() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                attachedDisplay?.surface(surfaceId, holder.surface); updateDestination(); invalidate()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false; attachedDisplay?.surface(surfaceId, null); attachedDisplay = null
            }
        })
    }
    private fun syncNativeSurface() {
        val target = session?.nativeDisplay?.takeIf { session?.closed?.get() == false }
        nativeSurface.visibility = if(target != null) VISIBLE else GONE
        if(attachedDisplay !== target) {
            attachedDisplay?.surface(surfaceId, null); attachedDisplay = null
            if(target != null && surfaceReady && isAttachedToWindow) {
                attachedDisplay = target; target.surface(surfaceId,nativeSurface.holder.surface)
            }
        }
    }
    private fun updateDestination() {
        val target = session ?: return
        val w = target.framebuffer?.width ?: target.options.width
        val h = target.framebuffer?.height ?: target.options.height
        val scale = minOf(width.toFloat()/w,height.toFloat()/h)
        val left = (width-w*scale)/2; val top = (height-h*scale)/2
        destination.set(left,top,left+w*scale,top+h*scale)
    }
    override fun onInterceptTouchEvent(event: MotionEvent) = true
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncNativeSurface(); watchMouseCapture(); invalidate() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        watchMouseCapture()
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh);updateDestination() }
    companion object {
        private val nextSurfaceId = AtomicLong()
        private const val MOUSE_BUTTON_HISTORY_SIZE = 16
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fallbackCursor = Path().apply {
        moveTo(0f, 0f); lineTo(3f, 18f); lineTo(8f, 12f); lineTo(15f, 12f); close()
    }
    private var bitmap: Bitmap? = null
    private var bitmapRevision = -1L
    private var cursorBitmap: Bitmap? = null
    private var cursorImage: CursorImage? = null
    private val destination = RectF()
    private data class PressedKey(val keysym: Int?, val scanCode: Int = 0) {
        fun send(client: GuiInput, down: Boolean) {
            if (scanCode != 0 && client is GuiDisplay) client.scanCode(scanCode, down)
            else keysym?.let { client.key(it, down) }
        }
    }
    private data class MouseButtonReport(
        val deviceId: Int,
        val eventTime: Long,
        val buttons: Int,
        val press: Boolean,
        val generic: Boolean,
    )

    private val pressed = mutableMapOf<Long, PressedKey>()
    private val mouseButtonHistory = ArrayDeque<MouseButtonReport>(MOUSE_BUTTON_HISTORY_SIZE)
    private var buttonMask = 0
    private var pointerX = 0
    private var pointerY = 0
    private var scrollY = 0f
    private var dragging = false
    private var multiTouch = false
    private var touchInProgress = false
    private var touchLocked = false
    private var touchLookMoved = false
    private var touchX = 0f
    private var touchY = 0f
    private val touchSlopSquared = ViewConfiguration.get(context).scaledTouchSlop.toFloat().let { it * it }
    private var pendingAccent = 0
    private var pendingKeyboard = false
    private var mouseCaptureRequested = false
    private val mouseCapturePoll = object : Runnable {
        override fun run() {
            val target = session
            // The X server can grab or ungrab the pointer without changing the framebuffer.
            val watch = isAttachedToWindow && isShown && windowVisibility == VISIBLE && isFocused && hasWindowFocus() &&
                target != null && !target.closed.get() &&
                (target.nativeDisplay != null || target.status == GuiStatus.STARTING && target.options.displayBackend != DisplayBackend.VNC)
            setMouseCapture(watch && target?.nativeDisplay?.pointerLocked == true)
            if (watch) postDelayed(this, 50L)
        }
    }

    private fun watchMouseCapture() {
        removeCallbacks(mouseCapturePoll)
        mouseCapturePoll.run()
    }

    private fun setMouseCapture(capture: Boolean) {
        if (mouseCaptureRequested == capture) return
        mouseCaptureRequested = capture
        if (capture) requestPointerCapture() else releasePointerCapture()
    }

    var session: GuiSession? = null
        set(value) {
            if (field === value) return
            releaseInput()
            attachedDisplay?.surface(surfaceId,null); attachedDisplay = null
            field = value
            syncNativeSurface(); updateDestination(); watchMouseCapture()
            bitmap = null
            bitmapRevision = -1L
            cursorBitmap = null
            cursorImage = null
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(this)
            invalidate()
        }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { if (!multiTouch && !touchLookMoved) performClick(); return true }
        override fun onLongPress(e: MotionEvent) {
            if (touchInProgress && !dragging && !multiTouch && session?.nativeDisplay?.pointerLocked != true) click(4)
        }
        override fun onScroll(first: MotionEvent?, current: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!touchLocked && !multiTouch && current.pointerCount == 1) {
                if (!dragging) { dragging = true; buttonMask = 1; sendPointer() }
                move(current.x, current.y)
            }
            return true
        }
    })

    init {
        setWillNotDraw(false)
        addView(nativeSurface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
        contentDescription = context.getString(R.string.x11_display_description)
        // Hide Android's large system pointer; draw the Linux cursor at framebuffer scale instead.
        pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    }

    fun showKeyboard() {
        requestFocus()
        pendingKeyboard = true
        post { openPendingKeyboard() }
    }
    private fun openPendingKeyboard() {
        if (pendingKeyboard && hasWindowFocus() && isAttachedToWindow) {
            pendingKeyboard = !(context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }
    fun refreshFramebuffer() { bitmap = null; bitmapRevision = -1; invalidate() }
    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon =
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        syncNativeSurface()
        if(session?.nativeDisplay != null) { updateDestination(); return }
        val target = session?.framebuffer ?: return
        synchronized(target) {
            var image = bitmap
            if (image == null || image.width != target.width || image.height != target.height) {
                image = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
                bitmap = image
                image.setPixels(target.pixels, 0, target.width, 0, 0, target.width, target.height)
            } else target.damageAfter(bitmapRevision)?.let { rect ->
                image.setPixels(target.pixels, rect.y * target.width + rect.x, target.width,
                    rect.x, rect.y, rect.width, rect.height)
            }
            bitmapRevision = target.revision
            val scale = minOf(width.toFloat() / target.width, height.toFloat() / target.height)
            val left = (width - target.width * scale) / 2
            val top = (height - target.height * scale) / 2
            destination.set(left, top, left + target.width * scale, top + target.height * scale)
            canvas.drawBitmap(image, null, destination, paint)
            val cursor = target.cursor
            if (cursor !== cursorImage) {
                cursorImage = cursor
                cursorBitmap = cursor?.takeIf { it.width > 0 && it.height > 0 }?.let {
                    Bitmap.createBitmap(it.pixels, it.width, it.height, Bitmap.Config.ARGB_8888)
                }
            }
            canvas.save()
            canvas.clipRect(destination)
            canvas.translate(left, top)
            canvas.scale(scale, scale)
            val cursorImageNow = cursorImage
            val cursorBitmapNow = cursorBitmap
            if (cursorImageNow != null && cursorBitmapNow != null) {
                canvas.drawBitmap(cursorBitmapNow, (pointerX - cursorImageNow.hotX).toFloat(),
                    (pointerY - cursorImageNow.hotY).toFloat(), paint)
            } else if (cursorImageNow == null) {
                canvas.translate(pointerX.toFloat(), pointerY.toFloat())
                paint.color = Color.WHITE; canvas.drawPath(fallbackCursor, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = Color.BLACK
                canvas.drawPath(fallbackCursor, paint); paint.style = Paint.Style.FILL
            }
            canvas.restore()
        }
    }

    private fun move(x: Float, y: Float) {
        updatePointer(x, y)
        sendPointer(true)
    }

    private fun updatePointer(x: Float, y: Float) {
        val target = session ?: return
        val bufferWidth = target.framebuffer?.width ?: target.options.width
        val bufferHeight = target.framebuffer?.height ?: target.options.height
        if (destination.width() <= 0) return
        pointerX = ((x - destination.left) * bufferWidth / destination.width()).roundToInt().coerceIn(0, bufferWidth - 1)
        pointerY = ((y - destination.top) * bufferHeight / destination.height()).roundToInt().coerceIn(0, bufferHeight - 1)
        postInvalidateOnAnimation()
    }

    private fun sendPointer(motion: Boolean = false) { session?.input?.pointer(pointerX, pointerY, buttonMask, motion) }
    private fun sendTouchButtons(mask: Int, lockedDisplay: GuiDisplay?) {
        if (lockedDisplay != null) lockedDisplay.relativePointer(0f, 0f, mask)
        else session?.input?.pointer(pointerX, pointerY, mask)
    }
    private fun click(mask: Int) {
        val lockedDisplay = session?.nativeDisplay?.takeIf { it.pointerLocked }
        sendTouchButtons(buttonMask or mask, lockedDisplay)
        sendTouchButtons(buttonMask, lockedDisplay)
    }
    override fun performClick(): Boolean {
        if (tracePointerEvents) session?.tracePointer("simulated left click")
        click(1)
        super.performClick()
        return true
    }

    // GestureDetector invokes performClick() for tap gestures.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        if (event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) return onCapturedPointerEvent(event)
        // A mouse tool can arrive through touch dispatch with a different source.
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            handleMouse(event)
            return true
        }
        val target = session
        val lockedDisplay = target?.nativeDisplay?.takeIf { it.pointerLocked }
        touchLocked = lockedDisplay != null
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchInProgress = true
            tracePointer(event, "touch")
            touchLookMoved = false
            multiTouch = false; dragging = false; scrollY = event.y
            if (!touchLocked) move(event.x, event.y)
        } else if (!touchInProgress) return true
        if (lockedDisplay != null && dragging) {
            dragging = false
            buttonMask = 0
            sendTouchButtons(buttonMask, lockedDisplay)
        }
        if (event.pointerCount > 1) {
            if (!multiTouch) {
                multiTouch = true; buttonMask = 0; scrollY = event.y
                sendTouchButtons(buttonMask, lockedDisplay)
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val delta = event.y - scrollY
                if (abs(delta) >= 24 * resources.displayMetrics.density) {
                    click(if (delta > 0) 8 else 16); scrollY = event.y
                }
            }
        } else if (!multiTouch && event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (lockedDisplay == null) move(event.x, event.y)
            else if (destination.width() > 0f && destination.height() > 0f) {
                val dx = event.x - touchX
                val dy = event.y - touchY
                if (touchLookMoved || dx * dx + dy * dy > touchSlopSquared) {
                    touchLookMoved = true
                    if (dx != 0f || dy != 0f) lockedDisplay.relativePointer(
                        dx * target.options.width / destination.width(),
                        dy * target.options.height / destination.height(), buttonMask,
                    )
                }
            }
        }
        // Preserve the initial movement while a locked touch is still within tap slop.
        if (!touchLocked || event.actionMasked != MotionEvent.ACTION_MOVE || touchLookMoved) {
            touchX = event.x; touchY = event.y
        }
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            tracePointer(event, "touch")
            touchInProgress = false
            buttonMask = 0; dragging = false
            sendTouchButtons(buttonMask, lockedDisplay)
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) return onCapturedPointerEvent(event)
        if (!event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(event)
        handleMouse(event)
        return true
    }

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) return super.onCapturedPointerEvent(event)
        handleMouse(event, captured = true)
        return true
    }

    private fun handleMouse(event: MotionEvent, captured: Boolean = false) {
        val display = session?.nativeDisplay
        val locked = display?.pointerLocked == true
        if (captured && display == null) return
        val relativeDisplay = display?.takeIf { locked || captured }
        val action = event.actionMasked
        if (relativeDisplay == null) updatePointer(event.x, event.y)
        else if (locked && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE ||
                captured && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP))) {
            // Android can attach movement to a captured DOWN/UP and send a second button event.
            val xAxis = if (captured) MotionEvent.AXIS_X else MotionEvent.AXIS_RELATIVE_X
            val yAxis = if (captured) MotionEvent.AXIS_Y else MotionEvent.AXIS_RELATIVE_Y
            var dx = event.getAxisValue(xAxis)
            var dy = event.getAxisValue(yAxis)
            for (index in 0 until event.historySize) {
                dx += event.getHistoricalAxisValue(xAxis, index)
                dy += event.getHistoricalAxisValue(yAxis, index)
            }
            if (dx.isFinite() && dy.isFinite() && (dx != 0f || dy != 0f)) relativeDisplay.relativePointer(dx, dy, buttonMask)
        }
        when (action) {
            MotionEvent.ACTION_SCROLL -> {
                val horizontal = wheelDelta(event.getAxisValue(MotionEvent.AXIS_HSCROLL))
                val vertical = wheelDelta(event.getAxisValue(MotionEvent.AXIS_VSCROLL))
                if (horizontal != 0f || vertical != 0f) {
                    if (relativeDisplay != null) relativeDisplay.relativeScroll(horizontal, vertical)
                    else session?.input?.scroll(pointerX, pointerY, buttonMask, horizontal, vertical)
                }
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> forwardMouseButtons(event, relativeDisplay)
            MotionEvent.ACTION_CANCEL -> {
                tracePointer(event, "mouse")
                releaseInput()
            }
            else -> if (relativeDisplay == null) sendPointer(true)
        }
    }

    private fun forwardMouseButtons(event: MotionEvent, relativeDisplay: GuiDisplay?) {
        val buttons = mouseButtons(event.buttonState)
        val action = event.actionMasked
        val report = MouseButtonReport(
            event.deviceId,
            event.eventTime,
            buttons,
            press = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_BUTTON_PRESS,
            generic = action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE,
        )
        // The touch copy can arrive after the generic release. Both copies keep the event time,
        // even when Android assigns different down times to their gestures.
        val duplicate = buttons != buttonMask && report.copy(generic = !report.generic) in mouseButtonHistory
        if (tracePointerEvents) {
            val route = if (duplicate) "mouse duplicate $buttonMask->$buttons" else "mouse $buttonMask->$buttons"
            tracePointer(event, route)
        }
        if (buttons == buttonMask || duplicate) return
        if (mouseButtonHistory.size == MOUSE_BUTTON_HISTORY_SIZE) mouseButtonHistory.removeFirst()
        mouseButtonHistory.addLast(report)
        buttonMask = buttons
        sendTouchButtons(buttonMask, relativeDisplay)
    }

    private fun tracePointer(event: MotionEvent, route: String) {
        if (!tracePointerEvents) return
        session?.tracePointer(
            "$route ${MotionEvent.actionToString(event.actionMasked)} device=${event.deviceId} " +
                "source=0x${event.source.toString(16)} tool=${event.getToolType(0)} " +
                "buttons=${event.buttonState} action_button=${event.actionButton} " +
                "down=${event.downTime} time=${event.eventTime}",
        )
    }

    private fun wheelDelta(delta: Float): Float = if (delta.isFinite()) delta.coerceIn(-20f, 20f) else 0f

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean =
        (isHardwareKeyboard(event) && forwardKeyEvent(event)) || super.onKeyPreIme(keyCode, event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        forwardKeyEvent(event) || super.dispatchKeyEvent(event)

    private fun isHardwareKeyboard(event: KeyEvent): Boolean =
        event.deviceId >= 0 && event.isFromSource(InputDevice.SOURCE_KEYBOARD) &&
            event.flags and KeyEvent.FLAG_SOFT_KEYBOARD == 0

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME) ||
            (event.keyCode == KeyEvent.KEYCODE_BACK && event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD)) {
            return false
        }
        val client = session?.input ?: return false
        if (event.action == KeyEvent.ACTION_MULTIPLE) {
            event.characters?.let(::sendText)
            return true
        }
        val physical = (event.deviceId.toLong() shl 32) or event.keyCode.toLong()
        if (event.action == KeyEvent.ACTION_UP) {
            pressed.remove(physical)?.send(client, false)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        pressed[physical]?.let { key ->
            if (key.scanCode == 0) key.send(client, true)
            return true
        }
        var symbol = X11Input.special(event.keyCode)
        // Android reports evdev scan codes. The X server applies its own XKB layout and repeat.
        if (client is GuiDisplay && isHardwareKeyboard(event) && event.scanCode in 1..247) {
            val key = PressedKey(symbol, event.scanCode)
            pressed[physical] = key
            key.send(client, true)
            return true
        }
        if (symbol == null) {
            val modifiers = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
            var code = event.getUnicodeChar(modifiers)
            if (code and KeyCharacterMap.COMBINING_ACCENT != 0) {
                pendingAccent = code and KeyCharacterMap.COMBINING_ACCENT_MASK
                return true
            }
            if (pendingAccent != 0 && code != 0) {
                val combined = KeyCharacterMap.getDeadChar(pendingAccent, code)
                if (combined != 0) code = combined else sendText(String(Character.toChars(pendingAccent)))
                pendingAccent = 0
            }
            if (code == 0) return false
            symbol = X11Input.unicode(code)
        }
        pressed[physical] = PressedKey(symbol)
        client.key(symbol, true)
        return true
    }

    fun sendSpecial(keysym: Int) { session?.input?.key(keysym, true); session?.input?.key(keysym, false) }
    fun sendChord(modifier: Int, keysym: Int) {
        val held = pressed.values.any { it.keysym == modifier }
        if (!held) session?.input?.key(modifier, true)
        sendSpecial(keysym)
        if (!held) session?.input?.key(modifier, false)
    }
    private fun sendText(text: String) {
        session?.input?.text(text)
    }

    fun releaseInput() {
        setMouseCapture(false)
        pendingKeyboard = false
        touchInProgress = false
        if (tracePointerEvents && buttonMask != 0) session?.tracePointer("release input mask=$buttonMask")
        session?.input?.let { client -> pressed.values.forEach { it.send(client, false) } }
        pressed.clear(); buttonMask = 0; pendingAccent = 0
        sendTouchButtons(buttonMask, session?.nativeDisplay)
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) releaseInput()
        super.onWindowFocusChanged(hasWindowFocus)
        watchMouseCapture()
        if (hasWindowFocus) post { openPendingKeyboard() }
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        if (!gainFocus) releaseInput()
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        watchMouseCapture()
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(mouseCapturePoll)
        releaseInput(); attachedDisplay?.surface(surfaceId,null); attachedDisplay = null
        super.onDetachedFromWindow()
    }
    override fun onCheckIsTextEditor(): Boolean = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        val inputSession = session
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, true) {
            private val composition = SpannableStringBuilder()
            override fun getEditable(): Editable = composition
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                return super.setComposingText(text, newCursorPosition)
            }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (session === inputSession) sendText(text?.toString().orEmpty())
                composition.clear(); return true
            }
            override fun finishComposingText(): Boolean {
                if (session === inputSession && composition.isNotEmpty()) sendText(composition.toString())
                composition.clear(); return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (session !== inputSession) return true
                if (composition.isNotEmpty()) {
                    composition.delete((composition.length - beforeLength.coerceAtLeast(0)).coerceAtLeast(0), composition.length)
                } else {
                    repeat(beforeLength.coerceIn(0, 256)) { sendSpecial(0xff08) }
                    repeat(afterLength.coerceIn(0, 256)) { sendSpecial(0xffff) }
                }
                return true
            }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (session !== inputSession) return true
                if (composition.isEmpty()) return deleteSurroundingText(beforeLength, afterLength)
                val count = minOf(beforeLength.coerceAtLeast(0), Character.codePointCount(composition, 0, composition.length))
                val start = Character.offsetByCodePoints(composition, composition.length, -count)
                composition.delete(start, composition.length)
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean = if (session === inputSession) dispatchKeyEvent(event) else true
            override fun performEditorAction(actionCode: Int): Boolean {
                if (session === inputSession) sendSpecial(0xff0d)
                return true
            }
        }
    }

    private fun mouseButtons(state: Int): Int =
        (if (state and MotionEvent.BUTTON_PRIMARY != 0) 1 else 0) or
        (if (state and MotionEvent.BUTTON_TERTIARY != 0) 2 else 0) or
        (if (state and MotionEvent.BUTTON_SECONDARY != 0) 4 else 0)
}
