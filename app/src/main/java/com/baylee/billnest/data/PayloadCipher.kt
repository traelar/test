package com.baylee.billnest.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PayloadCipher(
    private val alias: String = "billnest_payload_key_v2"
) {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val packed = ByteBuffer.allocate(4 + iv.size + cipherText.size)
            .putInt(iv.size)
            .put(iv)
            .put(cipherText)
            .array()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decrypt(packedBase64: String): String {
        val packed = Base64.decode(packedBase64, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(packed)
        val ivLength = buffer.int
        require(ivLength in 12..32 && ivLength <= buffer.remaining()) { "Invalid encrypted payload" }
        val iv = ByteArray(ivLength).also { buffer.get(it) }
        val cipherText = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}
