package com.baylee.billnest.model

import java.util.UUID

enum class TransactionSource { MANUAL, PLAID }
enum class DebtType { CREDIT_CARD, LOAN, MORTGAGE, OTHER }
enum class BudgetPeriod { WEEKLY, BIWEEKLY, MONTHLY, YEARLY, CUSTOM }

data class FinanceTransaction(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val amount: Double,
    val dateIso: String,
    val category: String = "Other",
    val accountId: String? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
    val transfer: Boolean = false
)

data class Budget(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val amount: Double,
    val category: String = "Other",
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
    val rollover: Boolean = false
)

data class Debt(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: DebtType,
    val balance: Double,
    val apr: Double = 0.0,
    val minimumPayment: Double = 0.0,
    val dueDay: Int = 1
)

data class SavingsGoal(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val targetAmount: Double,
    val savedAmount: Double = 0.0,
    val targetDateIso: String? = null,
    val accountId: String? = null,
    val paydayContribution: Double = 0.0
)

data class ReservedFund(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val amount: Double = 0.0,
    val targetAmount: Double? = null,
    val accountId: String? = null,
    val billId: String? = null,
    val paydayContribution: Double = 0.0,
    val consumeWhenBillPaid: Boolean = false
)

data class MoneySummary(
    val totalMoney: Double,
    val spendingMoney: Double,
    val savings: Double,
    val reserved: Double,
    val availableAfterUpcomingBills: Double
)

fun calculateMoneySummary(data: AppData): MoneySummary {
    val total = data.accounts.filter { it.role != AccountRole.CREDIT }.sumOf { it.balance }
    val savings = data.accounts.filter { it.role == AccountRole.SAVINGS }.sumOf { it.balance }
    val spendable = data.accounts.filter { it.includeInSpendable && it.role != AccountRole.SAVINGS && it.role != AccountRole.CREDIT }.sumOf { it.balance }
    val reserved = data.reservedFunds.sumOf { it.amount.coerceAtLeast(0.0) }
    val upcoming = data.bills.filterNot { it.isPaidFor() }.sumOf { it.amount.coerceAtLeast(0.0) }
    return MoneySummary(total, spendable, savings, reserved, spendable - reserved - upcoming)
}
