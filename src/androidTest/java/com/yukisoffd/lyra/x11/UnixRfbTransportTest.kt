package com.yukisoffd.lyra.x11

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a device to verify Android's actual filesystem Unix sockets, not a TCP substitute. */
@RunWith(AndroidJUnit4::class)
class UnixRfbTransportTest {
    @Test fun filesystemSocketTransfersBytesAndCloses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = File(context.cacheDir, "u-${System.nanoTime().toString(16)}")
        val listener = LocalSocket(LocalSocket.SOCKET_STREAM)
        listener.bind(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        val server = LocalServerSocket(listener.fileDescriptor)
        val executor = Executors.newSingleThreadExecutor()
        val accepted = executor.submit {
            server.accept().use { socket ->
                assertEquals(0x5a, socket.inputStream.read())
                socket.outputStream.write(0x6b); socket.outputStream.flush()
            }
        }
        try {
            UnixRfbTransport(path.absolutePath).use { transport ->
                assertTrue(transport.privateLocal)
                transport.connect(); transport.readTimeout(2000)
                transport.output.write(0x5a); transport.output.flush()
                assertEquals(0x6b, transport.input.read())
            }
            accepted.get(3, TimeUnit.SECONDS)
        } finally {
            runCatching { server.close() }; runCatching { listener.close() }
            executor.shutdownNow(); path.delete()
        }
    }
}
