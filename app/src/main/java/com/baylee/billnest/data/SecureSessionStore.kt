package com.baylee.billnest.data

import android.content.Context
import com.baylee.billnest.model.SessionData
import com.google.gson.Gson
import java.io.File

class SecureSessionStore(
    context: Context,
    private val cipher: PayloadCipher = PayloadCipher()
) {
    private data class StoredState(
        val session: SessionData? = null,
        val migratedHouseholdIds: Set<String> = emptySet()
    )

    private val gson = Gson()
    private val file = File(context.filesDir, "billnest-session-v2.sec")

    @Synchronized
    private fun loadState(): StoredState = runCatching {
        if (!file.exists()) return StoredState()
        gson.fromJson(cipher.decrypt(file.readText()), StoredState::class.java) ?: StoredState()
    }.getOrElse { StoredState() }

    @Synchronized
    private fun saveState(state: StoredState) {
        file.writeText(cipher.encrypt(gson.toJson(state)))
    }

    fun load(): SessionData? = loadState().session

    @Synchronized
    fun save(session: SessionData) {
        val state = loadState()
        saveState(state.copy(session = session))
    }

    @Synchronized
    fun clear() {
        val state = loadState()
        saveState(state.copy(session = null))
    }

    fun isLegacyMigrationComplete(householdId: String): Boolean =
        loadState().migratedHouseholdIds.contains(householdId)

    @Synchronized
    fun markLegacyMigrationComplete(householdId: String) {
        val state = loadState()
        saveState(state.copy(migratedHouseholdIds = state.migratedHouseholdIds + householdId))
    }
}
