package com.baylee.billnest.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class TransactionSource { MANUAL, PLAID }
enum class DebtType { CREDIT_CARD, LOAN, MORTGAGE, OTHER }
enum class BudgetPeriod { WEEKLY, BIWEEKLY, MONTHLY, YEARLY, CUSTOM }
enum class DebtStrategy { SNOWBALL, AVALANCHE }
enum class SubscriptionStatus { CONFIRMED, IGNORED }

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

data class SubscriptionPreference(
    val merchantKey: String,
    val name: String,
    val status: SubscriptionStatus,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class DebtStrategyProjection(
    val strategy: DebtStrategy,
    val months: Int,
    val totalInterest: Double,
    val monthlyBudget: Double,
    val payoffPossible: Boolean
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

fun calculateBudgetSpent(budget: Budget, transactions: List<FinanceTransaction>, referenceDate: LocalDate = LocalDate.now()): Double {
    val explicitStart = budget.startDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val explicitEnd = budget.endDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val start = explicitStart ?: when (budget.period) {
        BudgetPeriod.WEEKLY -> referenceDate.minusDays((referenceDate.dayOfWeek.value - 1).toLong())
        BudgetPeriod.BIWEEKLY -> referenceDate.minusDays(((referenceDate.dayOfYear - 1) % 14).toLong())
        BudgetPeriod.MONTHLY -> referenceDate.withDayOfMonth(1)
        BudgetPeriod.YEARLY -> referenceDate.withDayOfYear(1)
        BudgetPeriod.CUSTOM -> referenceDate
    }
    val end = explicitEnd ?: when (budget.period) {
        BudgetPeriod.WEEKLY -> start.plusDays(6)
        BudgetPeriod.BIWEEKLY -> start.plusDays(13)
        BudgetPeriod.MONTHLY -> start.plusMonths(1).minusDays(1)
        BudgetPeriod.YEARLY -> start.plusYears(1).minusDays(1)
        BudgetPeriod.CUSTOM -> referenceDate
    }
    return transactions.filter { row ->
        if (row.transfer || row.income || !row.category.equals(budget.category, true)) return@filter false
        val date = runCatching { LocalDate.parse(row.dateIso) }.getOrNull() ?: return@filter false
        !date.isBefore(start) && !date.isAfter(end)
    }.sumOf { it.amount.coerceAtLeast(0.0) }
}

fun calculateDebtStrategy(debts: List<Debt>, extraPayment: Double, strategy: DebtStrategy): DebtStrategyProjection {
    val active = debts.filter { it.balance > 0.0 }.associate { it.id to it.balance }.toMutableMap()
    val debtById = debts.associateBy { it.id }
    val monthlyBudget = debts.sumOf { it.minimumPayment.coerceAtLeast(0.0) } + extraPayment.coerceAtLeast(0.0)
    if (active.isEmpty()) return DebtStrategyProjection(strategy, 0, 0.0, monthlyBudget, true)
    if (monthlyBudget <= 0.0) return DebtStrategyProjection(strategy, 0, 0.0, monthlyBudget, false)
    var interestTotal = 0.0
    var month = 0
    while (active.isNotEmpty() && month < 1200) {
        month += 1
        active.keys.toList().forEach { id ->
            val interest = active.getValue(id) * ((debtById[id]?.apr ?: 0.0).coerceAtLeast(0.0) / 1200.0)
            active[id] = active.getValue(id) + interest
            interestTotal += interest
        }
        var remainingBudget = monthlyBudget
        active.keys.toList().forEach { id ->
            val minimum = (debtById[id]?.minimumPayment ?: 0.0).coerceAtLeast(0.0)
            val paid = minOf(active.getValue(id), minimum, remainingBudget)
            active[id] = active.getValue(id) - paid
            remainingBudget -= paid
        }
        active.entries.removeAll { it.value <= 0.005 }
        while (remainingBudget > 0.005 && active.isNotEmpty()) {
            val target = when (strategy) {
                DebtStrategy.SNOWBALL -> active.minBy { it.value }.key
                DebtStrategy.AVALANCHE -> active.keys.maxBy { debtById[it]?.apr ?: 0.0 }
            }
            val paid = minOf(active.getValue(target), remainingBudget)
            active[target] = active.getValue(target) - paid
            remainingBudget -= paid
            if (active.getValue(target) <= 0.005) active.remove(target)
        }
        if (remainingBudget >= monthlyBudget && active.isNotEmpty()) break
    }
    return DebtStrategyProjection(strategy, month, interestTotal, monthlyBudget, active.isEmpty())
}

fun subscriptionKey(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "").trim()

fun visibleSubscriptionSuggestions(
    suggestions: List<SubscriptionSuggestion>,
    preferences: List<SubscriptionPreference>
): List<SubscriptionSuggestion> {
    val hidden = preferences.map { it.merchantKey }.toSet()
    return suggestions.filterNot { subscriptionKey(it.name) in hidden }
}

fun mergePlaidAccounts(
    existing: List<Account>,
    incoming: List<Account>,
    retainMissing: Boolean = false
): List<Account> {
    val existingPlaid = existing.filter { it.source == AccountSource.PLAID }.associateBy { it.plaidAccountId }
    val manual = existing.filter { it.source == AccountSource.MANUAL }
    val refreshed = incoming.map { fresh ->
        val saved = existingPlaid[fresh.plaidAccountId]
        if (saved == null) fresh else fresh.copy(
            id = saved.id,
            name = saved.name,
            role = saved.role,
            includeInSpendable = saved.includeInSpendable,
            displayOrder = saved.displayOrder
        )
    }
    val receivedIds = incoming.mapNotNull { it.plaidAccountId }.toSet()
    val temporarilyUnavailable = if (retainMissing) {
        existingPlaid.values.filter { it.plaidAccountId !in receivedIds }
    } else emptyList()
    return (manual + refreshed + temporarilyUnavailable)
        .distinctBy { it.id }
        .sortedWith(compareBy<Account> { it.displayOrder }.thenBy { it.name })
}

data class Budget(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val amount: Double,
    val category: String = "Other",
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
    val rollover: Boolean = false,
    val startDateIso: String? = null,
    val endDateIso: String? = null
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

fun nextDebtDueDate(debt: Debt, today: LocalDate = LocalDate.now()): LocalDate {
    fun inMonth(month: java.time.YearMonth): LocalDate =
        month.atDay(debt.dueDay.coerceIn(1, month.lengthOfMonth()))
    val thisMonth = inMonth(java.time.YearMonth.from(today))
    return if (thisMonth.isBefore(today)) inMonth(java.time.YearMonth.from(today).plusMonths(1)) else thisMonth
}

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
