package com.axiombase.persistence

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptionService(keyHex: String) {
    private val key: SecretKey
    private val random = SecureRandom()

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12 // 96 bits, recommended for GCM
        private const val TAG_LENGTH_BITS = 128
    }

    init {
        val keyBytes = hexToBytes(keyHex)
        require(keyBytes.size == 32) { "ENCRYPTION_KEY must be 64 hex characters (32 bytes for AES-256)" }
        key = SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plaintext: ByteArray): String {
        val iv = ByteArray(IV_LENGTH)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        val combined = ByteArray(IV_LENGTH + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, IV_LENGTH)
        System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(encoded: String): ByteArray {
        val combined = Base64.getDecoder().decode(encoded)
        require(combined.size > IV_LENGTH) { "Encrypted data too short" }
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun encryptString(plaintext: String): String = encrypt(plaintext.toByteArray(Charsets.UTF_8))

    fun decryptString(encoded: String): String = String(decrypt(encoded), Charsets.UTF_8)

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
