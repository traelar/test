package com.baylee.billnest.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.baylee.billnest.model.SyncChange
import com.baylee.billnest.model.SyncConflict
import com.baylee.billnest.model.SyncMutation
import java.util.UUID

class LocalSyncDb(
    context: Context,
    private val cipher: PayloadCipher = PayloadCipher()
) : SQLiteOpenHelper(context, "billnest-sync-v2.db", null, 1) {

    data class StoredConflict(
        val conflictId: Long,
        val mutationId: String,
        val kind: String,
        val recordId: String,
        val localBaseVersion: Int,
        val localDeleted: Boolean,
        val localPayloadJson: String,
        val serverVersion: Int,
        val serverDeleted: Boolean,
        val serverPayloadJson: String,
        val serverUpdatedAt: String
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE records (
              kind TEXT NOT NULL,
              record_id TEXT NOT NULL,
              encrypted_payload TEXT NOT NULL,
              version INTEGER NOT NULL,
              deleted INTEGER NOT NULL,
              PRIMARY KEY(kind, record_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE outbox (
              mutation_id TEXT PRIMARY KEY,
              kind TEXT NOT NULL,
              record_id TEXT NOT NULL,
              base_version INTEGER NOT NULL,
              deleted INTEGER NOT NULL,
              encrypted_payload TEXT NOT NULL,
              created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE meta (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE conflicts (
              conflict_id INTEGER PRIMARY KEY AUTOINCREMENT,
              mutation_id TEXT NOT NULL,
              kind TEXT NOT NULL,
              record_id TEXT NOT NULL,
              local_base_version INTEGER NOT NULL,
              local_deleted INTEGER NOT NULL,
              local_payload TEXT NOT NULL,
              server_version INTEGER NOT NULL,
              server_deleted INTEGER NOT NULL,
              server_payload TEXT NOT NULL,
              server_updated_at TEXT NOT NULL,
              created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_outbox_record ON outbox(kind, record_id)")
        db.execSQL("CREATE INDEX idx_conflicts_record ON conflicts(kind, record_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun currentVersion(kind: String, recordId: String): Int {
        readableDatabase.rawQuery(
            "SELECT version FROM records WHERE kind = ? AND record_id = ? LIMIT 1",
            arrayOf(kind, recordId)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    @Synchronized
    fun enqueue(
        kind: String,
        recordId: String,
        baseVersion: Int,
        deleted: Boolean,
        payloadJson: String
    ): String {
        val mutationId = UUID.randomUUID().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("outbox", "kind = ? AND record_id = ?", arrayOf(kind, recordId))
            val values = ContentValues().apply {
                put("mutation_id", mutationId)
                put("kind", kind)
                put("record_id", recordId)
                put("base_version", baseVersion)
                put("deleted", if (deleted) 1 else 0)
                put("encrypted_payload", cipher.encrypt(payloadJson))
                put("created_at_ms", System.currentTimeMillis())
            }
            db.insertOrThrow("outbox", null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return mutationId
    }

    fun enqueueCurrent(kind: String, recordId: String, deleted: Boolean, payloadJson: String): String =
        enqueue(kind, recordId, currentVersion(kind, recordId), deleted, payloadJson)

    @Synchronized
    fun pendingMutations(limit: Int = 100): List<SyncMutation> {
        val result = mutableListOf<SyncMutation>()
        readableDatabase.rawQuery(
            """
            SELECT mutation_id, kind, record_id, base_version, deleted, encrypted_payload
            FROM outbox
            ORDER BY created_at_ms ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.coerceIn(1, 100).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += SyncMutation(
                    mutationId = cursor.getString(0),
                    kind = cursor.getString(1),
                    recordId = cursor.getString(2),
                    baseVersion = cursor.getInt(3),
                    deleted = cursor.getInt(4) != 0,
                    payloadJson = cipher.decrypt(cursor.getString(5))
                )
            }
        }
        return result
    }

    @Synchronized
    fun findPendingMutation(mutationId: String): SyncMutation? {
        readableDatabase.rawQuery(
            """
            SELECT mutation_id, kind, record_id, base_version, deleted, encrypted_payload
            FROM outbox WHERE mutation_id = ? LIMIT 1
            """.trimIndent(),
            arrayOf(mutationId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return SyncMutation(
                mutationId = cursor.getString(0),
                kind = cursor.getString(1),
                recordId = cursor.getString(2),
                baseVersion = cursor.getInt(3),
                deleted = cursor.getInt(4) != 0,
                payloadJson = cipher.decrypt(cursor.getString(5))
            )
        }
    }

    @Synchronized
    fun acknowledgeMutationIds(mutationIds: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            mutationIds.forEach { db.delete("outbox", "mutation_id = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun applyServerChange(change: SyncChange) {
        val values = ContentValues().apply {
            put("kind", change.kind)
            put("record_id", change.recordId)
            put("encrypted_payload", cipher.encrypt(change.payloadJson))
            put("version", change.version)
            put("deleted", if (change.deleted) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun cursor(): Long {
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = 'cursor' LIMIT 1", emptyArray()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull() ?: 0L else 0L
        }
    }

    @Synchronized
    fun setCursor(value: Long) {
        val values = ContentValues().apply {
            put("key", "cursor")
            put("value", value.toString())
        }
        writableDatabase.insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun recordConflict(conflict: SyncConflict, local: SyncMutation) {
        val server = conflict.serverRecord
        writableDatabase.delete("conflicts", "kind = ? AND record_id = ?", arrayOf(conflict.kind, conflict.recordId))
        val values = ContentValues().apply {
            put("mutation_id", conflict.mutationId)
            put("kind", conflict.kind)
            put("record_id", conflict.recordId)
            put("local_base_version", local.baseVersion)
            put("local_deleted", if (local.deleted) 1 else 0)
            put("local_payload", cipher.encrypt(local.payloadJson))
            put("server_version", server?.version ?: 0)
            put("server_deleted", if (server?.deleted != false) 1 else 0)
            put("server_payload", cipher.encrypt(server?.payloadJson ?: "{}"))
            put("server_updated_at", server?.updatedAt.orEmpty())
            put("created_at_ms", System.currentTimeMillis())
        }
        writableDatabase.insertOrThrow("conflicts", null, values)
    }

    fun conflictCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM conflicts", emptyArray()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getConflict(conflictId: Long): StoredConflict? {
        readableDatabase.rawQuery(
            """
            SELECT conflict_id, mutation_id, kind, record_id, local_base_version,
                   local_deleted, local_payload, server_version, server_deleted,
                   server_payload, server_updated_at
            FROM conflicts WHERE conflict_id = ? LIMIT 1
            """.trimIndent(),
            arrayOf(conflictId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return StoredConflict(
                conflictId = cursor.getLong(0),
                mutationId = cursor.getString(1),
                kind = cursor.getString(2),
                recordId = cursor.getString(3),
                localBaseVersion = cursor.getInt(4),
                localDeleted = cursor.getInt(5) != 0,
                localPayloadJson = cipher.decrypt(cursor.getString(6)),
                serverVersion = cursor.getInt(7),
                serverDeleted = cursor.getInt(8) != 0,
                serverPayloadJson = cipher.decrypt(cursor.getString(9)),
                serverUpdatedAt = cursor.getString(10)
            )
        }
    }

    @Synchronized
    fun deleteConflict(conflictId: Long) {
        writableDatabase.delete("conflicts", "conflict_id = ?", arrayOf(conflictId.toString()))
    }

    @Synchronized
    fun deletePendingForRecord(kind: String, recordId: String) {
        writableDatabase.delete("outbox", "kind = ? AND record_id = ?", arrayOf(kind, recordId))
    }
}
