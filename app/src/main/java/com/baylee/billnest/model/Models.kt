package com.baylee.billnest.model

import java.time.LocalDate
import java.util.UUID

enum class Frequency { ONE_TIME, WEEKLY, BIWEEKLY, MONTHLY, YEARLY }
enum class AccountType { CHECKING, SAVINGS, CASH, OTHER }
enum class AccountSource { MANUAL, PLAID }
enum class AccountRole { SPENDING, SAVINGS, CREDIT, OTHER }

const val BILLNEST_BACKEND_URL = "https://billnest-api.joshselusion.workers.dev"

val BillCategories = listOf(
    "Mortgage", "Housing", "Utilities", "Phone/Internet", "Insurance", "Car", "Credit Card",
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
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class AccountPreference(
    val accountId: String,
    val role: AccountRole = AccountRole.SPENDING,
    val includeInTotalMoney: Boolean = true,
    val includeInSpendingMoney: Boolean = true,
    val displayOrder: Int = Int.MAX_VALUE
)

data class ReservedFund(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val accountId: String,
    val currentReserved: Double = 0.0,
    val contributionAmount: Double = 0.0,
    val fundingPaydayId: String? = null,
    val linkedBillId: String? = null,
    val enabled: Boolean = true
)

data class MoneySummary(
    val totalMoney: Double,
    val spendingMoney: Double,
    val savingsMoney: Double,
    val reservedMoney: Double,
    val freeSavings: Double,
    val availableAfterUpcomingBills: Double
)

object MoneyMath {
    fun defaultRole(account: Account): AccountRole = when (account.type) {
        AccountType.CHECKING, AccountType.CASH -> AccountRole.SPENDING
        AccountType.SAVINGS -> AccountRole.SAVINGS
        AccountType.OTHER -> AccountRole.OTHER
    }

    fun preferenceFor(account: Account, preferences: List<AccountPreference>, fallbackOrder: Int): AccountPreference {
        return preferences.firstOrNull { it.accountId == account.id } ?: run {
            val role = defaultRole(account)
            AccountPreference(
                accountId = account.id,
                role = role,
                includeInTotalMoney = true,
                includeInSpendingMoney = role == AccountRole.SPENDING,
                displayOrder = fallbackOrder
            )
        }
    }

    fun orderedAccounts(accounts: List<Account>, preferences: List<AccountPreference>): List<Account> {
        val indexById = accounts.mapIndexed { index, account -> account.id to index }.toMap()
        return accounts.sortedWith(
            compareBy<Account> {
                preferenceFor(it, preferences, indexById[it.id] ?: Int.MAX_VALUE).displayOrder
            }.thenBy { indexById[it.id] ?: Int.MAX_VALUE }
        )
    }

    fun summary(
        accounts: List<Account>,
        preferences: List<AccountPreference>,
        reservedFunds: List<ReservedFund>,
        upcomingBills: Double = 0.0
    ): MoneySummary {
        val indexById = accounts.mapIndexed { index, account -> account.id to index }.toMap()
        val reservesByAccount = reservedFunds
            .filter { it.enabled }
            .groupBy { it.accountId }
            .mapValues { (_, funds) -> funds.sumOf { it.currentReserved.coerceAtLeast(0.0) } }

        var total = 0.0
        var spending = 0.0
        var savings = 0.0
        var freeSavings = 0.0

        accounts.forEach { account ->
            val preference = preferenceFor(account, preferences, indexById[account.id] ?: Int.MAX_VALUE)
            val balance = account.balance
            val reserved = reservesByAccount[account.id] ?: 0.0
            val freeBalance = (balance - reserved).coerceAtLeast(0.0)

            if (preference.includeInTotalMoney) total += balance
            if (preference.includeInSpendingMoney) spending += freeBalance
            if (preference.role == AccountRole.SAVINGS && preference.includeInTotalMoney) {
                savings += balance
                freeSavings += freeBalance
            }
        }

        val reservedMoney = reservedFunds.filter { it.enabled }.sumOf { it.currentReserved.coerceAtLeast(0.0) }
        return MoneySummary(
            totalMoney = total,
            spendingMoney = spending,
            savingsMoney = savings,
            reservedMoney = reservedMoney,
            freeSavings = freeSavings,
            availableAfterUpcomingBills = spending - upcomingBills.coerceAtLeast(0.0)
        )
    }
}

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
    val frequency: Frequency = Frequency.BIWEEKLY
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
    val accountPreferences: List<AccountPreference> = emptyList(),
    val reservedFunds: List<ReservedFund> = emptyList(),
    val backendUrl: String = BILLNEST_BACKEND_URL,
    val backendApiKey: String = "",
    val manualBalance: Double = 0.0,
    val balances: List<AccountBalance> = emptyList(),
    val plaidConnected: Boolean = false,
    val reminderDays: List<Int> = listOf(7, 3, 1, 0),
    val biometricLock: Boolean = false
)
