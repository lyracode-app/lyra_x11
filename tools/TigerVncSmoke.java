import com.yukisoffd.lyra.x11.GuestBootstrap;
import com.yukisoffd.lyra.x11.GuiOptions;
import com.yukisoffd.lyra.x11.AudioBackend;
import com.yukisoffd.lyra.x11.GraphicsBackend;
import com.yukisoffd.lyra.x11.rfb.*;
import kotlin.Unit;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.HexFormat;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Linux/JDK17+ integration harness against a real TigerVNC server, using the shipped Kotlin decoder. */
class TigerVncSmoke {
    static final class Unix implements RfbTransport {
        volatile SocketChannel channel;
        public boolean getPrivateLocal() { return true; }
        public void connect() throws IOException {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(path));
        }
        final String path;
        Unix(String path) { this.path = path; }
        public InputStream getInput() {
            return new InputStream() {
                public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255; }
                public int read(byte[] b, int off, int len) throws IOException { return channel.read(ByteBuffer.wrap(b, off, len)); }
            };
        }
        public OutputStream getOutput() {
            return new OutputStream() {
                public void write(int b) throws IOException { write(new byte[]{(byte)b}); }
                public void write(byte[] b, int off, int len) throws IOException {
                    ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
                    while (buffer.hasRemaining()) channel.write(buffer);
                }
            };
        }
        public void readTimeout(int timeout) { /* The harness has an overall ten-second deadline. */ }
        public void close() throws IOException { if (channel != null) channel.close(); }
    }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--emit")) {
            var options = new GuiOptions(320, 240, 30, "python3 " + GuestBootstrap.INSTANCE.quote(args[3]), false, AudioBackend.OFF, GraphicsBackend.SOFTWARE);
            Files.writeString(Path.of(args[1]), GuestBootstrap.INSTANCE.command(Integer.parseInt(args[4]), options, args[2], null));
            return;
        }
        if (args[0].equals("--password-vector")) {
            System.out.println(HexFormat.of().formatHex(VncAuth.INSTANCE.passwordFile("password".getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
            return;
        }
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger colors = new AtomicInteger(), frames = new AtomicInteger();
        java.util.Set<String> samples = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        AtomicReference<Throwable> error = new AtomicReference<>();
        RfbClient client = new RfbClient(new Unix(args[0]), new byte[0], pixels -> {
            if (pixels.getWidth() != 320 || pixels.getHeight() != 240) throw new AssertionError("Unexpected desktop geometry");
            // Stay away from the server's initial pointer in the middle of the screen.
            int color = pixels.getPixels()[20 * 320 + 20];
            samples.add(Integer.toHexString(color));
            int bit = color == 0xff225588 ? 1 : color == 0xff884422 ? 2 : 0;
            colors.updateAndGet(old -> old | bit);
            frames.incrementAndGet();
            if (colors.get() == 3) ready.countDown();
            return Unit.INSTANCE;
        });
        Thread reader = new Thread(() -> {
            try { client.run(); } catch (Throwable failure) { if (ready.getCount() != 0) error.set(failure); }
        }, "native-tigervnc-reader");
        reader.setDaemon(true); reader.start();
        try {
            if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("No two-color live update; frames=" + frames.get() + " samples=" + samples, error.get());
            System.out.println("TIGERVNC_UNIX_OK geometry=320x240 live_colors=2 frames=" + frames.get());
        } finally { client.close(); reader.join(2000); }
    }
}
