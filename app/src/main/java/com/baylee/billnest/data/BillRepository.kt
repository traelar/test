package com.baylee.billnest.data

import android.content.Context
import com.baylee.billnest.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

class BillRepository(context: Context) {
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

        val backend = data.backendUrl.trim().ifBlank { BILLNEST_BACKEND_URL }
        return data.copy(accounts = importedAccounts, backendUrl = backend)
    }

    private fun update(transform: (AppData) -> AppData) {
        val next = transform(_data.value)
        store.save(next)
        _data.value = next
    }

    fun addBill(bill: Bill) = update { it.copy(bills = it.bills + bill) }
    fun updateBill(bill: Bill) = update { data ->
        data.copy(bills = data.bills.map { if (it.id == bill.id) bill else it })
    }
    fun deleteBill(id: String) = update { it.copy(bills = it.bills.filterNot { b -> b.id == id }) }

    fun addPayday(payday: Payday) = update { it.copy(paydays = it.paydays + payday) }
    fun updatePayday(payday: Payday) = update { data -> data.copy(paydays = data.paydays.map { if (it.id == payday.id) payday else it }) }
    fun deletePayday(id: String) = update { it.copy(paydays = it.paydays.filterNot { p -> p.id == id }) }

    fun addAccount(account: Account) = update { it.copy(accounts = it.accounts + account) }
    fun updateAccount(account: Account) = update { data ->
        data.copy(accounts = data.accounts.map { if (it.id == account.id) account else it })
    }
    fun deleteAccount(id: String) = update { data ->
        data.copy(
            accounts = data.accounts.filterNot { it.id == id },
            bills = data.bills.map { if (it.accountId == id) it.copy(accountId = null) else it }
        )
    }

    fun syncPlaidAccounts(incoming: List<Account>) = update { data ->
        val manual = data.accounts.filter { it.source == AccountSource.MANUAL }
        val existingPlaid = data.accounts.filter { it.source == AccountSource.PLAID }
            .associateBy { it.plaidAccountId }
        val synced = incoming.map { fresh ->
            val existing = existingPlaid[fresh.plaidAccountId]
            if (existing == null) fresh else fresh.copy(id = existing.id, name = existing.name)
        }
        data.copy(accounts = manual + synced, plaidConnected = synced.isNotEmpty())
    }

    fun setBackendUrl(value: String) = update { it.copy(backendUrl = value.trim().ifBlank { BILLNEST_BACKEND_URL }) }
    fun setBackendApiKey(value: String) = update { it.copy(backendApiKey = value.trim()) }
    fun setManualBalance(value: Double) = update { it.copy(manualBalance = value) }
    fun setBalances(items: List<AccountBalance>, connected: Boolean = true) =
        update { it.copy(balances = items, plaidConnected = connected) }
    fun setBiometric(enabled: Boolean) = update { it.copy(biometricLock = enabled) }
    fun setReminderDays(days: List<Int>) = update { it.copy(reminderDays = days.distinct().sortedDescending()) }

    fun markPaid(id: String) = update { data ->
        data.copy(bills = data.bills.map { b ->
            if (b.id != id) b else {
                val due = b.dueDate()
                val paid = (b.paidDates + due.toString()).distinct()
                if (b.frequency == Frequency.ONE_TIME) b.copy(paidDates = paid)
                else b.copy(dueDateIso = advance(due, b.frequency).toString(), paidDates = paid)
            }
        })
    }

    private fun advance(d: LocalDate, f: Frequency): LocalDate = when (f) {
        Frequency.ONE_TIME -> d
        Frequency.WEEKLY -> d.plusWeeks(1)
        Frequency.BIWEEKLY -> d.plusWeeks(2)
        Frequency.MONTHLY -> d.plusMonths(1)
        Frequency.YEARLY -> d.plusYears(1)
    }
}
