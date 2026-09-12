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
    fun addAccount(a: Account) = repo.addAccount(a)
    fun updateAccount(a: Account) = repo.updateAccount(a)
    fun updateAccountPreference(v: AccountPreference) = repo.updateAccountPreference(v)
    fun moveAccount(accountKey: String, direction: Int) = repo.moveAccount(accountKey, direction)
    fun deleteAccount(id: String) = repo.deleteAccount(id)
    fun syncPlaidAccounts(items: List<Account>, connectedItems: Int? = null) = repo.syncPlaidAccounts(items, connectedItems)
    fun addManualTransaction(v: FinanceTransaction) = repo.addManualTransaction(v)
    fun updateManualTransaction(v: FinanceTransaction) = repo.updateManualTransaction(v)
    fun deleteManualTransaction(id: String) = repo.deleteManualTransaction(id)
    fun setPlaidTransactions(v: List<FinanceTransaction>) = repo.setPlaidTransactions(v)
    fun addBudget(v: Budget) = repo.addBudget(v)
    fun updateBudget(v: Budget) = repo.updateBudget(v)
    fun deleteBudget(id: String) = repo.deleteBudget(id)
    fun addDebt(v: Debt) = repo.addDebt(v)
    fun updateDebt(v: Debt) = repo.updateDebt(v)
    fun deleteDebt(id: String) = repo.deleteDebt(id)
    fun addGoal(v: SavingsGoal) = repo.addSavingsGoal(v)
    fun updateGoal(v: SavingsGoal) = repo.updateSavingsGoal(v)
    fun deleteGoal(id: String) = repo.deleteSavingsGoal(id)
    fun addReservedFund(v: ReservedFund) = repo.addReservedFund(v)
    fun updateReservedFund(v: ReservedFund) = repo.updateReservedFund(v)
    fun deleteReservedFund(id: String) = repo.deleteReservedFund(id)
    fun upsertBillMatch(v: BillTransactionMatch) = repo.upsertBillMatch(v)
    fun upsertSubscriptionOverride(v: SubscriptionOverride) = repo.upsertSubscriptionOverride(v)
    fun backendUrl(v: String) = repo.setBackendUrl(v)
    fun backendApiKey(v: String) = repo.setBackendApiKey(v)
    fun balance(v: Double) = repo.setManualBalance(v)
    fun reminderDays(v: List<Int>) = repo.setReminderDays(v)
}
