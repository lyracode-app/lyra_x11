package com.yukisoffd.lyra.x11

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.yukisoffd.lyra.x11.rfb.RfbTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class UnixRfbTransport(private val path: String) : RfbTransport {
    private val socket = LocalSocket(LocalSocket.SOCKET_STREAM)
    private val closed = AtomicBoolean(false)
    override val privateLocal = true
    override val input: InputStream get() = socket.inputStream
    override val output: OutputStream get() = socket.outputStream
    init { require(path.startsWith('/') && path.toByteArray(Charsets.UTF_8).size < 104) { "Unix socket path is too long" } }
    override fun connect() {
        if (closed.get()) throw IOException("Session closed")
        socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        if (closed.get()) { socket.close(); throw IOException("Session closed") }
    }
    override fun readTimeout(milliseconds: Int) { socket.soTimeout = milliseconds }
    override fun close() { closed.set(true); runCatching { socket.close() } }
}
