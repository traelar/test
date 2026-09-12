package com.baylee.billnest.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
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
    val transfer: Boolean = false,
    val income: Boolean = false
)

data class PaydayPattern(
    val label: String,
    val typicalAmount: Double,
    val nextDateIso: String,
    val frequency: Frequency,
    val sampleCount: Int
)

data class SubscriptionSuggestion(
    val name: String,
    val typicalAmount: Double,
    val frequency: Frequency,
    val lastDateIso: String,
    val sampleCount: Int
)

data class BillMatchSuggestion(
    val billId: String,
    val transactionId: String,
    val confidence: Double,
    val highConfidence: Boolean
)

fun detectPaydayPatterns(
    transactions: List<FinanceTransaction>,
    referenceDate: LocalDate = LocalDate.now()
): List<PaydayPattern> {
    val incomeRows = transactions.filter { row ->
        !row.transfer && (row.income || row.category.equals("Income", true) ||
            (row.source == TransactionSource.PLAID && row.amount < 0.0))
    }
    return incomeRows.groupBy { normalizePayer(it.name) }.mapNotNull { (_, rows) ->
        val dated = rows.mapNotNull { row -> runCatching { LocalDate.parse(row.dateIso) to row }.getOrNull() }
            .distinctBy { it.first }
            .sortedBy { it.first }
        if (dated.size < 2) return@mapNotNull null
        val intervals = dated.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.first, b.first).toInt() }.sorted()
        val medianDays = intervals[intervals.size / 2]
        val frequency = when (medianDays) {
            in 6..8 -> Frequency.WEEKLY
            in 12..16 -> Frequency.BIWEEKLY
            in 25..35 -> Frequency.MONTHLY
            in 330..400 -> Frequency.YEARLY
            else -> return@mapNotNull null
        }
        val stepDays = when (frequency) {
            Frequency.WEEKLY -> 7L
            Frequency.BIWEEKLY -> 14L
            Frequency.MONTHLY -> medianDays.toLong()
            Frequency.YEARLY -> medianDays.toLong()
            Frequency.ONE_TIME -> return@mapNotNull null
        }
        var next = dated.last().first.plusDays(stepDays)
        while (next.isBefore(referenceDate)) next = next.plusDays(stepDays)
        val amounts = dated.map { kotlin.math.abs(it.second.amount) }.sorted()
        PaydayPattern(
            label = dated.last().second.name.ifBlank { "Paycheck" },
            typicalAmount = amounts[amounts.size / 2],
            nextDateIso = next.toString(),
            frequency = frequency,
            sampleCount = dated.size
        )
    }.sortedBy { it.nextDateIso }
}

private fun normalizePayer(name: String): String = name.lowercase()
    .replace(Regex("\\d+"), " ")
    .replace(Regex("\\b(ach|direct|deposit|payroll|paycheck|payment)\\b"), " ")
    .replace(Regex("[^a-z]+"), " ")
    .trim()
    .ifBlank { name.lowercase().trim() }

fun detectSubscriptions(transactions: List<FinanceTransaction>): List<SubscriptionSuggestion> =
    transactions.filter { !it.transfer && !it.income }.groupBy { normalizeMerchant(it.name) }.mapNotNull { (_, rows) ->
        val dated = rows.mapNotNull { row -> runCatching { LocalDate.parse(row.dateIso) to row }.getOrNull() }
            .distinctBy { it.first }.sortedBy { it.first }
        if (dated.size < 3) return@mapNotNull null
        val intervals = dated.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.first, b.first).toInt() }.sorted()
        val medianDays = intervals[intervals.size / 2]
        val frequency = when (medianDays) {
            in 25..35 -> Frequency.MONTHLY
            in 350..380 -> Frequency.YEARLY
            else -> return@mapNotNull null
        }
        val amounts = dated.map { kotlin.math.abs(it.second.amount) }.sorted()
        val medianAmount = amounts[amounts.size / 2]
        if (medianAmount <= 0.0 || amounts.any { kotlin.math.abs(it - medianAmount) / medianAmount > 0.20 }) return@mapNotNull null
        SubscriptionSuggestion(dated.last().second.name, medianAmount, frequency, dated.last().first.toString(), dated.size)
    }.sortedByDescending { it.typicalAmount }

fun findBillMatches(bills: List<Bill>, transactions: List<FinanceTransaction>): List<BillMatchSuggestion> {
    val expenses = transactions.filter { !it.transfer && !it.income }
    return bills.filterNot { it.isPaidFor() }.mapNotNull { bill ->
        val due = runCatching { bill.dueDate() }.getOrNull() ?: return@mapNotNull null
        expenses.mapNotNull { transaction ->
            val date = runCatching { LocalDate.parse(transaction.dateIso) }.getOrNull() ?: return@mapNotNull null
            val dayGap = kotlin.math.abs(ChronoUnit.DAYS.between(due, date))
            val amountGap = kotlin.math.abs(transaction.amount - bill.amount)
            val amountRatio = if (bill.amount > 0) amountGap / bill.amount else 1.0
            if (dayGap > 7 || amountRatio > 0.20) return@mapNotNull null
            val billWords = normalizeMerchant(bill.name).split(' ').filter { it.length > 2 }.toSet()
            val transactionWords = normalizeMerchant(transaction.name).split(' ').filter { it.length > 2 }.toSet()
            val nameMatch = billWords.intersect(transactionWords).isNotEmpty()
            val confidence = ((1.0 - amountRatio) * 0.65 + (1.0 - dayGap / 7.0) * 0.20 + if (nameMatch) 0.15 else 0.0).coerceIn(0.0, 1.0)
            BillMatchSuggestion(bill.id, transaction.id, confidence, confidence >= 0.90)
        }.maxByOrNull { it.confidence }
    }
}

private fun normalizeMerchant(name: String): String = name.lowercase()
    .replace(Regex("\\d+"), " ")
    .replace(Regex("[^a-z]+"), " ")
    .replace(Regex("\\b(payment|purchase|debit|online|pos)\\b"), " ")
    .trim()

fun applyPaydayContributions(data: AppData, paydayId: String, receivedDate: LocalDate = LocalDate.now()): AppData {
    val payday = data.paydays.firstOrNull { it.id == paydayId } ?: return data
    val dateKey = receivedDate.toString()
    if (dateKey in payday.receivedDates) return data
    val nextDate = when (payday.frequency) {
        Frequency.ONE_TIME -> payday.nextDate()
        Frequency.WEEKLY -> payday.nextDate().plusWeeks(1)
        Frequency.BIWEEKLY -> payday.nextDate().plusWeeks(2)
        Frequency.MONTHLY -> payday.nextDate().plusMonths(1)
        Frequency.YEARLY -> payday.nextDate().plusYears(1)
    }
    return data.copy(
        paydays = data.paydays.map {
            if (it.id == paydayId) it.copy(nextDateIso = nextDate.toString(), receivedDates = (it.receivedDates + dateKey).distinct()) else it
        },
        reservedFunds = data.reservedFunds.map { it.copy(amount = it.amount + it.paydayContribution.coerceAtLeast(0.0)) },
        savingsGoals = data.savingsGoals.map { goal ->
            goal.copy(savedAmount = (goal.savedAmount + goal.paydayContribution.coerceAtLeast(0.0)).coerceAtMost(goal.targetAmount))
        }
    )
}

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
