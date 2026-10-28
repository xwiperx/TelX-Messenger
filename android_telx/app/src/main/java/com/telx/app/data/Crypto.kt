package com.telx.app.data

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side AES-256-GCM encryption for cloud backups.
 * The server only ever sees random ciphertext — the password never leaves the device.
 *
 * Blob format: base64( "TLX1" || salt[16] || iv[12] || AES-GCM(ciphertext, tag128) )
 */
object Crypto {

    private const val MAGIC = "TLX1"
    private const val PBKDF2_ITERS = 120_000
    private const val KEY_BITS = 256

    fun encrypt(plainText: String, password: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val out = ByteArray(MAGIC.length + salt.size + iv.size + ct.size)
        var o = 0
        MAGIC.toByteArray(Charsets.US_ASCII).copyInto(out, o); o += MAGIC.length
        salt.copyInto(out, o); o += salt.size
        iv.copyInto(out, o); o += iv.size
        ct.copyInto(out, o)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** @throws IllegalArgumentException when password is wrong or blob corrupted */
    fun decrypt(blobB64: String, password: String): String {
        val raw = try { Base64.decode(blobB64, Base64.NO_WRAP) } catch (e: Exception) {
            throw IllegalArgumentException("Corrupted backup")
        }
        val magicLen = MAGIC.length
        if (raw.size < magicLen + 16 + 12 + 16 || !MAGIC.equals(String(raw, 0, magicLen, Charsets.US_ASCII), ignoreCase = false))
            throw IllegalArgumentException("Not a TelX backup")
        val salt = raw.copyOfRange(magicLen, magicLen + 16)
        val iv = raw.copyOfRange(magicLen + 16, magicLen + 28)
        val ct = raw.copyOfRange(magicLen + 28, raw.size)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val plain = try {
            cipher.doFinal(ct)
        } catch (e: Exception) {
            throw IllegalArgumentException("Wrong password")
        }
        return String(plain, Charsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERS, KEY_BITS)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return f.generateSecret(spec).encoded
    }
}
