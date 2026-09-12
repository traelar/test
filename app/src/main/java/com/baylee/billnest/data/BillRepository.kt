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

    private fun upsertPreference(preferences: List<AccountPreference>, preference: AccountPreference): List<AccountPreference> {
        val without = preferences.filterNot { it.accountKey == preference.accountKey }
        return (without + preference).sortedBy { it.displayOrder }
    }

    fun addBill(bill: Bill) { update { it.copy(bills = it.bills + bill) }; queue(SyncMapper.billMutation(bill)) }
    fun updateBill(bill: Bill) { update { d -> d.copy(bills = d.bills.map { if (it.id == bill.id) bill else it }) }; queue(SyncMapper.billMutation(bill)) }
    fun deleteBill(id: String) { update { it.copy(bills = it.bills.filterNot { b -> b.id == id }) }; queueDelete("bill", id) }

    fun addPayday(payday: Payday) { update { it.copy(paydays = it.paydays + payday) }; queue(SyncMapper.paydayMutation(payday)) }
    fun updatePayday(payday: Payday) { update { d -> d.copy(paydays = d.paydays.map { if (it.id == payday.id) payday else it }) }; queue(SyncMapper.paydayMutation(payday)) }
    fun deletePayday(id: String) { update { it.copy(paydays = it.paydays.filterNot { p -> p.id == id }) }; queueDelete("payday", id) }

    fun addAccount(account: Account) {
        val canonical = account.copy(id = AccountFinance.canonicalLocalId(account))
        val preference = AccountFinance.defaultPreference(canonical, nextAccountOrder(_data.value))
        update { it.copy(accounts = it.accounts + canonical, accountPreferences = upsertPreference(it.accountPreferences, preference)) }
        queue(SyncMapper.accountMutation(canonical)); queue(SyncMapper.accountPreferenceMutation(preference))
    }

    fun updateAccount(account: Account) {
        val existing = _data.value.accounts.firstOrNull { it.id == account.id }
        val canonical = account.copy(id = AccountFinance.canonicalLocalId(account))
        if (existing?.source == AccountSource.PLAID || canonical.source == AccountSource.PLAID) {
            val key = AccountFinance.stableKey(canonical)
            val current = _data.value.accountPreferences.firstOrNull { it.accountKey == key }
                ?: AccountFinance.defaultPreference(canonical, nextAccountOrder(_data.value))
            val preference = current.copy(customName = canonical.name.trim().ifBlank { null })
            update { d -> d.copy(accounts = d.accounts.map { if (AccountFinance.stableKey(it) == key) canonical else it }, accountPreferences = upsertPreference(d.accountPreferences, preference)) }
            queue(SyncMapper.accountPreferenceMutation(preference))
        } else {
            update { d -> d.copy(accounts = d.accounts.map { if (it.id == canonical.id) canonical else it }) }
            queue(SyncMapper.accountMutation(canonical))
        }
    }

    fun updateAccountPreference(preference: AccountPreference) {
        update { d -> d.copy(accountPreferences = upsertPreference(d.accountPreferences, preference)) }
        queue(SyncMapper.accountPreferenceMutation(preference))
    }

    fun moveAccount(accountKey: String, direction: Int) {
        if (direction == 0) return
        val current = _data.value
        val ordered = AccountFinance.sortAccounts(current.accounts, current.accountPreferences)
        val index = ordered.indexOfFirst { AccountFinance.stableKey(it) == accountKey }
        if (index == -1) return
        val target = (index + if (direction < 0) -1 else 1).coerceIn(0, ordered.lastIndex)
        if (target == index) return
        val mutable = ordered.toMutableList()
        val moved = mutable.removeAt(index); mutable.add(target, moved)
        val preferences = mutable.mapIndexed { order, account ->
            AccountFinance.preferenceFor(account, current.accountPreferences, order).copy(displayOrder = order)
        }
        update { it.copy(accountPreferences = preferences) }
        preferences.forEach { queue(SyncMapper.accountPreferenceMutation(it)) }
    }

    fun deleteAccount(id: String) {
        val existing = _data.value.accounts.firstOrNull { it.id == id }
        val key = existing?.let(AccountFinance::stableKey)
        update { d -> d.copy(
            accounts = d.accounts.filterNot { it.id == id },
            accountPreferences = if (key == null) d.accountPreferences else d.accountPreferences.filterNot { it.accountKey == key },
            bills = d.bills.map { if (it.accountId == id) it.copy(accountId = null) else it }
        ) }
        if (existing?.source == AccountSource.MANUAL) queueDelete("manual_account", id)
        if (key != null) queueDelete("account_preference", key)
    }

    fun syncPlaidAccounts(incoming: List<Account>, connectedItems: Int? = null) {
        val before = _data.value
        val existingPlaid = before.accounts.filter { it.source == AccountSource.PLAID }.associateBy { it.plaidAccountId }
        val canonicalIncoming = incoming.map { it.copy(id = AccountFinance.canonicalLocalId(it)) }
        val legacyIdMap = existingPlaid.mapNotNull { (plaidId, existing) ->
            val canonical = canonicalIncoming.firstOrNull { it.plaidAccountId == plaidId }?.id
            if (canonical != null && canonical != existing.id) existing.id to canonical else null
        }.toMap()
        val changedBills = before.bills.map { bill ->
            val replacement = bill.accountId?.let(legacyIdMap::get)
            if (replacement == null) bill else bill.copy(accountId = replacement)
        }
        val newPreferences = mutableListOf<AccountPreference>()
        update { d ->
            val manual = d.accounts.filter { it.source == AccountSource.MANUAL }
            var preferences = d.accountPreferences
            var nextOrder = nextAccountOrder(d)
            canonicalIncoming.forEach { account ->
                val key = AccountFinance.stableKey(account)
                if (preferences.none { it.accountKey == key }) {
                    val pref = AccountFinance.defaultPreference(account, nextOrder++)
                    preferences = upsertPreference(preferences, pref); newPreferences += pref
                }
            }
            d.copy(accounts = manual + canonicalIncoming, accountPreferences = preferences, bills = changedBills, plaidConnected = connectedItems?.let { it > 0 } ?: canonicalIncoming.isNotEmpty())
        }
        changedBills.filter { changed -> before.bills.firstOrNull { it.id == changed.id } != changed }.forEach { queue(SyncMapper.billMutation(it)) }
        newPreferences.forEach { queue(SyncMapper.accountPreferenceMutation(it)) }
    }

    fun addManualTransaction(value: FinanceTransaction) {
        val normalized = value.copy(source = TransactionSource.MANUAL, plaidTransactionId = null)
        update { it.copy(manualTransactions = it.manualTransactions + normalized) }
        queue(SyncMapper.manualTransactionMutation(normalized))
    }
    fun updateManualTransaction(value: FinanceTransaction) { update { d -> d.copy(manualTransactions = d.manualTransactions.map { if (it.id == value.id) value.copy(source = TransactionSource.MANUAL) else it }) }; queue(SyncMapper.manualTransactionMutation(value.copy(source = TransactionSource.MANUAL))) }
    fun deleteManualTransaction(id: String) { update { it.copy(manualTransactions = it.manualTransactions.filterNot { t -> t.id == id }) }; queueDelete("manual_transaction", id) }
    fun setPlaidTransactions(values: List<FinanceTransaction>) = update { it.copy(plaidTransactions = values.map { tx -> tx.copy(source = TransactionSource.PLAID) }) }

    fun addBudget(value: Budget) { update { it.copy(budgets = it.budgets + value) }; queue(SyncMapper.budgetMutation(value)) }
    fun updateBudget(value: Budget) { update { d -> d.copy(budgets = upsert(d.budgets, value.id, value) { it.id }) }; queue(SyncMapper.budgetMutation(value)) }
    fun deleteBudget(id: String) { update { it.copy(budgets = it.budgets.filterNot { v -> v.id == id }) }; queueDelete("budget", id) }

    fun addDebt(value: Debt) { update { it.copy(debts = it.debts + value) }; queue(SyncMapper.debtMutation(value)) }
    fun updateDebt(value: Debt) { update { d -> d.copy(debts = upsert(d.debts, value.id, value) { it.id }) }; queue(SyncMapper.debtMutation(value)) }
    fun deleteDebt(id: String) { update { it.copy(debts = it.debts.filterNot { v -> v.id == id }) }; queueDelete("debt", id) }

    fun addSavingsGoal(value: SavingsGoal) { update { it.copy(savingsGoals = it.savingsGoals + value) }; queue(SyncMapper.goalMutation(value)) }
    fun updateSavingsGoal(value: SavingsGoal) { update { d -> d.copy(savingsGoals = upsert(d.savingsGoals, value.id, value) { it.id }) }; queue(SyncMapper.goalMutation(value)) }
    fun deleteSavingsGoal(id: String) { update { it.copy(savingsGoals = it.savingsGoals.filterNot { v -> v.id == id }) }; queueDelete("goal", id) }

    fun addReservedFund(value: ReservedFund) { update { it.copy(reservedFunds = it.reservedFunds + value) }; queue(SyncMapper.reservedFundMutation(value)) }
    fun updateReservedFund(value: ReservedFund) { update { d -> d.copy(reservedFunds = upsert(d.reservedFunds, value.id, value) { it.id }) }; queue(SyncMapper.reservedFundMutation(value)) }
    fun deleteReservedFund(id: String) { update { it.copy(reservedFunds = it.reservedFunds.filterNot { v -> v.id == id }) }; queueDelete("reserved_fund", id) }

    fun upsertBillMatch(value: BillTransactionMatch) { update { d -> d.copy(billMatches = upsert(d.billMatches, value.id, value) { it.id }) }; queue(SyncMapper.billMatchMutation(value)) }
    fun upsertSubscriptionOverride(value: SubscriptionOverride) { update { d -> d.copy(subscriptionOverrides = upsert(d.subscriptionOverrides, value.merchantKey, value) { it.merchantKey }) }; queue(SyncMapper.subscriptionOverrideMutation(value)) }

    fun setBackendUrl(value: String) = update { it.copy(backendUrl = value.trim().ifBlank { BILLNEST_BACKEND_URL }) }
    fun setBackendApiKey(value: String) = update { it.copy(backendApiKey = value.trim()) }
    fun setManualBalance(value: Double) = update { it.copy(manualBalance = value) }
    fun setBalances(items: List<AccountBalance>, connected: Boolean = true) = update { it.copy(balances = items, plaidConnected = connected) }
    fun setBiometric(enabled: Boolean) = update { it.copy(biometricLock = enabled) }

    fun setReminderDays(days: List<Int>) {
        val normalized = days.distinct().sortedDescending()
        update { it.copy(reminderDays = normalized) }
        queue(SyncMapper.settingsMutation(SharedSettings(normalized)))
    }

    fun markPaid(id: String) {
        update { d -> d.copy(bills = d.bills.map { bill ->
            if (bill.id != id) bill else {
                val due = bill.dueDate(); val paid = (bill.paidDates + due.toString()).distinct()
                if (bill.frequency == Frequency.ONE_TIME) bill.copy(paidDates = paid)
                else bill.copy(dueDateIso = advance(due, bill.frequency).toString(), paidDates = paid)
            }
        }) }
        _data.value.bills.firstOrNull { it.id == id }?.let { queue(SyncMapper.billMutation(it)) }
    }

    fun applyRemoteBill(value: Bill?, deletedId: String?) = update { d -> when { value != null -> d.copy(bills = upsert(d.bills, value.id, value) { it.id }); !deletedId.isNullOrBlank() -> d.copy(bills = d.bills.filterNot { it.id == deletedId }); else -> d } }
    fun applyRemotePayday(value: Payday?, deletedId: String?) = update { d -> when { value != null -> d.copy(paydays = upsert(d.paydays, value.id, value) { it.id }); !deletedId.isNullOrBlank() -> d.copy(paydays = d.paydays.filterNot { it.id == deletedId }); else -> d } }
    fun applyRemoteManualAccount(value: Account?, deletedId: String?) = update { d -> when {
        value != null -> { val canonical = value.copy(id = AccountFinance.canonicalLocalId(value), source = AccountSource.MANUAL); d.copy(accounts = d.accounts.filterNot { it.id == canonical.id } + canonical) }
        !deletedId.isNullOrBlank() -> d.copy(accounts = d.accounts.filterNot { it.id == deletedId }, bills = d.bills.map { if (it.accountId == deletedId) it.copy(accountId = null) else it })
        else -> d
    } }
    fun applyRemoteAccountPreference(value: AccountPreference?, deletedKey: String?) = update { d -> when { value != null -> d.copy(accountPreferences = upsertPreference(d.accountPreferences, value)); !deletedKey.isNullOrBlank() -> d.copy(accountPreferences = d.accountPreferences.filterNot { it.accountKey == deletedKey }); else -> d } }
    fun applyRemoteManualTransaction(value: FinanceTransaction?, deletedId: String?) = update { d -> when { value != null -> d.copy(manualTransactions = upsert(d.manualTransactions, value.id, value.copy(source = TransactionSource.MANUAL)) { it.id }); !deletedId.isNullOrBlank() -> d.copy(manualTransactions = d.manualTransactions.filterNot { it.id == deletedId }); else -> d } }
    fun applyRemoteBudget(value: Budget?, deletedId: String?) = update { d -> when { value != null -> d.copy(budgets = upsert(d.budgets, value.id, value) { it.id }); !deletedId.isNullOrBlank() -> d.copy(budgets = d.budgets.filterNot { it.id == deletedId }); else -> d } }
    fun applyRemoteDebt(value: Debt?, deletedId: String?) = update { d -> when { value != null -> d.copy(debts = upsert(d.debts, value.id, value) { it.id }); !deletedId.isNullOrBlank() -> d.copy(debts = d.debts.filterNot { it.id == deletedId }); else -> d } }
    fun applyRemoteGoal(value: SavingsGoal?, deletedId: String?) = update { d -> when { value != null -> d.copy(savingsGoals = upsert(d.savingsGoals, value.id, value) { it.id }); !deletedId.isNullOrBlank() -> d.copy(savingsGoals = d.savingsGoals.filterNot { it.id == deletedId }); else -> d } }
    fun applyRemoteReservedFund(value: ReservedFund?, deletedId: String?) = update { d -> when { value != null -> d.copy(reservedFunds = upsert(d.reservedFunds, value.id, value) { it.id }); !deletedId.isNullOrBlank() -> d.copy(reservedFunds = d.reservedFunds.filterNot { it.id == deletedId }); else -> d } }
    fun applyRemoteBillMatch(value: BillTransactionMatch?, deletedId: String?) = update { d -> when { value != null -> d.copy(billMatches = upsert(d.billMatches, value.id, value) { it.id }); !deletedId.isNullOrBlank() -> d.copy(billMatches = d.billMatches.filterNot { it.id == deletedId }); else -> d } }
    fun applyRemoteSubscriptionOverride(value: SubscriptionOverride?, deletedKey: String?) = update { d -> when { value != null -> d.copy(subscriptionOverrides = upsert(d.subscriptionOverrides, value.merchantKey, value) { it.merchantKey }); !deletedKey.isNullOrBlank() -> d.copy(subscriptionOverrides = d.subscriptionOverrides.filterNot { it.merchantKey == deletedKey }); else -> d } }
    fun applyRemoteSharedSettings(settings: SharedSettings) { update { it.copy(reminderDays = settings.reminderDays.distinct().sortedDescending()) } }

    private fun <T> upsert(items: List<T>, id: String, value: T, idOf: (T) -> String): List<T> {
        var replaced = false
        val mapped = items.map { if (idOf(it) == id) { replaced = true; value } else it }
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
