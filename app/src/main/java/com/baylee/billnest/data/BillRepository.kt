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
        val importedAccounts = if (data.accounts.isNotEmpty()) {
            data.accounts
        } else {
            when {
                data.balances.isNotEmpty() -> data.balances.map { old ->
                    Account(
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

        val savedBackend = data.backendUrl.trim()
        val backend = when {
            savedBackend.isBlank() -> BILLNEST_BACKEND_URL
            savedBackend == "https://billnest-api.joshsolution.workers.dev" -> BILLNEST_BACKEND_URL
            else -> savedBackend
        }
        return data.copy(accounts = importedAccounts, backendUrl = backend)
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
        update { it.copy(accounts = it.accounts + account) }
        queue(SyncMapper.accountMutation(account))
    }

    fun moveAccount(accountId: String, direction: Int) {
        update { data ->
            val ordered = data.accounts.sortedWith(compareBy<Account> { it.displayOrder }.thenBy { it.name }).toMutableList()
            val from = ordered.indexOfFirst { it.id == accountId }
            val to = (from + direction).coerceIn(0, ordered.lastIndex)
            if (from < 0 || from == to) return@update data
            val moved = ordered.removeAt(from)
            ordered.add(to, moved)
            data.copy(accounts = ordered.mapIndexed { index, account -> account.copy(displayOrder = index) })
        }
        _data.value.accounts.filter { it.source == AccountSource.MANUAL }.forEach { queue(SyncMapper.accountMutation(it)) }
    }

    fun updateAccount(account: Account) {
        update { data -> data.copy(accounts = data.accounts.map { if (it.id == account.id) account else it }) }
        queue(SyncMapper.accountMutation(account))
    }

    fun deleteAccount(id: String) {
        val existing = _data.value.accounts.firstOrNull { it.id == id }
        update { data ->
            data.copy(
                accounts = data.accounts.filterNot { it.id == id },
                bills = data.bills.map { if (it.accountId == id) it.copy(accountId = null) else it }
            )
        }
        if (existing?.source == AccountSource.MANUAL) queueDelete("manual_account", id)
    }

    fun saveTransaction(value: FinanceTransaction) = update { data ->
        data.copy(transactions = upsert(data.transactions, value.id, value) { it.id })
    }
    fun deleteTransaction(id: String) = update { it.copy(transactions = it.transactions.filterNot { row -> row.id == id }) }
    fun saveBudget(value: Budget) = update { data -> data.copy(budgets = upsert(data.budgets, value.id, value) { it.id }) }
    fun deleteBudget(id: String) = update { it.copy(budgets = it.budgets.filterNot { row -> row.id == id }) }
    fun saveDebt(value: Debt) = update { data -> data.copy(debts = upsert(data.debts, value.id, value) { it.id }) }
    fun deleteDebt(id: String) = update { it.copy(debts = it.debts.filterNot { row -> row.id == id }) }
    fun saveGoal(value: SavingsGoal) = update { data -> data.copy(savingsGoals = upsert(data.savingsGoals, value.id, value) { it.id }) }
    fun deleteGoal(id: String) = update { it.copy(savingsGoals = it.savingsGoals.filterNot { row -> row.id == id }) }
    fun saveReservedFund(value: ReservedFund) = update { data ->
        data.copy(reservedFunds = upsert(data.reservedFunds, value.id, value.copy(amount = value.amount.coerceAtLeast(0.0))) { it.id })
    }
    fun deleteReservedFund(id: String) = update { it.copy(reservedFunds = it.reservedFunds.filterNot { row -> row.id == id }) }
    fun fundReserved(id: String, amount: Double) = update { data ->
        data.copy(reservedFunds = data.reservedFunds.map { if (it.id == id) it.copy(amount = (it.amount + amount).coerceAtLeast(0.0)) else it })
    }

    fun syncPlaidAccounts(incoming: List<Account>) = update { data ->
        val manual = data.accounts.filter { it.source == AccountSource.MANUAL }
        val existingPlaid = data.accounts.filter { it.source == AccountSource.PLAID }
            .associateBy { it.plaidAccountId }
        val synced = incoming.map { fresh ->
            val existing = existingPlaid[fresh.plaidAccountId]
            if (existing == null) fresh else fresh.copy(
                id = existing.id,
                name = existing.name,
                role = existing.role,
                includeInSpendable = existing.includeInSpendable,
                displayOrder = existing.displayOrder
            )
        }
        data.copy(accounts = (manual + synced).sortedWith(compareBy<Account> { it.displayOrder }.thenBy { it.name }), plaidConnected = synced.isNotEmpty())
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
            val paidBill = data.bills.firstOrNull { it.id == id }
            data.copy(
                bills = data.bills.map { bill ->
                    if (bill.id != id) bill else {
                        val due = bill.dueDate()
                        val paid = (bill.paidDates + due.toString()).distinct()
                        if (bill.frequency == Frequency.ONE_TIME) bill.copy(paidDates = paid)
                        else bill.copy(dueDateIso = advance(due, bill.frequency).toString(), paidDates = paid)
                    }
                },
                reservedFunds = data.reservedFunds.map { fund ->
                    if (fund.billId == id && fund.consumeWhenBillPaid && paidBill != null) fund.copy(amount = (fund.amount - paidBill.amount).coerceAtLeast(0.0)) else fund
                }
            )
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
                    val without = data.accounts.filterNot { it.id == account.id }
                    data.copy(accounts = without + account.copy(source = AccountSource.MANUAL))
                }
                !deletedId.isNullOrBlank() -> data.copy(
                    accounts = data.accounts.filterNot { it.id == deletedId },
                    bills = data.bills.map { if (it.accountId == deletedId) it.copy(accountId = null) else it }
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
