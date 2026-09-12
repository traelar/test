package com.baylee.billnest.model

import java.time.LocalDate
import java.util.UUID

enum class BudgetPeriod { WEEKLY, BIWEEKLY, MONTHLY, YEARLY, CUSTOM }
enum class DebtType { CREDIT_CARD, PERSONAL_LOAN, AUTO_LOAN, STUDENT_LOAN, MORTGAGE, OTHER }
enum class DebtPayoffStrategy { SNOWBALL, AVALANCHE }
enum class TransactionSource { PLAID, MANUAL }
enum class TransactionType { EXPENSE, INCOME, TRANSFER }
enum class MatchStatus { AUTO_MATCHED, NEEDS_REVIEW, CONFIRMED, REJECTED }

data class Budget(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String,
    val amount: Double,
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
    val startDateIso: String = LocalDate.now().toString(),
    val endDateIso: String? = null,
    val rollover: Boolean = false,
    val active: Boolean = true
)

data class Debt(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: DebtType = DebtType.OTHER,
    val balance: Double,
    val aprPercent: Double = 0.0,
    val minimumPayment: Double = 0.0,
    val dueDay: Int? = null,
    val linkedAccountKey: String? = null,
    val notes: String = "",
    val active: Boolean = true
)

data class SavingsGoal(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double = 0.0,
    val targetDateIso: String? = null,
    val linkedAccountKey: String? = null,
    val contributionPerPaycheck: Double = 0.0,
    val linkedPaydayId: String? = null,
    val active: Boolean = true
)

data class ReservedFund(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val accountKey: String,
    val reservedAmount: Double = 0.0,
    val contributionPerPaycheck: Double = 0.0,
    val linkedPaydayId: String? = null,
    val linkedBillId: String? = null,
    val targetAmount: Double? = null,
    val notes: String = "",
    val active: Boolean = true
)

data class FinanceTransaction(
    val id: String = UUID.randomUUID().toString(),
    val source: TransactionSource = TransactionSource.MANUAL,
    val plaidTransactionId: String? = null,
    val accountKey: String? = null,
    val dateIso: String = LocalDate.now().toString(),
    val name: String,
    val merchantName: String? = null,
    val amount: Double,
    val type: TransactionType = TransactionType.EXPENSE,
    val category: String = "Other",
    val notes: String = "",
    val pending: Boolean = false,
    val excludedFromSpending: Boolean = false
)

data class BillTransactionMatch(
    val id: String = UUID.randomUUID().toString(),
    val billId: String,
    val transactionId: String,
    val score: Double,
    val status: MatchStatus,
    val reason: String = ""
)

data class SubscriptionOverride(
    val merchantKey: String,
    val ignored: Boolean = false,
    val displayName: String? = null
)

data class CashPositionSummary(
    val totalMoney: Double,
    val spendingMoney: Double,
    val savingsMoney: Double,
    val reservedMoney: Double,
    val availableSpending: Double,
    val freeSavings: Double
)

object CashPosition {
    fun calculate(
        accounts: List<Account>,
        preferences: List<AccountPreference>,
        reservedFunds: List<ReservedFund>
    ): CashPositionSummary {
        val base = AccountFinance.summarize(accounts, preferences)
        val accountByKey = accounts.associateBy(AccountFinance::stableKey)
        val activeFunds = reservedFunds.filter { it.active && it.reservedAmount > 0.0 }
        val reserved = activeFunds.sumOf { it.reservedAmount.coerceAtLeast(0.0) }
        val spendingReserved = activeFunds.sumOf { fund ->
            val account = accountByKey[fund.accountKey] ?: return@sumOf 0.0
            val preference = AccountFinance.preferenceFor(account, preferences, accounts.indexOf(account))
            if (preference.includeInSpending) fund.reservedAmount.coerceAtLeast(0.0) else 0.0
        }
        val savingsReserved = activeFunds.sumOf { fund ->
            val account = accountByKey[fund.accountKey] ?: return@sumOf 0.0
            val preference = AccountFinance.preferenceFor(account, preferences, accounts.indexOf(account))
            if (preference.role == AccountRole.SAVINGS && preference.includeInTotal) fund.reservedAmount.coerceAtLeast(0.0) else 0.0
        }
        return CashPositionSummary(
            totalMoney = base.totalMoney,
            spendingMoney = base.spendingMoney,
            savingsMoney = base.savingsMoney,
            reservedMoney = reserved,
            availableSpending = base.spendingMoney - spendingReserved,
            freeSavings = base.savingsMoney - savingsReserved
        )
    }
}

object DebtPlanner {
    fun prioritize(debts: List<Debt>, strategy: DebtPayoffStrategy): List<Debt> {
        val active = debts.filter { it.active && it.balance > 0.0 }
        return when (strategy) {
            DebtPayoffStrategy.SNOWBALL -> active.sortedWith(compareBy<Debt> { it.balance }.thenByDescending { it.aprPercent })
            DebtPayoffStrategy.AVALANCHE -> active.sortedWith(compareByDescending<Debt> { it.aprPercent }.thenBy { it.balance })
        }
    }
}
