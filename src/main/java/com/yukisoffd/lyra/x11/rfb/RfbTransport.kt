package com.yukisoffd.lyra.x11.rfb

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

interface RfbTransport : AutoCloseable {
    /** True only for a filesystem socket in the app's private, mode-0700 session directory. */
    val privateLocal: Boolean
    val input: InputStream
    val output: OutputStream
    @Throws(java.io.IOException::class)
    fun connect()
    fun readTimeout(milliseconds: Int)
}

/** Retained for protocol compatibility tests and authenticated loopback clients. */
class TcpRfbTransport(private val port: Int) : RfbTransport {
    private val socket = Socket()
    override val privateLocal = false
    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()
    override fun connect() { socket.tcpNoDelay = true; socket.connect(InetSocketAddress("127.0.0.1", port), 1500) }
    override fun readTimeout(milliseconds: Int) { socket.soTimeout = milliseconds }
    override fun close() = socket.close()
}
