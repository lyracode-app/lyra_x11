package com.yukisoffd.lyra.x11.rfb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Private Unix socket in production; legacy TCP is authenticated. UI never performs blocking IO. */
class RfbClient(private val transport: RfbTransport, private val password: ByteArray = ByteArray(0),
    private val frame: (PixelBuffer) -> Unit) : AutoCloseable, com.yukisoffd.lyra.x11.GuiInput {
    constructor(port: Int, password: ByteArray, frame: (PixelBuffer) -> Unit) : this(TcpRfbTransport(port), password, frame)
    private val closed = AtomicBoolean(false)
    private val commands = ArrayBlockingQueue<(DataOutputStream) -> Unit>(512)
    private var writer: Thread? = null
    @Volatile var buffer: PixelBuffer? = null; private set
    @Volatile var active = true
    @Volatile private var pointer: Triple<Int, Int, Int>? = null

    fun run() {
        transport.connect()
        transport.readTimeout(10000)
        val input = DataInputStream(BufferedInputStream(transport.input, 64 * 1024))
        val output = DataOutputStream(BufferedOutputStream(transport.output, 16 * 1024))
        handshake(input, output)
        if (closed.get()) throw IOException("Session closed")
        transport.readTimeout(0)
        writer = Thread({
            try {
                while (!closed.get()) {
                    val command = commands.poll(8, TimeUnit.MILLISECONDS)
                    command?.invoke(output)
                    while (true) {
                        val queued = commands.poll() ?: break
                        queued(output)
                    }
                    // A single pending motion replaces older motions; button edges remain ordered in commands.
                    synchronized(this) { pointer.also { pointer = null } }?.let { (x, y, mask) ->
                        writePointer(output, x, y, mask)
                    }
                    output.flush()
                }
            } catch (_: Exception) { close() }
        }, "lyra-x11-rfb-writer").apply { isDaemon = true; start() }
        requestUpdate(false)
        ZrleDecoder().use { zrle ->
            while (!closed.get()) {
                when (val type = input.readUnsignedByte()) {
                    0 -> readUpdate(input, zrle)
                    2 -> Unit // Bell
                    3 -> { input.skipBytes(3); readBytes(input, 1_048_576) } // No implicit clipboard sharing.
                    else -> throw IOException("Unsupported RFB server message: $type")
                }
            }
        }
    }

    private fun handshake(input: DataInputStream, output: DataOutputStream) {
        val version = ByteArray(12).also(input::readFully).toString(Charsets.US_ASCII)
        require(Regex("RFB 003\\.00[3-9]\\n").matches(version)) { "Unsupported RFB version" }
        val minor = version.substring(8, 11).toInt().let { if (it >= 8) 8 else if (it >= 7) 7 else 3 }
        output.write("RFB 003.00$minor\n".toByteArray(Charsets.US_ASCII)); output.flush()
        val security = if (minor == 3) input.readInt() else {
            val count = input.readUnsignedByte()
            require(count > 0) { "RFB security negotiation failed" }
            val types = ByteArray(count).also(input::readFully)
            val selected = if (transport.privateLocal && types.contains(1.toByte())) 1 else 2
            require(types.contains(selected.toByte())) { "Server does not offer the required security type" }
            output.writeByte(selected); output.flush()
            selected
        }
        require(security == 2 || (security == 1 && transport.privateLocal)) { "Unauthenticated TCP is not supported" }
        if (security == 2) {
            require(password.isNotEmpty()) { "Unexpected password authentication on private GUI socket; start a fresh Lyra session" }
            val challenge = ByteArray(16).also(input::readFully)
            output.write(VncAuth.response(password, challenge)); output.flush()
        }
        if (security == 2 || minor >= 8) require(input.readInt() == 0) { "VNC authentication failed" }
        output.writeByte(1); output.flush() // shared
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        val serverFormat = ByteArray(16).also(input::readFully)
        require(serverFormat.size == 16)
        readBytes(input, 65536) // desktop name, never interpreted as instructions
        buffer = PixelBuffer(width, height)
        // 32 bpp, 24-bit true color, little endian B,G,R,pad; ZRLE CPIXEL omits pad.
        output.write(byteArrayOf(0, 0, 0, 0, 32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0))
        val encodings = intArrayOf(16, 1, 0, -223, -239, -224) // ZRLE, CopyRect, Raw, DesktopSize, Cursor, LastRect
        output.writeByte(2); output.writeByte(0); output.writeShort(encodings.size)
        encodings.forEach(output::writeInt)
        output.flush()
    }

    private fun readUpdate(input: DataInputStream, zrle: ZrleDecoder) {
        input.readUnsignedByte()
        val count = input.readUnsignedShort()
        val target = buffer ?: error("Missing framebuffer")
        for (i in 0 until count) {
            val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
            val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
            when (val encoding = input.readInt()) {
                -224 -> break
                -223 -> target.resize(w, h)
                -239 -> readCursor(input, target, x, y, w, h)
                0 -> {
                    target.validate(x, y, w, h)
                    val row = ByteArray(w * 4)
                    for (dy in 0 until h) {
                        input.readFully(row)
                        synchronized(target) {
                            for (dx in 0 until w) target.pixels[(y + dy) * target.width + x + dx] = color(row, dx * 4)
                            target.mark(x, y + dy, w, 1)
                        }
                    }
                }
                1 -> target.copy(x, y, w, h, input.readUnsignedShort(), input.readUnsignedShort())
                16 -> {
                    target.validate(x, y, w, h)
                    zrle.decode(readBytes(input, 48 * 1024 * 1024), target, x, y, w, h)
                }
                else -> throw IOException("Unsupported RFB encoding: $encoding")
            }
        }
        target.commitFrame()
        frame(target)
        if (!active && !closed.get()) Thread.sleep(500)
        requestUpdate(true)
    }

    private fun readCursor(input: DataInputStream, target: PixelBuffer, x: Int, y: Int, w: Int, h: Int) {
        require(w <= 256 && h <= 256 && (w == 0 || x < w) && (h == 0 || y < h)) { "Oversized RFB cursor" }
        val bytes = ByteArray(w * h * 4).also(input::readFully)
        val stride = (w + 7) / 8
        val mask = ByteArray(stride * h).also(input::readFully)
        val pixels = IntArray(w * h) { index ->
            if ((mask[(index / w) * stride + (index % w) / 8].toInt() and (128 ushr (index % w % 8))) == 0) 0
            else color(bytes, index * 4)
        }
        synchronized(target) { target.cursor = CursorImage(w, h, x, y, pixels) }
    }

    private fun requestUpdate(incremental: Boolean) {
        val target = buffer ?: return
        enqueue { out ->
            out.writeByte(3); out.writeByte(if (incremental) 1 else 0)
            out.writeShort(0); out.writeShort(0); out.writeShort(target.width); out.writeShort(target.height)
        }
    }

    override fun key(keysym: Int, down: Boolean) = enqueue { out ->
        out.writeByte(4); out.writeByte(if (down) 1 else 0); out.writeShort(0); out.writeInt(keysym)
    }

    override fun text(text: String) {
        val points = text.codePoints().limit(4096).toArray()
        enqueue { out ->
            for (point in points) {
                val symbol = when (point) { 10, 13 -> 0xff0d; 9 -> 0xff09; in 0x20..0xff -> point; else -> 0x01000000 or point }
                for (down in intArrayOf(1, 0)) {
                    out.writeByte(4); out.writeByte(down); out.writeShort(0); out.writeInt(symbol)
                }
            }
        }
    }

    @Synchronized override fun pointer(x: Int, y: Int, mask: Int, motionOnly: Boolean) {
        val target = buffer ?: return
        val px = x.coerceIn(0, target.width - 1); val py = y.coerceIn(0, target.height - 1)
        if (motionOnly) pointer = Triple(px, py, mask)
        else {
            pointer = null
            enqueue { writePointer(it, px, py, mask) }
        }
    }

    private fun enqueue(command: (DataOutputStream) -> Unit) {
        // Never silently lose key-up or button-up events: terminate an overloaded connection.
        if (!closed.get() && !commands.offer(command)) close()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { transport.close() }
            writer?.interrupt()
            commands.clear()
            password.fill(0)
        }
    }

    companion object {
        private fun readBytes(input: DataInputStream, limit: Int): ByteArray {
            val count = input.readInt()
            require(count in 0..limit) { "Oversized RFB payload" }
            return ByteArray(count).also(input::readFully)
        }
        private fun color(bytes: ByteArray, offset: Int): Int = 0xff000000.toInt() or
            ((bytes[offset + 2].toInt() and 255) shl 16) or
            ((bytes[offset + 1].toInt() and 255) shl 8) or (bytes[offset].toInt() and 255)
        private fun writePointer(output: DataOutputStream, x: Int, y: Int, mask: Int) {
            output.writeByte(5); output.writeByte(mask); output.writeShort(x); output.writeShort(y)
        }
    }
}
