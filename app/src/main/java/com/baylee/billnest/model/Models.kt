package com.baylee.billnest.model

import java.time.LocalDate
import java.util.UUID

enum class Frequency { ONE_TIME, WEEKLY, BIWEEKLY, MONTHLY, YEARLY }
enum class AccountType { CHECKING, SAVINGS, CASH, OTHER }
enum class AccountSource { MANUAL, PLAID }
enum class AccountRole { SPENDING, SAVINGS, CREDIT, OTHER }

const val BILLNEST_BACKEND_URL = "https://billnest-api.joshselusion.workers.dev"

val BillCategories = listOf(
    "Housing", "Utilities", "Phone/Internet", "Insurance", "Car", "Credit Card",
    "Subscriptions", "Medical", "Kids", "Groceries", "Other"
)

data class Account(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: AccountType = AccountType.CHECKING,
    val balance: Double = 0.0,
    val source: AccountSource = AccountSource.MANUAL,
    val plaidAccountId: String? = null,
    val mask: String = "",
    val connectionLabel: String? = null,
    val role: AccountRole = AccountRole.OTHER,
    val includeInSpendable: Boolean = true,
    val displayOrder: Int = 0,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class Bill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val amount: Double,
    val dueDateIso: String,
    val frequency: Frequency = Frequency.MONTHLY,
    val autopay: Boolean = false,
    val category: String = "Other",
    val notes: String = "",
    val variableAmount: Boolean = false,
    val accountId: String? = null,
    val paidDates: List<String> = emptyList()
) {
    fun dueDate(): LocalDate = LocalDate.parse(dueDateIso)
    fun isPaidFor(date: LocalDate = dueDate()): Boolean = paidDates.contains(date.toString())
}

data class Payday(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "Paycheck",
    val amount: Double,
    val nextDateIso: String,
    val frequency: Frequency = Frequency.BIWEEKLY,
    val receivedDates: List<String> = emptyList()
) {
    fun nextDate(): LocalDate = LocalDate.parse(nextDateIso)
}

// Legacy balance shape retained so existing encrypted v1.x data can migrate safely.
data class AccountBalance(
    val accountId: String,
    val name: String,
    val mask: String = "",
    val current: Double,
    val available: Double? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class AppData(
    val bills: List<Bill> = emptyList(),
    val paydays: List<Payday> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val transactions: List<FinanceTransaction> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val debts: List<Debt> = emptyList(),
    val savingsGoals: List<SavingsGoal> = emptyList(),
    val reservedFunds: List<ReservedFund> = emptyList(),
    val subscriptionPreferences: List<SubscriptionPreference> = emptyList(),
    val backendUrl: String = BILLNEST_BACKEND_URL,
    val backendApiKey: String = "",
    val manualBalance: Double = 0.0,
    val balances: List<AccountBalance> = emptyList(),
    val plaidConnected: Boolean = false,
    val reminderDays: List<Int> = listOf(7, 3, 1, 0),
    val biometricLock: Boolean = false
)
