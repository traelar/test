package com.baylee.billnest.data

import com.baylee.billnest.model.*
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
        val session = sessionStore.load() ?: return@withLock SyncSummary(0, syncDb.conflictCount(), 0, syncDb.cursor())
        syncOneBatch(session)
    }

    suspend fun migrateLegacyIfNeeded(session: SessionData): SyncSummary = mutex.withLock {
        if (!session.isOwner || sessionStore.isLegacyMigrationComplete(session.householdId)) return@withLock syncOneBatch(session)
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
        if (syncDb.pendingMutations(1).isEmpty() && syncDb.conflictCount() == 0) sessionStore.markLegacyMigrationComplete(session.householdId)
        SyncSummary(applied, syncDb.conflictCount(), changes, latest.cursor)
    }

    suspend fun useServer(conflictId: Long): SyncSummary = mutex.withLock {
        val conflict = syncDb.getConflict(conflictId) ?: return@withLock SyncSummary(0, syncDb.conflictCount(), 0, syncDb.cursor())
        syncDb.deletePendingForRecord(conflict.kind, conflict.recordId)
        val change = SyncChange(syncDb.cursor(), conflict.kind, conflict.recordId, conflict.serverVersion, conflict.serverDeleted, conflict.serverPayloadJson, conflict.serverUpdatedAt)
        syncDb.applyServerChange(change)
        applyChangeToRepository(change)
        syncDb.deleteConflict(conflictId)
        SyncSummary(0, syncDb.conflictCount(), 1, syncDb.cursor())
    }

    suspend fun keepThisDevice(conflictId: Long): SyncSummary = mutex.withLock {
        val conflict = syncDb.getConflict(conflictId) ?: return@withLock SyncSummary(0, syncDb.conflictCount(), 0, syncDb.cursor())
        syncDb.deletePendingForRecord(conflict.kind, conflict.recordId)
        restoreLocalConflictPayload(conflict)
        syncDb.enqueue(conflict.kind, conflict.recordId, conflict.serverVersion, conflict.localDeleted, conflict.localPayloadJson)
        syncDb.deleteConflict(conflictId)
        syncOneBatch(sessionStore.load() ?: return@withLock SyncSummary(0, syncDb.conflictCount(), 0, syncDb.cursor()))
    }

    private suspend fun syncOneBatch(session: SessionData): SyncSummary {
        val pending = syncDb.pendingMutations(100)
        val pendingById = pending.associateBy { it.mutationId }
        val response = api.sync(repo.data.value.backendUrl, session.sessionToken, syncDb.cursor(), pending)
        if (response.applied.isNotEmpty()) syncDb.acknowledgeMutationIds(response.applied.map { it.mutationId })
        response.conflicts.forEach { conflict -> pendingById[conflict.mutationId]?.let { syncDb.recordConflict(conflict, it) } }
        if (response.conflicts.isNotEmpty()) syncDb.acknowledgeMutationIds(response.conflicts.map { it.mutationId })
        response.changes.forEach { change -> syncDb.applyServerChange(change); applyChangeToRepository(change) }
        syncDb.setCursor(response.cursor)
        return SyncSummary(response.applied.size, syncDb.conflictCount(), response.changes.size, response.cursor)
    }

    private fun enqueueIfNew(draft: SyncRecordDraft) {
        if (syncDb.currentVersion(draft.kind, draft.recordId) == 0) syncDb.enqueueCurrent(draft.kind, draft.recordId, false, draft.payloadJson)
    }

    private fun enqueueLegacySnapshot() {
        val d = repo.data.value
        d.bills.forEach { enqueueIfNew(SyncMapper.billMutation(it)) }
        d.paydays.forEach { enqueueIfNew(SyncMapper.paydayMutation(it)) }
        d.accounts.filter { it.source == AccountSource.MANUAL }.forEach { SyncMapper.accountMutation(it)?.let(::enqueueIfNew) }
        d.accountPreferences.forEach { enqueueIfNew(SyncMapper.accountPreferenceMutation(it)) }
        d.manualTransactions.forEach { enqueueIfNew(SyncMapper.manualTransactionMutation(it)) }
        d.budgets.forEach { enqueueIfNew(SyncMapper.budgetMutation(it)) }
        d.debts.forEach { enqueueIfNew(SyncMapper.debtMutation(it)) }
        d.savingsGoals.forEach { enqueueIfNew(SyncMapper.goalMutation(it)) }
        d.reservedFunds.forEach { enqueueIfNew(SyncMapper.reservedFundMutation(it)) }
        d.billMatches.forEach { enqueueIfNew(SyncMapper.billMatchMutation(it)) }
        d.subscriptionOverrides.forEach { enqueueIfNew(SyncMapper.subscriptionOverrideMutation(it)) }
        if (syncDb.currentVersion("settings", "household") == 0) enqueueIfNew(SyncMapper.settingsMutation(SharedSettings(d.reminderDays)))
    }

    private fun applyChangeToRepository(change: SyncChange) {
        val deletedId = if (change.deleted) change.recordId else null
        when (change.kind) {
            "bill" -> repo.applyRemoteBill(if (change.deleted) null else SyncMapper.decodeBill(change.payloadJson), deletedId)
            "payday" -> repo.applyRemotePayday(if (change.deleted) null else SyncMapper.decodePayday(change.payloadJson), deletedId)
            "manual_account" -> repo.applyRemoteManualAccount(if (change.deleted) null else SyncMapper.decodeAccount(change.payloadJson), deletedId)
            "account_preference" -> repo.applyRemoteAccountPreference(if (change.deleted) null else SyncMapper.decodeAccountPreference(change.payloadJson), deletedId)
            "manual_transaction" -> repo.applyRemoteManualTransaction(if (change.deleted) null else SyncMapper.decodeTransaction(change.payloadJson), deletedId)
            "budget" -> repo.applyRemoteBudget(if (change.deleted) null else SyncMapper.decodeBudget(change.payloadJson), deletedId)
            "debt" -> repo.applyRemoteDebt(if (change.deleted) null else SyncMapper.decodeDebt(change.payloadJson), deletedId)
            "goal" -> repo.applyRemoteGoal(if (change.deleted) null else SyncMapper.decodeGoal(change.payloadJson), deletedId)
            "reserved_fund" -> repo.applyRemoteReservedFund(if (change.deleted) null else SyncMapper.decodeReservedFund(change.payloadJson), deletedId)
            "bill_match" -> repo.applyRemoteBillMatch(if (change.deleted) null else SyncMapper.decodeBillMatch(change.payloadJson), deletedId)
            "subscription_override" -> repo.applyRemoteSubscriptionOverride(if (change.deleted) null else SyncMapper.decodeSubscriptionOverride(change.payloadJson), deletedId)
            "settings" -> if (!change.deleted) repo.applyRemoteSharedSettings(SyncMapper.decodeSettings(change.payloadJson))
        }
    }

    private fun restoreLocalConflictPayload(conflict: LocalSyncDb.StoredConflict) {
        applyChangeToRepository(SyncChange(syncDb.cursor(), conflict.kind, conflict.recordId, conflict.serverVersion, conflict.localDeleted, conflict.localPayloadJson, conflict.serverUpdatedAt))
    }
}
