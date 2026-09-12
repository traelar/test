package com.baylee.billnest.ui

import androidx.lifecycle.ViewModel
import com.baylee.billnest.data.BillRepository
import com.baylee.billnest.model.*

class MainViewModel(val repo: BillRepository) : ViewModel() {
    val data = repo.data
    fun add(b: Bill) = repo.addBill(b)
    fun updateBill(b: Bill) = repo.updateBill(b)
    fun paid(id: String) = repo.markPaid(id)
    fun delete(id: String) = repo.deleteBill(id)
    fun addPayday(p: Payday) = repo.addPayday(p)
    fun updatePayday(p: Payday) = repo.updatePayday(p)
    fun deletePayday(id: String) = repo.deletePayday(id)
    fun receivePayday(id: String) = repo.receivePayday(id)
    fun addAccount(a: Account) = repo.addAccount(a)
    fun updateAccount(a: Account) = repo.updateAccount(a)
    fun moveAccount(id: String, direction: Int) = repo.moveAccount(id, direction)
    fun deleteAccount(id: String) = repo.deleteAccount(id)
    fun syncPlaidAccounts(items: List<Account>, retainMissing: Boolean = false) = repo.syncPlaidAccounts(items, retainMissing)
    fun backendUrl(v: String) = repo.setBackendUrl(v)
    fun backendApiKey(v: String) = repo.setBackendApiKey(v)
    fun balance(v: Double) = repo.setManualBalance(v)
    fun reminderDays(v: List<Int>) = repo.setReminderDays(v)
    fun saveTransaction(v: FinanceTransaction) = repo.saveTransaction(v)
    fun deleteTransaction(id: String) = repo.deleteTransaction(id)
    fun syncPlaidTransactions(v: List<FinanceTransaction>) = repo.syncPlaidTransactions(v)
    fun saveBudget(v: Budget) = repo.saveBudget(v)
    fun deleteBudget(id: String) = repo.deleteBudget(id)
    fun saveDebt(v: Debt) = repo.saveDebt(v)
    fun deleteDebt(id: String) = repo.deleteDebt(id)
    fun saveGoal(v: SavingsGoal) = repo.saveGoal(v)
    fun deleteGoal(id: String) = repo.deleteGoal(id)
    fun saveReservedFund(v: ReservedFund) = repo.saveReservedFund(v)
    fun deleteReservedFund(id: String) = repo.deleteReservedFund(id)
    fun fundReserved(id: String, amount: Double) = repo.fundReserved(id, amount)
    fun saveSubscriptionPreference(v: SubscriptionPreference) = repo.saveSubscriptionPreference(v)
    fun deleteSubscriptionPreference(key: String) = repo.deleteSubscriptionPreference(key)
}
