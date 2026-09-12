package com.baylee.billnest.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.baylee.billnest.model.AppData
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedStore(private val context: Context) {
    private val gson = Gson()
    private val file = File(context.filesDir, "billnest.sec")
    private val alias = "billnest_data_key_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
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

    fun load(): AppData = runCatching {
        if (!file.exists()) return AppData()
        val all = file.readBytes()
        val bb = ByteBuffer.wrap(all)
        val ivSize = bb.int
        val iv = ByteArray(ivSize).also { bb.get(it) }
        val encrypted = ByteArray(bb.remaining()).also { bb.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val decoded = String(cipher.doFinal(encrypted), Charsets.UTF_8)

        val root = JsonParser.parseString(decoded).asJsonObject
        listOf(
            "accounts",
            "accountPreferences",
            "manualTransactions",
            "plaidTransactions",
            "budgets",
            "debts",
            "savingsGoals",
            "reservedFunds",
            "billMatches",
            "subscriptionOverrides"
        ).forEach { field ->
            if (!root.has(field) || root.get(field).isJsonNull) root.add(field, JsonArray())
        }
        if (!root.has("backendUrl") || root.get("backendUrl").isJsonNull) root.addProperty("backendUrl", "")
        gson.fromJson(root, AppData::class.java)
    }.getOrElse { AppData() }

    @Synchronized
    fun save(data: AppData) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(gson.toJson(data).toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val bb = ByteBuffer.allocate(4 + iv.size + encrypted.size)
        bb.putInt(iv.size).put(iv).put(encrypted)
        file.writeBytes(bb.array())
    }
}
