package com.yukisoffd.lyra.x11.rfb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RfbClientTest {
    @Test fun privateSocketSecurityNoneWorksAcrossSupportedProtocolVersions() {
        for (minor in listOf(3, 7, 8)) {
            ServerSocket(0).use { server ->
                val executor = Executors.newFixedThreadPool(2)
                val frame = CountDownLatch(1)
                val tcp = TcpRfbTransport(server.localPort)
                // The fixture uses a JVM socket to exercise byte streams. Production uses Android LocalSocket.
                val local = object : RfbTransport by tcp { override val privateLocal = true }
                lateinit var client: RfbClient
                client = RfbClient(local) { pixels ->
                    assertEquals(0xff112233.toInt(), pixels.pixels[0])
                    frame.countDown(); client.close()
                }
                val serverTask = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
                        output.write("RFB 003.00$minor\n".toByteArray()); output.flush()
                        input.readFully(ByteArray(12))
                        if (minor == 3) output.writeInt(1) else {
                            output.write(byteArrayOf(1, 1)); output.flush(); assertEquals(1, input.readUnsignedByte())
                        }
                        if (minor >= 8) output.writeInt(0)
                        output.flush(); assertEquals(1, input.readUnsignedByte())
                        output.writeShort(1); output.writeShort(1); output.write(ByteArray(16)); output.writeInt(0); output.flush()
                        input.readFully(ByteArray(20)); assertEquals(2, input.readUnsignedByte()); input.readUnsignedByte()
                        repeat(input.readUnsignedShort()) { input.readInt() }
                        assertEquals(3, input.readUnsignedByte()); input.readFully(ByteArray(9))
                        output.writeByte(0); output.writeByte(0); output.writeShort(1)
                        output.writeShort(0); output.writeShort(0); output.writeShort(1); output.writeShort(1); output.writeInt(0)
                        output.write(byteArrayOf(0x33, 0x22, 0x11, 0)); output.flush()
                        assertTrue(frame.await(5, TimeUnit.SECONDS))
                    }
                }
                val reader = executor.submit { client.run() }
                try { serverTask.get(5, TimeUnit.SECONDS); reader.get(5, TimeUnit.SECONDS) }
                finally { client.close(); executor.shutdownNow() }
            }
        }
    }
    @Test fun loopbackHandshakeDamageCopyResizeCursorAndInput() {
        val server = ServerSocket(0)
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(1)
        val inputSent = CountDownLatch(1)
        val password = "password".toByteArray()
        var frameNumber = 0
        val client = RfbClient(server.localPort, password.copyOf()) { target ->
            frameNumber++
            if (frameNumber == 1) {
                assertArrayEquals(intArrayOf(0xffff0000.toInt(), 0xff0000ff.toInt()), target.pixels)
                assertEquals(1, target.cursor?.width)
            }
            if (frameNumber == 2) {
                assertArrayEquals(intArrayOf(0xffff0000.toInt(), 0xffff0000.toInt()), target.pixels)
            }
            if (frameNumber == 3) {
                assertEquals(1, target.width); assertEquals(2, target.height); ready.countDown()
            }
        }
        val serverTask = executor.submit {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
                output.write("RFB 003.008\n".toByteArray()); output.flush()
                assertEquals("RFB 003.008\n", ByteArray(12).also(input::readFully).toString(Charsets.US_ASCII))
                output.write(byteArrayOf(1, 2)); output.flush(); assertEquals(2, input.readUnsignedByte())
                val challenge = ByteArray(16) { it.toByte() }
                output.write(challenge); output.flush()
                assertArrayEquals(VncAuth.response(password, challenge), ByteArray(16).also(input::readFully))
                output.writeInt(0); output.flush(); assertEquals(1, input.readUnsignedByte())
                output.writeShort(2); output.writeShort(1)
                output.write(ByteArray(16)); output.writeInt(4); output.writeBytes("test"); output.flush()
                val format = ByteArray(20).also(input::readFully)
                assertEquals(32, format[4].toInt()); assertEquals(0, format[6].toInt())
                assertEquals(2, input.readUnsignedByte()); input.readUnsignedByte()
                val encodings = IntArray(input.readUnsignedShort()) { input.readInt() }
                assertTrue(encodings.contains(16))
                fun request() { assertEquals(3, input.readUnsignedByte()); input.readFully(ByteArray(9)) }
                request()
                fun rect(x: Int, y: Int, w: Int, h: Int, encoding: Int) {
                    output.writeShort(x); output.writeShort(y); output.writeShort(w); output.writeShort(h); output.writeInt(encoding)
                }
                output.writeByte(0); output.writeByte(0); output.writeShort(2)
                rect(0, 0, 2, 1, 0); output.write(byteArrayOf(0, 0, -1, 0, -1, 0, 0, 0))
                rect(0, 0, 1, 1, -239); output.write(byteArrayOf(-1, -1, -1, 0, 128.toByte())); output.flush()
                request()
                output.writeByte(0); output.writeByte(0); output.writeShort(1)
                rect(1, 0, 1, 1, 1); output.writeShort(0); output.writeShort(0); output.flush()
                request()
                output.writeByte(0); output.writeByte(0); output.writeShort(2)
                rect(0, 0, 1, 2, -223); rect(0, 0, 0, 0, -224); output.flush()
                request()
                assertTrue(inputSent.await(5, TimeUnit.SECONDS))
                assertEquals(4, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
                input.readUnsignedShort(); assertEquals(0xffe3, input.readInt())
                assertEquals(4, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                input.readUnsignedShort(); assertEquals(0xffe3, input.readInt())
                assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedShort()); assertEquals(1, input.readUnsignedShort())
                for (point in ("中😀\n" + "a".repeat(1000)).codePoints().toArray()) {
                    val expected = when (point) { 10 -> 0xff0d; in 0x20..0xff -> point; else -> 0x01000000 or point }
                    for (down in listOf(1, 0)) {
                        assertEquals(4, input.readUnsignedByte()); assertEquals(down, input.readUnsignedByte())
                        input.readUnsignedShort(); assertEquals(expected, input.readInt())
                    }
                }
                val wheelMasks = mutableListOf<Int>()
                while (true) {
                    val type = input.readUnsignedByte()
                    if (type == 4) break
                    assertEquals(5, type)
                    val mask = input.readUnsignedByte()
                    assertEquals(0, input.readUnsignedShort())
                    assertEquals(1, input.readUnsignedShort())
                    if (mask == 1) continue
                    wheelMasks += mask
                    assertEquals(5, input.readUnsignedByte())
                    assertEquals(1, input.readUnsignedByte())
                    assertEquals(0, input.readUnsignedShort())
                    assertEquals(1, input.readUnsignedShort())
                }
                assertEquals(listOf(9, 9, 9, 65), wheelMasks)
                assertEquals(1, input.readUnsignedByte())
                input.readUnsignedShort()
                assertEquals(0xffbe, input.readInt())
            }
        }
        val readerTask = executor.submit { runCatching { client.run() } }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            client.key(0xffe3, true); client.key(0xffe3, false); client.pointer(99, 99, 1)
            client.text("中😀\n" + "a".repeat(1000))
            repeat(3) { client.scroll(0, 1, 1, horizontal = 0f, vertical = 0.25f) }
            client.scroll(0, 1, 1, horizontal = 1f, vertical = 2.25f)
            client.key(0xffbe, true)
            inputSent.countDown()
            serverTask.get(5, TimeUnit.SECONDS)
        } finally {
            client.close(); server.close(); readerTask.get(5, TimeUnit.SECONDS); executor.shutdownNow()
        }
    }

    @Test fun unauthenticatedServerIsRejected() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val task = executor.submit {
                server.accept().use { socket ->
                    val output = DataOutputStream(socket.getOutputStream())
                    output.write("RFB 003.008\n".toByteArray()); output.flush()
                    DataInputStream(socket.getInputStream()).readFully(ByteArray(12))
                    output.write(byteArrayOf(1, 1)); output.flush()
                }
            }
            RfbClient(server.localPort, ByteArray(8)) {}.use { client ->
                assertThrows(IllegalArgumentException::class.java) { client.run() }
            }
            task.get(5, TimeUnit.SECONDS); executor.shutdownNow()
        }
    }
}
