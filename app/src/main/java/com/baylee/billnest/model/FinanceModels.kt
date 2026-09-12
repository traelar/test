package com.baylee.billnest.model

import java.time.LocalDate
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

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
    val matchedDueDateIso: String = "",
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

data class DebtPayoffEstimate(
    val strategy: DebtPayoffStrategy,
    val months: Int,
    val interestPaid: Double,
    val totalPaid: Double,
    val debtFreeDateIso: String?
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

    fun estimate(
        debts: List<Debt>,
        strategy: DebtPayoffStrategy,
        extraMonthlyPayment: Double = 0.0,
        startDate: LocalDate = LocalDate.now()
    ): DebtPayoffEstimate {
        data class Working(val debt: Debt, var balance: Double)
        val working = debts.filter { it.active && it.balance > 0.0 }.map { Working(it, it.balance) }.toMutableList()
        if (working.isEmpty()) return DebtPayoffEstimate(strategy, 0, 0.0, 0.0, startDate.toString())
        val originalPrincipal = working.sumOf { it.balance }
        var interest = 0.0
        var months = 0
        val maxMonths = 1200
        while (working.any { it.balance > 0.005 } && months < maxMonths) {
            months += 1
            working.forEach { item ->
                if (item.balance <= 0.0) return@forEach
                val monthlyRate = max(0.0, item.debt.aprPercent) / 100.0 / 12.0
                val charged = item.balance * monthlyRate
                item.balance += charged
                interest += charged
            }
            var available = working.filter { it.balance > 0.0 }.sumOf { minOf(it.balance, max(0.0, it.debt.minimumPayment)) } + max(0.0, extraMonthlyPayment)
            val ordered = when (strategy) {
                DebtPayoffStrategy.SNOWBALL -> working.filter { it.balance > 0.0 }.sortedBy { it.balance }
                DebtPayoffStrategy.AVALANCHE -> working.filter { it.balance > 0.0 }.sortedByDescending { it.debt.aprPercent }
            }
            ordered.forEach { item ->
                if (available <= 0.0 || item.balance <= 0.0) return@forEach
                val minimum = minOf(item.balance, max(0.0, item.debt.minimumPayment))
                val payment = minOf(item.balance, minimum)
                item.balance -= payment
                available -= payment
            }
            ordered.forEach { item ->
                if (available <= 0.0 || item.balance <= 0.0) return@forEach
                val payment = minOf(item.balance, available)
                item.balance -= payment
                available -= payment
            }
            if (working.filter { it.balance > 0.005 }.all { it.debt.minimumPayment <= 0.0 } && extraMonthlyPayment <= 0.0) break
        }
        val debtFreeDate = if (working.none { it.balance > 0.005 }) startDate.plusMonths(months.toLong()).toString() else null
        return DebtPayoffEstimate(
            strategy = strategy,
            months = if (debtFreeDate == null) maxMonths else months,
            interestPaid = interest,
            totalPaid = originalPrincipal + interest,
            debtFreeDateIso = debtFreeDate
        )
    }
}
