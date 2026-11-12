package com.yukisoffd.lyra.x11

import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.view.SurfaceView
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyra.x11.media.MediaTestActivity
import com.yukisoffd.lyra.x11.nativebackend.NativeDisplay
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A real standard X11 client exercises the embedded server, not a synthetic framebuffer. */
@RunWith(AndroidJUnit4::class)
class NativeDisplayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun until(message: String, check: () -> Boolean) {
        val deadline=System.nanoTime()+8_000_000_000L
        while(!check() && System.nanoTime()<deadline) Thread.sleep(25)
        assertTrue(message,check())
    }
    @Test fun nativeX11AuthenticatesAndPresentsSharedBuffersToMultipleSurfaces() {
        val bridge=File(context.cacheDir,"xt-${System.nanoTime().toString(16)}").apply { mkdir() }
        val id=UUID.randomUUID().toString();val key=id.replace("-", "").take(16)
        val media=File(bridge,key).apply { mkdir() }
        val logs=StringBuffer()
        val options=GuiOptions(width=800,height=600,framesPerSecond=60,audio=AudioBackend.OFF,graphics=GraphicsBackend.SOFTWARE,displayBackend=DisplayBackend.NATIVE)
        val display=try { NativeDisplay.create(context,bridge,id,101,options) { logs.append(it).append('\n') } }
            catch(error: Throwable) { throw AssertionError(logs.toString(),error) }
        val activity=instrumentation.startActivitySync(Intent(context,MediaTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val views=ArrayList<SurfaceView>()
        lateinit var guiView: X11DisplayView
        val session=GuiSession(id,GuiEnvironment("test","Test Linux"),101,options)
        try {
            assertThrows(IllegalStateException::class.java) { NativeDisplay.create(context,bridge,id,101,options) {} }
            assertTrue("Duplicate preparation must preserve the live server",display.isAlive)
            assertTrue(File(media,"xs/auth").exists())
            instrumentation.runOnMainSync {
                activity.setContentView(LinearLayout(activity).apply {
                    orientation=LinearLayout.HORIZONTAL
                    guiView=X11DisplayView(activity).apply { this.session=session }
                    views+=guiView.getChildAt(0) as SurfaceView
                    addView(guiView,LinearLayout.LayoutParams(0,-1,1f))
                    addView(SurfaceView(activity).also { views+=it },LinearLayout.LayoutParams(0,-1,1f))
                })
                // The view is created while preparation is still pending, as in the real UI.
                session.nativeDisplay=display;guiView.invalidate()
            }
            until("Android surfaces not ready") { views.all { it.holder.surface.isValid } }
            instrumentation.runOnMainSync { display.surface(100_000,views[1].holder.surface) }
            val auth=File(media,"xs/auth").readBytes().takeLast(16).toByteArray()
            // Missing/wrong cookies must fail; local use never disables X11 authorization.
            XClient(File(bridge,".X11-unix/X101"),ByteArray(16)).use { assertFalse(it.accepted) }
            XClient(File(bridge,".X11-unix/X101"),auth).use { client ->
                assertTrue(logs.toString()+client.reason,client.accepted)
                assertEquals(800,client.width); assertEquals(600,client.height)
                client.paint(0x00dc2828)
                assertEquals("X11 root pixel",0x00dc2828,client.pixel())
                until(logs.toString()) { display.hasFrame && (display.diagnostics()["gpu_mirrors"] as Long)>0 }
                until("First native frame must be red") {
                    display.capture().let { image -> assertEquals(800,image.width);assertEquals(600,image.height);val c=image.getPixel(image.width/3,image.height/3)
                        image.recycle();Color.red(c)>180&&Color.green(c)<90 }
                }
                views.forEachIndexed { index,view -> until("Android surface $index must show X11 pixels") { Color.red(surfacePixel(view))>180 } }
                val readbacks=display.diagnostics()["screenshot_readbacks"]
                val beforeFrames=display.diagnostics()["presented_frames"] as Long
                client.paint(0x0028dc28)
                until("X11 damage must present without a screenshot request") { (display.diagnostics()["presented_frames"] as Long)>beforeFrames }
                assertEquals("Normal presentation must never read pixels on CPU",readbacks,display.diagnostics()["screenshot_readbacks"])
                views.forEachIndexed { index,view -> until("Android surface $index must update to green") { Color.green(surfacePixel(view))>180 } }
                display.capture().let { image -> assertTrue(Color.green(image.getPixel(image.width/3,image.height/3))>180); image.recycle() }
                display.pointer(200,150,0)
                Thread.sleep(100)
                val point=client.queryPointer()
                assertEquals(200,point.first); assertEquals(150,point.second)
                client.selectInput()
                instrumentation.runOnMainSync { guiView.sendSpecial('a'.code) }
                assertEquals(listOf(2,3),client.keyEvents().map { it.type })
                instrumentation.runOnMainSync {
                    assertTrue(guiView.requestFocus())
                    fun hardware(action: Int, code: Int, scan: Int, repeat: Int = 0) {
                        val event = KeyEvent(1L, 1L, action, code, repeat, 0, 7, scan, 0, InputDevice.SOURCE_KEYBOARD)
                        assertTrue(guiView.dispatchKeyEventPreIme(event))
                    }
                    hardware(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 29)
                    // A different Android mapping must still send the physical A key to XKB.
                    hardware(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q, 30)
                    hardware(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q, 30, repeat = 1)
                    hardware(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Q, 30)
                    guiView.releaseInput()
                    hardware(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, 2)
                    hardware(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1, 2)
                    hardware(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1, 59)
                    hardware(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F1, 59)
                    guiView.onCreateInputConnection(EditorInfo()).commitText("a", 1)
                }
                val keys = client.keyEvents(10)
                assertEquals(listOf(2, 2, 3, 3, 2, 3, 2, 3, 2, 3), keys.map { it.type })
                assertEquals(listOf(37, 38, 38, 37, 10, 10, 67, 67, 38, 38), keys.map { it.code })
                assertEquals(4, keys[1].state and 4) // ControlMask is active on the A press.
                client.selectRawButtons()
                fun pointer(
                    action: Int,
                    buttons: Int = 0,
                    vertical: Float = 0f,
                    horizontal: Float = 0f,
                    source: Int = InputDevice.SOURCE_MOUSE,
                    tool: Int = MotionEvent.TOOL_TYPE_MOUSE,
                    downTime: Long = 1L,
                    eventTime: Long = 2L,
                    desktopX: Float = 20f,
                    desktopY: Float = 580f,
                    relativeBatch: Pair<Float, Float>? = null,
                ) {
                    val properties = MotionEvent.PointerProperties().apply {
                        this.id = 0
                        toolType = tool
                    }
                    val coords = MotionEvent.PointerCoords().apply {
                        val scale = minOf(guiView.width / 800f, guiView.height / 600f)
                        x = if (source == InputDevice.SOURCE_MOUSE_RELATIVE) desktopX else (guiView.width - 800f * scale) / 2f + desktopX * scale
                        y = if (source == InputDevice.SOURCE_MOUSE_RELATIVE) desktopY else (guiView.height - 600f * scale) / 2f + desktopY * scale
                        setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
                        setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
                    }
                    val event = MotionEvent.obtain(
                        downTime, eventTime, action, 1, arrayOf(properties), arrayOf(coords), 0, buttons,
                        1f, 1f, 0, 0, source, 0,
                    )
                    try {
                        relativeBatch?.let { (dx, dy) -> event.addBatch(eventTime + 1, dx, dy, 0f, 0f, 0) }
                        if (source == InputDevice.SOURCE_MOUSE_RELATIVE) assertTrue(guiView.dispatchCapturedPointerEvent(event))
                        else when (action) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP ->
                                assertTrue(guiView.onTouchEvent(event))
                            else -> assertTrue(guiView.onGenericMotionEvent(event))
                        }
                    } finally {
                        event.recycle()
                    }
                }
                fun verifyButtons(message: String, buttonCodes: List<Int>, send: () -> Unit) {
                    instrumentation.runOnMainSync {
                        send()
                        guiView.sendSpecial(0xffbe)
                    }
                    val expected = buttonCodes.flatMap { listOf(15 to it, 16 to it) }
                    assertEquals(message, expected, client.rawButtonsBeforeKeyRelease())
                }
                verifyButtons("Quick click followed by delayed DOWN and UP copies", listOf(1)) {
                    pointer(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY, downTime = 100L, eventTime = 100L)
                    pointer(MotionEvent.ACTION_BUTTON_RELEASE, downTime = 100L, eventTime = 156L)
                    pointer(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, downTime = 99L, eventTime = 100L)
                    pointer(MotionEvent.ACTION_UP, downTime = 99L, eventTime = 156L)
                }
                verifyButtons("Two quick clicks with distinct event times", listOf(1, 1)) {
                    for (down in listOf(200L, 260L)) {
                        pointer(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, downTime = down, eventTime = down)
                        pointer(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY, downTime = down, eventTime = down)
                        pointer(MotionEvent.ACTION_BUTTON_RELEASE, downTime = down, eventTime = down + 55L)
                        pointer(MotionEvent.ACTION_UP, downTime = down, eventTime = down + 55L)
                    }
                }
                for ((index, source) in listOf(InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_TOUCHSCREEN).withIndex()) {
                    val down = 400L + index * 100L
                    verifyButtons("Mouse click with touch event source $source", listOf(1)) {
                        pointer(MotionEvent.ACTION_HOVER_MOVE)
                        pointer(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, source = source, downTime = down, eventTime = down)
                        pointer(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY, downTime = down, eventTime = down)
                        pointer(MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY, source = source)
                        pointer(MotionEvent.ACTION_BUTTON_RELEASE, downTime = down, eventTime = down + 56L)
                        pointer(MotionEvent.ACTION_UP, source = source, downTime = down, eventTime = down + 56L)
                    }
                }
                verifyButtons("Finger tap followed by an extra ACTION_UP", listOf(1)) {
                    pointer(MotionEvent.ACTION_DOWN, source = InputDevice.SOURCE_TOUCHSCREEN, tool = MotionEvent.TOOL_TYPE_FINGER)
                    pointer(MotionEvent.ACTION_UP, source = InputDevice.SOURCE_TOUCHSCREEN, tool = MotionEvent.TOOL_TYPE_FINGER)
                    pointer(MotionEvent.ACTION_UP, source = InputDevice.SOURCE_TOUCHSCREEN, tool = MotionEvent.TOOL_TYPE_FINGER)
                }
                verifyButtons("Fractional, fast and horizontal wheel events", listOf(4, 5, 5, 7)) {
                    pointer(MotionEvent.ACTION_HOVER_MOVE)
                    repeat(4) { pointer(MotionEvent.ACTION_SCROLL, vertical = 0.25f) }
                    pointer(MotionEvent.ACTION_SCROLL, vertical = -2f)
                    pointer(MotionEvent.ACTION_SCROLL, horizontal = 1f)
                }

                client.selectRawButtons(includeMotion = true)
                client.grabPointer(hidden = false)
                assertFalse("A visible grab is not pointer lock", display.pointerLocked)
                client.ungrabPointer()
                client.grabPointer(hidden = true)
                until("A hidden, explicit grab must enter pointer lock") { display.pointerLocked }
                fun hasMouseCapture(): Boolean {
                    var captured = false
                    instrumentation.runOnMainSync { captured = guiView.hasPointerCapture() }
                    return captured
                }
                until("X11 pointer lock must request Android mouse capture") { hasMouseCapture() }
                client.warpPointer(400, 300)
                instrumentation.runOnMainSync {
                    pointer(MotionEvent.ACTION_MOVE, source = InputDevice.SOURCE_MOUSE_RELATIVE,
                        desktopX = 15f, desktopY = -7f, relativeBatch = 25f to 3f)
                    guiView.sendSpecial(0xffbe)
                }
                client.rawPointerBeforeKeyRelease().let { (buttons, motion) ->
                    assertTrue(buttons.isEmpty())
                    assertEquals(listOf(40.0 to -4.0), motion)
                }
                client.warpPointer(400, 300)
                verifyButtons("Captured buttons and fractional wheel remain relative", listOf(1, 4)) {
                    pointer(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, source = InputDevice.SOURCE_MOUSE_RELATIVE,
                        downTime = 700L, eventTime = 700L, desktopX = 0f, desktopY = 0f)
                    pointer(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY, source = InputDevice.SOURCE_MOUSE_RELATIVE,
                        downTime = 700L, eventTime = 700L)
                    pointer(MotionEvent.ACTION_BUTTON_RELEASE, source = InputDevice.SOURCE_MOUSE_RELATIVE,
                        downTime = 700L, eventTime = 750L)
                    pointer(MotionEvent.ACTION_UP, source = InputDevice.SOURCE_MOUSE_RELATIVE,
                        downTime = 700L, eventTime = 750L, desktopX = 0f, desktopY = 0f)
                    repeat(4) { pointer(MotionEvent.ACTION_SCROLL, vertical = 0.25f, source = InputDevice.SOURCE_MOUSE_RELATIVE) }
                }
                assertEquals(400 to 300, client.queryPointer())
                client.warpPointer(400, 300)
                fun finger(action: Int, x: Float, y: Float, time: Long) = pointer(
                    action, source = InputDevice.SOURCE_TOUCHSCREEN, tool = MotionEvent.TOOL_TYPE_FINGER,
                    downTime = 1000, eventTime = time, desktopX = x, desktopY = y,
                )
                instrumentation.runOnMainSync {
                    finger(MotionEvent.ACTION_DOWN, 500f, 430f, 1000)
                    finger(MotionEvent.ACTION_MOVE, 560f, 410f, 1016)
                    guiView.sendSpecial(0xffbe)
                }
                client.rawPointerBeforeKeyRelease().let { (buttons, motion) ->
                    assertTrue("Looking around must not press the left button", buttons.isEmpty())
                    assertEquals(1, motion.size)
                    assertEquals(60.0, motion.single().first, 0.01)
                    assertEquals(-20.0, motion.single().second, 0.01)
                }
                client.warpPointer(400, 300)
                instrumentation.runOnMainSync {
                    finger(MotionEvent.ACTION_MOVE, 600f, 425f, 1032)
                    finger(MotionEvent.ACTION_UP, 600f, 425f, 1048)
                    guiView.sendSpecial(0xffbe)
                }
                client.rawPointerBeforeKeyRelease().let { (buttons, motion) ->
                    assertTrue(buttons.isEmpty())
                    assertEquals(1, motion.size)
                    assertEquals(40.0, motion.single().first, 0.01)
                    assertEquals(15.0, motion.single().second, 0.01)
                }
                client.warpPointer(400, 300)
                instrumentation.runOnMainSync {
                    finger(MotionEvent.ACTION_DOWN, 500f, 430f, 1200)
                    finger(MotionEvent.ACTION_MOVE, 501f, 430f, 1216)
                    finger(MotionEvent.ACTION_UP, 501f, 430f, 1232)
                    guiView.sendSpecial(0xffbe)
                }
                client.rawPointerBeforeKeyRelease().let { (buttons, motion) ->
                    assertEquals("A tap under pointer lock should still click", listOf(15 to 1, 16 to 1), buttons)
                    assertTrue("A tap must not move the locked pointer", motion.isEmpty())
                }
                assertEquals(400 to 300, client.queryPointer())
                client.ungrabPointer()
                until("Ungrab must restore absolute touch") { !display.pointerLocked }
                until("Ungrab must release Android mouse capture") { !hasMouseCapture() }
                verifyButtons("Touch tap after unlocking", listOf(1)) {
                    finger(MotionEvent.ACTION_DOWN, 100f, 120f, 2000)
                    finger(MotionEvent.ACTION_UP, 100f, 120f, 2016)
                }
                assertEquals(100 to 120, client.queryPointer())
                instrumentation.runOnMainSync { guiView.session=null }
                client.paint(0x002828dc)
                until("Remaining surface must continue presenting") { (display.diagnostics()["surfaces"] as Long)==1L }
                Thread.sleep(100)
                display.capture().let { image -> assertTrue(Color.blue(image.getPixel(image.width/3,image.height/3))>180); image.recycle() }
                until("The remaining Android surface must show blue") { Color.blue(surfacePixel(views[1]))>180 }
                val stats=display.diagnostics()
                assertEquals(stats["presented_frames"],(stats["ahb_frames"] as Long)+(stats["uploaded_frames"] as Long))
                assertTrue(stats["transport"] in listOf("ahardwarebuffer_eglimage","shared_memory_texture_upload"))
                assertEquals(false,stats["vnc_encoding"])
                android.util.Log.i("LyraNativeX11Test","NATIVE_X11_OK $stats")
            }
        } catch(error: Throwable) { throw AssertionError(logs.toString()+"\n"+display.diagnostics(),error) }
        finally {
            instrumentation.runOnMainSync { if(views.isNotEmpty())guiView.session=null;display.surface(100_000,null);activity.finish() }
            display.close();media.delete();File(bridge,".X11-unix").delete();bridge.delete()
        }
        assertFalse(display.isAlive)
    }

    private fun surfacePixel(view: SurfaceView): Int {
        val scale=minOf(view.width/640f,view.height/480f)
        val w=(640*scale).toInt();val h=(480*scale).toInt()
        val left=(view.width-w)/2;val top=(view.height-h)/2
        val image=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        val done=CountDownLatch(1);var result=-1
        try {
            PixelCopy.request(view.holder.surface,Rect(left,top,left+w,top+h),image,{ result=it;done.countDown() },Handler(Looper.getMainLooper()))
            assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result)
            val pixel=image.getPixel(16,16)
            return pixel
        } finally { image.recycle() }
    }

    private class XClient(socket: File,cookie: ByteArray) : AutoCloseable {
        private val connection=LocalSocket().apply { connect(LocalSocketAddress(socket.path,LocalSocketAddress.Namespace.FILESYSTEM));soTimeout=4000 }
        private val input=DataInputStream(connection.inputStream)
        private val output=connection.outputStream
        var accepted=false; private set
        var reason=""; private set
        var width=0;private set
        var height=0;private set
        private var root=0;private var base=0;private var gc=0
        private var xiOpcode: Byte = 0
        private fun bytes(size: Int,block: ByteBuffer.()->Unit)=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(block).array()
        private fun pad(n: Int)=(n+3)and -4
        init {
            val name="MIT-MAGIC-COOKIE-1".toByteArray()
            output.write(bytes(12+pad(name.size)+pad(cookie.size)) {
                put('l'.code.toByte());put(0);putShort(11);putShort(0);putShort(name.size.toShort());putShort(cookie.size.toShort());putShort(0)
                put(name);position(12+pad(name.size));put(cookie)
            });output.flush()
            val header=ByteArray(8);input.readFully(header)
            val length=((header[6].toInt()and 255)or((header[7].toInt()and 255)shl 8))*4
            val body=ByteArray(length);input.readFully(body)
            accepted=header[0].toInt()==1
            if(!accepted) reason=String(body.take(header[1].toInt()and 255).toByteArray())
            else {
                val setup=ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                base=setup.getInt(4)
                val offset=32+pad(setup.getShort(16).toInt()and 65535)+(body[21].toInt()and 255)*8
                root=setup.getInt(offset);width=setup.getShort(offset+20).toInt()and 65535;height=setup.getShort(offset+22).toInt()and 65535
                gc=base or 1
                output.write(bytes(16) { put(55);put(0);putShort(4);putInt(gc);putInt(root);putInt(0) })
            }
        }
        fun paint(color: Int) {
            output.write(bytes(16) { put(56);put(0);putShort(4);putInt(gc);putInt(4);putInt(color) })
            output.write(bytes(20) { put(70);put(0);putShort(5);putInt(root);putInt(gc);putShort(0);putShort(0);putShort(width.toShort());putShort(height.toShort()) })
            output.flush()
        }
        fun queryPointer(): Pair<Int,Int> {
            output.write(bytes(8) { put(38);put(0);putShort(2);putInt(root) });output.flush()
            val reply=ByteArray(32); input.readFully(reply)
            check(reply[0].toInt()==1) { "X11 error: ${reply.joinToString()}" }
            val data=ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
            return data.getShort(16).toInt() to data.getShort(18).toInt()
        }
        fun warpPointer(x: Int, y: Int) {
            output.write(bytes(24) {
                put(41); put(0); putShort(6); putInt(0); putInt(root)
                putLong(0); putShort(x.toShort()); putShort(y.toShort())
            })
            assertEquals(x to y, queryPointer())
        }
        fun grabPointer(hidden: Boolean) {
            // Like GLFW, grab a child window and select XI2 RawMotion on the root.
            val window = base or 5
            output.write(bytes(32) {
                put(1); put(0); putShort(8); putInt(window); putInt(root); putInt(0)
                putShort(width.toShort()); putShort(height.toShort()); putShort(0); putShort(1); putInt(0); putInt(0)
            })
            output.write(bytes(8) { put(8); put(0); putShort(2); putInt(window) })
            val cursor = if (hidden) {
                val pixmap = base or 2
                val bitmapGc = base or 3
                val cursor = base or 4
                output.write(bytes(16) { put(53); put(1); putShort(4); putInt(pixmap); putInt(root); putShort(1); putShort(1) })
                output.write(bytes(20) { put(55); put(0); putShort(5); putInt(bitmapGc); putInt(pixmap); putInt(4); putInt(0) })
                output.write(bytes(20) { put(70); put(0); putShort(5); putInt(pixmap); putInt(bitmapGc); putInt(0); putShort(1); putShort(1) })
                output.write(bytes(32) { put(93); put(0); putShort(8); putInt(cursor); putInt(pixmap); putInt(pixmap) })
                cursor
            } else 0
            output.write(bytes(24) {
                put(26); put(0); putShort(6); putInt(window); putShort(0)
                put(1); put(1); putInt(window); putInt(cursor); putInt(0)
            })
            output.flush()
            val reply = ByteArray(32)
            input.readFully(reply)
            assertEquals("GrabPointer reply type", 1, reply[0].toInt())
            assertEquals("GrabPointer status", 0, reply[1].toInt())
        }
        fun ungrabPointer() {
            output.write(bytes(8) { put(27); put(0); putShort(2); putInt(0) })
            output.write(bytes(8) { put(4); put(0); putShort(2); putInt(base or 5) })
            queryPointer() // A round trip waits for the preceding request.
        }
        fun pixel(): Int {
            output.write(bytes(20) { put(73);put(2);putShort(5);putInt(root);putShort((width/2).toShort());putShort((height/2).toShort());putShort(1);putShort(1);putInt(-1) });output.flush()
            val reply=ByteArray(32);input.readFully(reply)
            check(reply[0].toInt()==1) { "X11 error: ${reply.joinToString()}" }
            val data=ByteArray(4);input.readFully(data)
            return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int and 0x00ffffff
        }
        fun selectInput() {
            output.write(bytes(16) { put(2);put(0);putShort(4);putInt(root);putInt(1 shl 11);putInt(3) })
            output.write(bytes(12) { put(42);put(1);putShort(3);putInt(root);putInt(0) })
            output.write(bytes(4) { put(43);put(0);putShort(1) });output.flush()
            val reply=ByteArray(32);input.readFully(reply);check(reply[0].toInt()==1)
        }
        fun selectRawButtons(includeMotion: Boolean = false) {
            val name = "XInputExtension".toByteArray()
            val size = 8 + pad(name.size)
            output.write(bytes(size) { put(98); put(0); putShort((size / 4).toShort()); putShort(name.size.toShort()); putShort(0); put(name) })
            output.flush()
            val reply = ByteArray(32)
            input.readFully(reply)
            check(reply[0].toInt() == 1 && reply[8].toInt() == 1) { "XInput extension missing" }
            xiOpcode = reply[9]
            output.write(bytes(8) { put(xiOpcode); put(47); putShort(2); putShort(2); putShort(1) })
            output.flush()
            input.readFully(reply)
            check(reply[0].toInt() == 1) { "XIQueryVersion failed" }
            // Same XI2 raw button selection on the root window as xinput test-xi2 --root.
            output.write(bytes(20) {
                put(xiOpcode); put(46); putShort(5); putInt(root); putShort(1); putShort(0)
                putShort(1); putShort(1); putInt((1 shl 15) or (1 shl 16) or (if (includeMotion) 1 shl 17 else 0))
            })
            output.write(bytes(4) { put(43); put(0); putShort(1) })
            output.flush()
            input.readFully(reply)
            check(reply[0].toInt() == 1) { "XISelectEvents failed" }
        }
        data class KeyboardEvent(val type: Int, val code: Int, val state: Int)
        fun keyEvents(count: Int = 2): List<KeyboardEvent> {
            val events = ArrayList<KeyboardEvent>()
            repeat(count + 8) {
                val reply=ByteArray(32);input.readFully(reply)
                val type=reply[0].toInt()and 127
                check(type!=0) { "X11 input error: ${reply.joinToString()}" }
                if (type == 2 || type == 3) {
                    val state = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).getShort(28).toInt() and 0xffff
                    events += KeyboardEvent(type, reply[1].toInt() and 255, state)
                }
                if (events.size == count) return events
            }
            error("Key events missing")
        }
        fun rawButtonsBeforeKeyRelease() = rawPointerBeforeKeyRelease().first
        fun rawPointerBeforeKeyRelease(): Pair<List<Pair<Int, Int>>, List<Pair<Double, Double>>> {
            val buttons = mutableListOf<Pair<Int, Int>>()
            val motion = mutableListOf<Pair<Double, Double>>()
            repeat(48) {
                val reply = ByteArray(32)
                input.readFully(reply)
                val type = reply[0].toInt() and 127
                check(type != 0) { "X11 input error: ${reply.joinToString()}" }
                if (type == 3) return buttons to motion
                if (type == 35) {
                    val data = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
                    val values = ByteBuffer.allocate(data.getInt(4) * 4).order(ByteOrder.LITTLE_ENDIAN)
                    input.readFully(values.array())
                    val eventType = data.getShort(8).toInt()
                    if (reply[1] != xiOpcode) return@repeat
                    if (eventType in 15..16) buttons += eventType to data.getInt(16)
                    if (eventType == 17 && values.getInt(0) == 3) {
                        // Skip the valuator mask and two processed FP3232 values.
                        val rawOffset = (data.getShort(22).toInt() and 0xffff) * 4 + 16
                        fun rawAxis(offset: Int) = values.getInt(offset) + values.getInt(offset + 4).toUInt().toDouble() / 4294967296.0
                        motion += rawAxis(rawOffset) to rawAxis(rawOffset + 8)
                    }
                }
            }
            error("End of mouse input missing")
        }
        override fun close()=connection.close()
    }
}
