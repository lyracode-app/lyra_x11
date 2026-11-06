package com.yukisoffd.lyra.x11

import android.content.Context
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
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncNativeSurface(); invalidate() }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh);updateDestination() }
    companion object { private val nextSurfaceId = AtomicLong() }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fallbackCursor = Path().apply {
        moveTo(0f, 0f); lineTo(3f, 18f); lineTo(8f, 12f); lineTo(15f, 12f); close()
    }
    private var bitmap: Bitmap? = null
    private var bitmapRevision = -1L
    private var cursorBitmap: Bitmap? = null
    private var cursorImage: CursorImage? = null
    private val destination = RectF()
    private val pressed = mutableMapOf<Long, Int>()
    private var buttonMask = 0
    private var pointerX = 0
    private var pointerY = 0
    private var scrollY = 0f
    private var dragging = false
    private var multiTouch = false
    private var pendingAccent = 0
    private var pendingKeyboard = false
    var session: GuiSession? = null
        set(value) {
            if (field === value) return
            releaseInput()
            attachedDisplay?.surface(surfaceId,null); attachedDisplay = null
            field = value
            syncNativeSurface(); updateDestination()
            bitmap = null
            bitmapRevision = -1L
            cursorBitmap = null
            cursorImage = null
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(this)
            invalidate()
        }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { if (!multiTouch) performClick(); return true }
        override fun onLongPress(e: MotionEvent) { if (!dragging && !multiTouch) click(4) }
        override fun onScroll(first: MotionEvent?, current: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!multiTouch && current.pointerCount == 1) {
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
        val target = session ?: return
        val bufferWidth = target.framebuffer?.width ?: target.options.width
        val bufferHeight = target.framebuffer?.height ?: target.options.height
        if (destination.width() <= 0) return
        pointerX = ((x - destination.left) * bufferWidth / destination.width()).roundToInt().coerceIn(0, bufferWidth - 1)
        pointerY = ((y - destination.top) * bufferHeight / destination.height()).roundToInt().coerceIn(0, bufferHeight - 1)
        sendPointer(true)
        postInvalidateOnAnimation()
    }

    private fun sendPointer(motion: Boolean = false) { session?.input?.pointer(pointerX, pointerY, buttonMask, motion) }
    private fun click(mask: Int) {
        session?.input?.pointer(pointerX, pointerY, buttonMask or mask)
        session?.input?.pointer(pointerX, pointerY, buttonMask)
    }
    override fun performClick(): Boolean { click(1); super.performClick(); return true }

    // GestureDetector invokes performClick() for tap gestures.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            move(event.x, event.y)
            buttonMask = mouseButtons(event.buttonState)
            sendPointer()
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) releaseInput()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            multiTouch = false; dragging = false; scrollY = event.y
            move(event.x, event.y)
        }
        if (event.pointerCount > 1) {
            if (!multiTouch) { multiTouch = true; buttonMask = 0; sendPointer(); scrollY = event.y }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val delta = event.y - scrollY
                if (abs(delta) >= 24 * resources.displayMetrics.density) {
                    click(if (delta > 0) 8 else 16); scrollY = event.y
                }
            }
        } else if (!multiTouch && event.actionMasked == MotionEvent.ACTION_MOVE) move(event.x, event.y)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            buttonMask = 0; dragging = false; sendPointer()
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(event)
        move(event.x, event.y)
        buttonMask = mouseButtons(event.buttonState)
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                repeat(abs(vertical).roundToInt().coerceIn(if (vertical == 0f) 0 else 1, 20)) { click(if (vertical > 0) 8 else 16) }
                repeat(abs(horizontal).roundToInt().coerceIn(if (horizontal == 0f) 0 else 1, 20)) { click(if (horizontal > 0) 64 else 32) }
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> sendPointer()
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME) ||
            (event.keyCode == KeyEvent.KEYCODE_BACK && event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD)) {
            return super.dispatchKeyEvent(event)
        }
        val client = session?.input ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_MULTIPLE) {
            event.characters?.let(::sendText)
            return true
        }
        val physical = (event.deviceId.toLong() shl 32) or event.keyCode.toLong()
        if (event.action == KeyEvent.ACTION_UP) {
            pressed.remove(physical)?.let { client.key(it, false) }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        var symbol = X11Input.special(event.keyCode)
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
            if (code == 0) return super.dispatchKeyEvent(event)
            symbol = X11Input.unicode(code)
        }
        pressed[physical] = symbol
        client.key(symbol, true)
        return true
    }

    fun sendSpecial(keysym: Int) { session?.input?.key(keysym, true); session?.input?.key(keysym, false) }
    fun sendChord(modifier: Int, keysym: Int) {
        val held = pressed.containsValue(modifier)
        if (!held) session?.input?.key(modifier, true)
        sendSpecial(keysym)
        if (!held) session?.input?.key(modifier, false)
    }
    private fun sendText(text: String) {
        session?.input?.text(text)
    }

    fun releaseInput() {
        pendingKeyboard = false
        pressed.values.forEach { session?.input?.key(it, false) }
        pressed.clear(); buttonMask = 0; pendingAccent = 0
        sendPointer()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) releaseInput()
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) post { openPendingKeyboard() }
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        if (!gainFocus) releaseInput()
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }
    override fun onDetachedFromWindow() { releaseInput(); attachedDisplay?.surface(surfaceId,null); attachedDisplay = null; super.onDetachedFromWindow() }
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
