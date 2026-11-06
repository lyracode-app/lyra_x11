import com.yukisoffd.lyra.x11.*;
import java.nio.file.*;

/** Emit exactly the production media script for a Linux native-daemon smoke test. */
class MediaBootstrapSmoke {
    public static void main(String[] args) throws Exception {
        var options = new GuiOptions(320, 240, 30, "true", false,
            AudioBackend.valueOf(args[2]), GraphicsBackend.SOFTWARE);
        String media = "/run/lyra/0123456789abcdef";
        String script = "set -eu\numask 077\nstate=" + GuestBootstrap.INSTANCE.quote(args[1]) +
            "\nmkdir -p \"$state/runtime\"\nexport XDG_RUNTIME_DIR=\"$state/runtime\"\nexport DISPLAY=:987\nexport XAUTHORITY=\"$state/Xauthority\"\n" +
            "\nexport HOME=\"$state/home\"\nmkdir -p \"$HOME\"\nunset SESSION_MANAGER WAYLAND_DISPLAY\n" +
            "export DBUS_SYSTEM_BUS_ADDRESS=\"unix:path=$state/no-system-bus\"\nexport DBUS_SESSION_BUS_ADDRESS=\"unix:path=$state/no-session-bus\"\n" +
            "audio_pid=''; pulse_pid=''; policy_pid=''\n" +
            "trap 'for pid in \"$policy_pid\" \"$pulse_pid\" \"$audio_pid\"; do [ -z \"$pid\" ] || kill \"$pid\" 2>/dev/null || true; done' EXIT HUP INT TERM\n" +
            GuestMediaBootstrap.INSTANCE.script(new GuestMedia(media, false), options) +
            "\n[ \"$audio_ok\" = yes ]\npactl info\n" +
            "paplay --raw --format=s16le --rate=48000 --channels=2 \"$1\"\n" +
            "sleep 0.2\n";
        Files.writeString(Path.of(args[0]), script);
    }
}
