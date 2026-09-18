package com.yonatan.ktuvit

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ktuvit does not send the password. The browser derives a key from a fixed salt
 * and the email, AES-encrypts the password with it, hashes the ciphertext and sends
 * that. This is a port of the site's own Encrypt(), verified against it with test
 * vectors (both the intermediate key and the final value match).
 *
 *   iv   = CryptoJS.enc.Hex.parse(email)
 *   key  = PBKDF2(salt, email, 128 bits, 3000 iterations, HMAC-SHA1)
 *   out  = base64(sha256(AES-CBC-PKCS7(password, key, iv)))
 */
object KtuvitCrypto {
    private const val SALT = "C8928DC731EC4E30AE6C523A34BE353A"
    private const val ITERATIONS = 3000
    private const val KEY_BITS = 128

    fun encryptPassword(email: String, password: String): String? {
        return try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(
                    PBEKeySpec(SALT.toCharArray(), email.toByteArray(Charsets.UTF_8), ITERATIONS, KEY_BITS)
                )
                .encoded

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(ivFromEmail(email)),
            )
            val cipherText = cipher.doFinal(password.toByteArray(Charsets.UTF_8))

            val hash = MessageDigest.getInstance("SHA-256").digest(cipherText)
            Base64.encodeToString(hash, Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The site parses the email as if it were hex. Non-hex characters simply yield
     * zero bytes, which is what CryptoJS ends up with, and the result is used as a
     * 16 byte IV, zero padded when the email is shorter.
     */
    private fun ivFromEmail(email: String): ByteArray {
        val iv = ByteArray(16)
        var i = 0
        while (i < email.length && i / 2 < 16) {
            val chunk = email.substring(i, minOf(i + 2, email.length))
            iv[i / 2] = parseLeadingHex(chunk).toByte()
            i += 2
        }
        return iv
    }

    /** Mirrors JavaScript parseInt(chunk, 16): leading hex digits only, NaN becomes 0. */
    private fun parseLeadingHex(chunk: String): Int {
        var value = 0
        var digits = 0
        for (character in chunk) {
            val digit = Character.digit(character, 16)
            if (digit < 0) break
            value = value * 16 + digit
            digits++
        }
        return if (digits == 0) 0 else value
    }
}
