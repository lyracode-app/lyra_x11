package com.yukisoffd.lyra.x11.rfb

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object VncAuth {
    fun newPassword(): ByteArray {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        return ByteArray(8) { alphabet[random.nextInt(alphabet.length)].code.toByte() }
    }

    fun passwordFile(password: ByteArray): ByteArray = encrypt(
        reverseBits(byteArrayOf(23, 82, 107, 6, 35, 78, 88, 7)), password.copyOf(8),
    )

    fun response(password: ByteArray, challenge: ByteArray): ByteArray {
        require(challenge.size == 16)
        val key = reverseBits(password.copyOf(8))
        return encrypt(key, challenge)
    }
    private fun reverseBits(bytes: ByteArray) = bytes.map { (Integer.reverse(it.toInt() and 255) ushr 24).toByte() }.toByteArray()

    // This is the legacy RFB authentication wire format, used only over authenticated loopback.
    @android.annotation.SuppressLint("GetInstance")
    private fun encrypt(key: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("DES/ECB/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        doFinal(data)
    }
}
