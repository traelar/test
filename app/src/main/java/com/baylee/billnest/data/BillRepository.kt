package com.baylee.billnest.data

import android.content.Context
import com.baylee.billnest.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

class BillRepository(context: Context) {
    private val store = EncryptedStore(context)
    private val _data = MutableStateFlow(store.load())
    val data: StateFlow<AppData> = _data

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
    fun deletePayday(id: String) = update { it.copy(paydays = it.paydays.filterNot { p -> p.id == id }) }

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
