package com.baylee.billnest.ui

import androidx.lifecycle.ViewModel
import com.baylee.billnest.data.BillRepository
import com.baylee.billnest.model.Bill
import com.baylee.billnest.model.Payday

class MainViewModel(val repo: BillRepository) : ViewModel() {
    val data = repo.data
    fun add(b: Bill) = repo.addBill(b)
    fun updateBill(b: Bill) = repo.updateBill(b)
    fun paid(id: String) = repo.markPaid(id)
    fun delete(id: String) = repo.deleteBill(id)
    fun addPayday(p: Payday) = repo.addPayday(p)
    fun updatePayday(p: Payday) = repo.updatePayday(p)
    fun deletePayday(id: String) = repo.deletePayday(id)
    fun balance(v: Double) = repo.setManualBalance(v)
    fun reminderDays(v: List<Int>) = repo.setReminderDays(v)
}
