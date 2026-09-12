package com.baylee.billnest.data

import com.baylee.billnest.model.AccountSource
import com.baylee.billnest.model.SessionData
import com.baylee.billnest.model.SharedSettings
import com.baylee.billnest.model.SyncChange
import com.baylee.billnest.model.SyncMapper
import com.baylee.billnest.model.SyncSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HouseholdSyncRepository(
    private val repo: BillRepository,
    private val syncDb: LocalSyncDb,
    private val sessionStore: SecureSessionStore,
    private val api: SyncApi = SyncApi()
) {
    private val mutex = Mutex()

    val conflictCount: Int get() = syncDb.conflictCount()

    suspend fun syncNow(): SyncSummary = mutex.withLock {
        val session = sessionStore.load()
            ?: return@withLock SyncSummary(0, syncDb.conflictCount(), 0, syncDb.cursor())
        syncOneBatch(session)
    }

    suspend fun migrateLegacyIfNeeded(session: SessionData): SyncSummary = mutex.withLock {
        if (!session.isOwner || sessionStore.isLegacyMigrationComplete(session.householdId)) {
            return@withLock syncOneBatch(session)
        }

        enqueueLegacySnapshot()

        var applied = 0
        var changes = 0
        var latest = SyncSummary(0, syncDb.conflictCount(), 0, syncDb.cursor())
        var passes = 0
        do {
            latest = syncOneBatch(session)
            applied += latest.appliedCount
            changes += latest.changeCount
            passes += 1
        } while (syncDb.pendingMutations(1).isNotEmpty() && passes < 20)

        if (syncDb.pendingMutations(1).isEmpty() && syncDb.conflictCount() == 0) {
            sessionStore.markLegacyMigrationComplete(session.householdId)
        }

        SyncSummary(
            appliedCount = applied,
            conflictCount = syncDb.conflictCount(),
            changeCount = changes,
            cursor = latest.cursor
        )
    }

    suspend fun useServer(conflictId: Long): SyncSummary = mutex.withLock {
        val conflict = syncDb.getConflict(conflictId)
            ?: return@withLock SyncSummary(0, syncDb.conflictCount(), 0, syncDb.cursor())

        syncDb.deletePendingForRecord(conflict.kind, conflict.recordId)
        val change = SyncChange(
            eventId = syncDb.cursor(),
            kind = conflict.kind,
            recordId = conflict.recordId,
            version = conflict.serverVersion,
            deleted = conflict.serverDeleted,
            payloadJson = conflict.serverPayloadJson,
            updatedAt = conflict.serverUpdatedAt
        )
        syncDb.applyServerChange(change)
        applyChangeToRepository(change)
        syncDb.deleteConflict(conflictId)
        SyncSummary(0, syncDb.conflictCount(), 1, syncDb.cursor())
    }

    suspend fun keepThisDevice(conflictId: Long): SyncSummary = mutex.withLock {
        val conflict = syncDb.getConflict(conflictId)
            ?: return@withLock SyncSummary(0, syncDb.conflictCount(), 0, syncDb.cursor())

        syncDb.deletePendingForRecord(conflict.kind, conflict.recordId)
        restoreLocalConflictPayload(conflict)
        syncDb.enqueue(
            kind = conflict.kind,
            recordId = conflict.recordId,
            baseVersion = conflict.serverVersion,
            deleted = conflict.localDeleted,
            payloadJson = conflict.localPayloadJson
        )
        syncDb.deleteConflict(conflictId)
        syncOneBatch(sessionStore.load() ?: return@withLock SyncSummary(0, syncDb.conflictCount(), 0, syncDb.cursor()))
    }

    private suspend fun syncOneBatch(session: SessionData): SyncSummary {
        val pending = syncDb.pendingMutations(100)
        val pendingById = pending.associateBy { it.mutationId }
        val response = api.sync(
            backendUrl = repo.data.value.backendUrl,
            sessionToken = session.sessionToken,
            sinceEventId = syncDb.cursor(),
            mutations = pending
        )

        if (response.applied.isNotEmpty()) {
            syncDb.acknowledgeMutationIds(response.applied.map { it.mutationId })
        }

        if (response.conflicts.isNotEmpty()) {
            response.conflicts.forEach { conflict ->
                pendingById[conflict.mutationId]?.let { local -> syncDb.recordConflict(conflict, local) }
            }
            syncDb.acknowledgeMutationIds(response.conflicts.map { it.mutationId })
        }

        response.changes.forEach { change ->
            syncDb.applyServerChange(change)
            applyChangeToRepository(change)
        }
        syncDb.setCursor(response.cursor)

        return SyncSummary(
            appliedCount = response.applied.size,
            conflictCount = syncDb.conflictCount(),
            changeCount = response.changes.size,
            cursor = response.cursor
        )
    }

    private fun enqueueLegacySnapshot() {
        val data = repo.data.value
        data.bills.forEach { bill ->
            if (syncDb.currentVersion("bill", bill.id) == 0) {
                val draft = SyncMapper.billMutation(bill)
                syncDb.enqueueCurrent(draft.kind, draft.recordId, false, draft.payloadJson)
            }
        }
        data.paydays.forEach { payday ->
            if (syncDb.currentVersion("payday", payday.id) == 0) {
                val draft = SyncMapper.paydayMutation(payday)
                syncDb.enqueueCurrent(draft.kind, draft.recordId, false, draft.payloadJson)
            }
        }
        data.accounts.filter { it.source == AccountSource.MANUAL }.forEach { account ->
            if (syncDb.currentVersion("manual_account", account.id) == 0) {
                SyncMapper.accountMutation(account)?.let { draft ->
                    syncDb.enqueueCurrent(draft.kind, draft.recordId, false, draft.payloadJson)
                }
            }
        }
        data.accountPreferences.forEach { preference ->
            if (syncDb.currentVersion("account_preference", preference.accountKey) == 0) {
                val draft = SyncMapper.accountPreferenceMutation(preference)
                syncDb.enqueueCurrent(draft.kind, draft.recordId, false, draft.payloadJson)
            }
        }
        if (syncDb.currentVersion("settings", "household") == 0) {
            val settings = SyncMapper.settingsMutation(SharedSettings(data.reminderDays))
            syncDb.enqueueCurrent(settings.kind, settings.recordId, false, settings.payloadJson)
        }
    }

    private fun applyChangeToRepository(change: SyncChange) {
        when (change.kind) {
            "bill" -> repo.applyRemoteBill(
                bill = if (change.deleted) null else SyncMapper.decodeBill(change.payloadJson),
                deletedId = if (change.deleted) change.recordId else null
            )
            "payday" -> repo.applyRemotePayday(
                payday = if (change.deleted) null else SyncMapper.decodePayday(change.payloadJson),
                deletedId = if (change.deleted) change.recordId else null
            )
            "manual_account" -> repo.applyRemoteManualAccount(
                account = if (change.deleted) null else SyncMapper.decodeAccount(change.payloadJson),
                deletedId = if (change.deleted) change.recordId else null
            )
            "account_preference" -> repo.applyRemoteAccountPreference(
                preference = if (change.deleted) null else SyncMapper.decodeAccountPreference(change.payloadJson),
                deletedKey = if (change.deleted) change.recordId else null
            )
            "settings" -> if (!change.deleted) {
                repo.applyRemoteSharedSettings(SyncMapper.decodeSettings(change.payloadJson))
            }
        }
    }

    private fun restoreLocalConflictPayload(conflict: LocalSyncDb.StoredConflict) {
        val change = SyncChange(
            eventId = syncDb.cursor(),
            kind = conflict.kind,
            recordId = conflict.recordId,
            version = conflict.serverVersion,
            deleted = conflict.localDeleted,
            payloadJson = conflict.localPayloadJson,
            updatedAt = conflict.serverUpdatedAt
        )
        applyChangeToRepository(change)
    }
}
