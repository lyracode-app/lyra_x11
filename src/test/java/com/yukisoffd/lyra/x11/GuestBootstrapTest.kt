package com.yukisoffd.lyra.x11

import com.yukisoffd.lyra.x11.rfb.VncAuth
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class GuestBootstrapTest {
    private val token = "b8992841-0b1a-448f-a5da-b110dc0e64fd"
    @Test fun nativeGuestUsesTheStandardSocketContractWithoutStartingAnotherServer() {
        val auth="/run/lyra/b89928410b1a448f/xs/auth"
        val script=GuestBootstrap.command(101,GuiOptions(),token,nativeAuthority=auth)
        assertTrue(script.contains("export DISPLAY=:101"))
        assertTrue(script.contains("export XAUTHORITY='$auth'"))
        assertTrue(script.contains("xdpyinfo -display"))
        assertTrue(script.contains("dbus-run-session"))
        assertFalse(script.contains("Xtigervnc"))
        assertFalse(script.contains("xauth -f"))
        assertFalse(script.contains("-ac"))
        assertThrows(IllegalArgumentException::class.java) { GuestBootstrap.command(101,GuiOptions(),token,nativeAuthority="/run/lyra/other/auth") }
    }
    @Test fun independentDisplaysUsePrivateUnixSocketsAndNoSystemd() {
        val script = GuestBootstrap.command(100, GuiOptions(), token)
        assertTrue(script.contains("-rfbport -1 -rfbunixpath"))
        assertTrue(script.contains("-rfbunixmode 0600 -SecurityTypes None"))
        assertTrue(script.contains("-nolisten tcp"))
        assertTrue(script.contains("export DISPLAY=:100"))
        assertTrue(script.contains("dbus-run-session -- /bin/sh -c 'startxfce4'"))
        assertFalse(script.contains("-rfbauth"))
        assertFalse(script.contains("passwd"))
        assertFalse(script.contains("systemctl"))
        assertFalse(script.contains("-ac"))
    }
    @Test fun desktopCommandsArePassedAsOneQuotedShellArgument() {
        assertEquals("'a'\"'\"'b'", GuestBootstrap.quote("a'b"))
        val command = "printf '%s' \"hello\"; exec openbox-session"
        assertTrue(GuestBootstrap.command(101, GuiOptions(desktopCommand = command), token)
            .contains("/bin/sh -c ${GuestBootstrap.quote(command)}"))
        assertThrows(IllegalArgumentException::class.java) { GuestBootstrap.command(1, GuiOptions(), "../oops") }
    }
    @Test fun passwordFileAndAuthenticationUseDifferentKeys() {
        val password = "password".toByteArray()
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        // TigerVNC d3des reverses key-bit order even for its fixed password-file key.
        assertEquals("dbd83cfd727a1458", VncAuth.passwordFile(password).joinToString("") { "%02x".format(it.toInt() and 255) })
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(byteArrayOf(0xe8.toByte(), 0x4a, 0xd6.toByte(), 0x60, 0xc4.toByte(), 0x72, 0x1a, 0xe0.toByte()), "DES"))
        assertArrayEquals(password, cipher.doFinal(VncAuth.passwordFile(password)))
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(byteArrayOf(0x0e, 0x86.toByte(), 0xce.toByte(), 0xce.toByte(),
            0xee.toByte(), 0xf6.toByte(), 0x4e, 0x26), "DES"))
        val challenge = ByteArray(16) { it.toByte() }
        assertArrayEquals(cipher.doFinal(challenge), VncAuth.response(password, challenge))
    }
    @Test fun performanceOptionsRejectUnboundedAllocations() {
        assertThrows(IllegalArgumentException::class.java) { GuiOptions(width = 10000) }
        assertThrows(IllegalArgumentException::class.java) { GuiOptions(framesPerSecond = 0) }
    }
    @Test fun appPreviewsUseTheirOwnWindowManagerAndSessionDbus() {
        val script = GuestBootstrap.command(100, GuiOptions(desktopCommand = "python3 /workspace/app.py", appMode = true), token)
        assertTrue(script.contains("command -v openbox"))
        assertTrue(script.contains("dbus-run-session -- /bin/sh -c"))
        assertTrue(script.contains("openbox >/dev/null 2>&1 & exec /bin/sh -lc"))
        assertTrue(script.contains("python3 /workspace/app.py"))
    }
}
