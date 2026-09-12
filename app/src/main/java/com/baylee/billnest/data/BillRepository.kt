package com.baylee.billnest.data

import android.content.Context
import com.baylee.billnest.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

class BillRepository(
    context: Context,
    private val syncDb: LocalSyncDb? = null,
    private val onSyncNeeded: (() -> Unit)? = null
) {
    private val store = EncryptedStore(context)
    private val initialData = migrateLegacy(store.load()).also { store.save(it) }
    private val _data = MutableStateFlow(initialData)
    val data: StateFlow<AppData> = _data

    private fun migrateLegacy(data: AppData): AppData {
        val sourceAccounts = if (data.accounts.isNotEmpty()) {
            data.accounts
        } else {
            when {
                data.balances.isNotEmpty() -> data.balances.map { old ->
                    Account(
                        id = "plaid:${old.accountId}",
                        name = old.name,
                        type = AccountType.CHECKING,
                        balance = old.available ?: old.current,
                        source = AccountSource.PLAID,
                        plaidAccountId = old.accountId,
                        mask = old.mask,
                        updatedAtEpochMs = old.updatedAtEpochMs
                    )
                }
                data.manualBalance != 0.0 -> listOf(
                    Account(name = "Main Account", type = AccountType.CHECKING, balance = data.manualBalance)
                )
                else -> emptyList()
            }
        }

        val idMap = mutableMapOf<String, String>()
        val canonicalAccounts = sourceAccounts.map { account ->
            val canonicalId = AccountFinance.canonicalLocalId(account)
            if (canonicalId != account.id) idMap[account.id] = canonicalId
            account.copy(id = canonicalId)
        }
        val migratedBills = data.bills.map { bill ->
            val replacement = bill.accountId?.let(idMap::get)
            if (replacement == null) bill else bill.copy(accountId = replacement)
        }

        val preferenceByKey = data.accountPreferences.associateBy { it.accountKey }.toMutableMap()
        canonicalAccounts.forEachIndexed { index, account ->
            val key = AccountFinance.stableKey(account)
            if (!preferenceByKey.containsKey(key)) {
                val fallback = AccountFinance.defaultPreference(account, index)
                preferenceByKey[key] = if (account.source == AccountSource.PLAID) {
                    fallback.copy(customName = account.name)
                } else fallback
            }
        }

        val savedBackend = data.backendUrl.trim()
        val backend = when {
            savedBackend.isBlank() -> BILLNEST_BACKEND_URL
            savedBackend == "https://billnest-api.joshsolution.workers.dev" -> BILLNEST_BACKEND_URL
            else -> savedBackend
        }
        return data.copy(
            bills = migratedBills,
            accounts = canonicalAccounts,
            accountPreferences = preferenceByKey.values.sortedBy { it.displayOrder },
            backendUrl = backend
        )
    }

    private fun update(transform: (AppData) -> AppData) {
        val next = transform(_data.value)
        store.save(next)
        _data.value = next
    }

    private fun queue(draft: SyncRecordDraft?, deleted: Boolean = false) {
        if (draft == null) return
        syncDb?.enqueueCurrent(
            kind = draft.kind,
            recordId = draft.recordId,
            deleted = deleted,
            payloadJson = if (deleted) "{}" else draft.payloadJson
        )
        onSyncNeeded?.invoke()
    }

    private fun queueDelete(kind: String, recordId: String) {
        syncDb?.enqueueCurrent(kind, recordId, true, "{}")
        onSyncNeeded?.invoke()
    }

    private fun nextAccountOrder(data: AppData): Int =
        (data.accountPreferences.maxOfOrNull { it.displayOrder } ?: -1) + 1

    private fun upsertPreference(
        preferences: List<AccountPreference>,
        preference: AccountPreference
    ): List<AccountPreference> {
        val without = preferences.filterNot { it.accountKey == preference.accountKey }
        return (without + preference).sortedBy { it.displayOrder }
    }

    fun addBill(bill: Bill) {
        update { it.copy(bills = it.bills + bill) }
        queue(SyncMapper.billMutation(bill))
    }

    fun updateBill(bill: Bill) {
        update { data -> data.copy(bills = data.bills.map { if (it.id == bill.id) bill else it }) }
        queue(SyncMapper.billMutation(bill))
    }

    fun deleteBill(id: String) {
        update { it.copy(bills = it.bills.filterNot { bill -> bill.id == id }) }
        queueDelete("bill", id)
    }

    fun addPayday(payday: Payday) {
        update { it.copy(paydays = it.paydays + payday) }
        queue(SyncMapper.paydayMutation(payday))
    }

    fun updatePayday(payday: Payday) {
        update { data -> data.copy(paydays = data.paydays.map { if (it.id == payday.id) payday else it }) }
        queue(SyncMapper.paydayMutation(payday))
    }

    fun deletePayday(id: String) {
        update { it.copy(paydays = it.paydays.filterNot { payday -> payday.id == id }) }
        queueDelete("payday", id)
    }

    fun addAccount(account: Account) {
        val canonical = account.copy(id = AccountFinance.canonicalLocalId(account))
        val preference = AccountFinance.defaultPreference(canonical, nextAccountOrder(_data.value))
        update {
            it.copy(
                accounts = it.accounts + canonical,
                accountPreferences = upsertPreference(it.accountPreferences, preference)
            )
        }
        queue(SyncMapper.accountMutation(canonical))
        queue(SyncMapper.accountPreferenceMutation(preference))
    }

    fun updateAccount(account: Account) {
        val existing = _data.value.accounts.firstOrNull { it.id == account.id }
        val canonical = account.copy(id = AccountFinance.canonicalLocalId(account))
        if (existing?.source == AccountSource.PLAID || canonical.source == AccountSource.PLAID) {
            val key = AccountFinance.stableKey(canonical)
            val current = _data.value.accountPreferences.firstOrNull { it.accountKey == key }
                ?: AccountFinance.defaultPreference(canonical, nextAccountOrder(_data.value))
            val preference = current.copy(customName = canonical.name.trim().ifBlank { null })
            update { data ->
                data.copy(
                    accounts = data.accounts.map { if (AccountFinance.stableKey(it) == key) canonical else it },
                    accountPreferences = upsertPreference(data.accountPreferences, preference)
                )
            }
            queue(SyncMapper.accountPreferenceMutation(preference))
        } else {
            update { data -> data.copy(accounts = data.accounts.map { if (it.id == canonical.id) canonical else it }) }
            queue(SyncMapper.accountMutation(canonical))
        }
    }

    fun updateAccountPreference(preference: AccountPreference) {
        update { data -> data.copy(accountPreferences = upsertPreference(data.accountPreferences, preference)) }
        queue(SyncMapper.accountPreferenceMutation(preference))
    }

    fun moveAccount(accountKey: String, direction: Int) {
        if (direction == 0) return
        val data = _data.value
        val ordered = AccountFinance.sortAccounts(data.accounts, data.accountPreferences)
        val index = ordered.indexOfFirst { AccountFinance.stableKey(it) == accountKey }
        if (index == -1) return
        val target = (index + if (direction < 0) -1 else 1).coerceIn(0, ordered.lastIndex)
        if (target == index) return
        val mutable = ordered.toMutableList()
        val moved = mutable.removeAt(index)
        mutable.add(target, moved)
        val preferences = mutable.mapIndexed { order, account ->
            val current = AccountFinance.preferenceFor(account, data.accountPreferences, order)
            current.copy(displayOrder = order)
        }
        update { it.copy(accountPreferences = preferences) }
        preferences.forEach { queue(SyncMapper.accountPreferenceMutation(it)) }
    }

    fun deleteAccount(id: String) {
        val existing = _data.value.accounts.firstOrNull { it.id == id }
        val key = existing?.let(AccountFinance::stableKey)
        update { data ->
            data.copy(
                accounts = data.accounts.filterNot { it.id == id },
                accountPreferences = if (key == null) data.accountPreferences else data.accountPreferences.filterNot { it.accountKey == key },
                bills = data.bills.map { if (it.accountId == id) it.copy(accountId = null) else it }
            )
        }
        if (existing?.source == AccountSource.MANUAL) queueDelete("manual_account", id)
        if (key != null) queueDelete("account_preference", key)
    }

    fun syncPlaidAccounts(incoming: List<Account>, connectedItems: Int? = null) {
        val before = _data.value
        val existingPlaid = before.accounts.filter { it.source == AccountSource.PLAID }.associateBy { it.plaidAccountId }
        val canonicalIncoming = incoming.map { fresh ->
            fresh.copy(id = AccountFinance.canonicalLocalId(fresh))
        }
        val legacyIdMap = existingPlaid.mapNotNull { (plaidId, existing) ->
            val canonical = canonicalIncoming.firstOrNull { it.plaidAccountId == plaidId }?.id
            if (canonical != null && canonical != existing.id) existing.id to canonical else null
        }.toMap()

        val changedBills = before.bills.map { bill ->
            val replacement = bill.accountId?.let(legacyIdMap::get)
            if (replacement == null) bill else bill.copy(accountId = replacement)
        }

        val newPreferences = mutableListOf<AccountPreference>()
        update { data ->
            val manual = data.accounts.filter { it.source == AccountSource.MANUAL }
            var preferences = data.accountPreferences
            var nextOrder = nextAccountOrder(data)
            canonicalIncoming.forEach { account ->
                val key = AccountFinance.stableKey(account)
                if (preferences.none { it.accountKey == key }) {
                    val pref = AccountFinance.defaultPreference(account, nextOrder++)
                    preferences = upsertPreference(preferences, pref)
                    newPreferences += pref
                }
            }
            data.copy(
                accounts = manual + canonicalIncoming,
                accountPreferences = preferences,
                bills = changedBills,
                plaidConnected = connectedItems?.let { it > 0 } ?: canonicalIncoming.isNotEmpty()
            )
        }
        changedBills.filter { changed -> before.bills.firstOrNull { it.id == changed.id } != changed }
            .forEach { queue(SyncMapper.billMutation(it)) }
        newPreferences.forEach { queue(SyncMapper.accountPreferenceMutation(it)) }
    }

    fun setBackendUrl(value: String) = update { it.copy(backendUrl = value.trim().ifBlank { BILLNEST_BACKEND_URL }) }
    fun setBackendApiKey(value: String) = update { it.copy(backendApiKey = value.trim()) }
    fun setManualBalance(value: Double) = update { it.copy(manualBalance = value) }
    fun setBalances(items: List<AccountBalance>, connected: Boolean = true) =
        update { it.copy(balances = items, plaidConnected = connected) }
    fun setBiometric(enabled: Boolean) = update { it.copy(biometricLock = enabled) }

    fun setReminderDays(days: List<Int>) {
        val normalized = days.distinct().sortedDescending()
        update { it.copy(reminderDays = normalized) }
        queue(SyncMapper.settingsMutation(SharedSettings(normalized)))
    }

    fun markPaid(id: String) {
        update { data ->
            data.copy(bills = data.bills.map { bill ->
                if (bill.id != id) bill else {
                    val due = bill.dueDate()
                    val paid = (bill.paidDates + due.toString()).distinct()
                    if (bill.frequency == Frequency.ONE_TIME) bill.copy(paidDates = paid)
                    else bill.copy(dueDateIso = advance(due, bill.frequency).toString(), paidDates = paid)
                }
            })
        }
        _data.value.bills.firstOrNull { it.id == id }?.let { queue(SyncMapper.billMutation(it)) }
    }

    fun applyRemoteBill(bill: Bill?, deletedId: String?) {
        update { data ->
            when {
                bill != null -> data.copy(bills = upsert(data.bills, bill.id, bill) { it.id })
                !deletedId.isNullOrBlank() -> data.copy(bills = data.bills.filterNot { it.id == deletedId })
                else -> data
            }
        }
    }

    fun applyRemotePayday(payday: Payday?, deletedId: String?) {
        update { data ->
            when {
                payday != null -> data.copy(paydays = upsert(data.paydays, payday.id, payday) { it.id })
                !deletedId.isNullOrBlank() -> data.copy(paydays = data.paydays.filterNot { it.id == deletedId })
                else -> data
            }
        }
    }

    fun applyRemoteManualAccount(account: Account?, deletedId: String?) {
        update { data ->
            when {
                account != null -> {
                    val canonical = account.copy(id = AccountFinance.canonicalLocalId(account))
                    val without = data.accounts.filterNot { it.id == canonical.id }
                    data.copy(accounts = without + canonical.copy(source = AccountSource.MANUAL))
                }
                !deletedId.isNullOrBlank() -> data.copy(
                    accounts = data.accounts.filterNot { it.id == deletedId },
                    bills = data.bills.map { if (it.accountId == deletedId) it.copy(accountId = null) else it }
                )
                else -> data
            }
        }
    }

    fun applyRemoteAccountPreference(preference: AccountPreference?, deletedKey: String?) {
        update { data ->
            when {
                preference != null -> data.copy(accountPreferences = upsertPreference(data.accountPreferences, preference))
                !deletedKey.isNullOrBlank() -> data.copy(
                    accountPreferences = data.accountPreferences.filterNot { it.accountKey == deletedKey }
                )
                else -> data
            }
        }
    }

    fun applyRemoteSharedSettings(settings: SharedSettings) {
        update { it.copy(reminderDays = settings.reminderDays.distinct().sortedDescending()) }
    }

    private fun <T> upsert(items: List<T>, id: String, value: T, idOf: (T) -> String): List<T> {
        var replaced = false
        val mapped = items.map {
            if (idOf(it) == id) {
                replaced = true
                value
            } else it
        }
        return if (replaced) mapped else mapped + value
    }

    private fun advance(d: LocalDate, f: Frequency): LocalDate = when (f) {
        Frequency.ONE_TIME -> d
        Frequency.WEEKLY -> d.plusWeeks(1)
        Frequency.BIWEEKLY -> d.plusWeeks(2)
        Frequency.MONTHLY -> d.plusMonths(1)
        Frequency.YEARLY -> d.plusYears(1)
    }
}
