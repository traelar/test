package com.baylee.billnest.ui

import androidx.lifecycle.ViewModel
import com.baylee.billnest.data.BillRepository
import com.baylee.billnest.model.Bill

class MainViewModel(val repo: BillRepository) : ViewModel() {
    val data = repo.data
    fun add(b: Bill) = repo.addBill(b)
    fun paid(id: String) = repo.markPaid(id)
    fun delete(id: String) = repo.deleteBill(id)
    fun balance(v: Double) = repo.setManualBalance(v)
}
