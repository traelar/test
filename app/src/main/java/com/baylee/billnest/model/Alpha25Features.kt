package com.baylee.billnest.model

import com.google.gson.Gson
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class CashFlowDay(
    val date: LocalDate,
    val income: Double,
    val bills: Double,
    val endingBalance: Double
)

data class CashFlowProjection(
    val startingBalance: Double,
    val endingBalance: Double,
    val lowestBalance: Double,
    val lowestBalanceDate: LocalDate,
    val days: List<CashFlowDay>
)

fun cashFlowProjection(
    data: AppData,
    start: LocalDate = LocalDate.now(),
    end: LocalDate = YearMonth.from(start).atEndOfMonth()
): CashFlowProjection {
    val starting = calculateMonthlyCashProjection(data, start).currentSpendable
    var running = starting
    var lowest = starting
    var lowestDate = start
    val result = mutableListOf<CashFlowDay>()

    fun paydayOccurrences(payday: Payday): Set<LocalDate> {
        var occurrence = runCatching { payday.nextDate() }.getOrNull() ?: return emptySet()
        val rows = linkedSetOf<LocalDate>()
        var guard = 0
        while (occurrence.isBefore(start) && payday.frequency != Frequency.ONE_TIME && guard < 128) {
            occurrence = when (payday.frequency) {
                Frequency.WEEKLY -> occurrence.plusWeeks(1)
                Frequency.BIWEEKLY -> occurrence.plusWeeks(2)
                Frequency.MONTHLY -> occurrence.plusMonths(1)
                Frequency.YEARLY -> occurrence.plusYears(1)
                Frequency.ONE_TIME -> occurrence
            }
            guard++
        }
        while (!occurrence.isAfter(end) && guard < 256) {
            if (!occurrence.isBefore(start) && occurrence.toString() !in payday.receivedDates) rows += occurrence
            if (payday.frequency == Frequency.ONE_TIME) break
            occurrence = when (payday.frequency) {
                Frequency.WEEKLY -> occurrence.plusWeeks(1)
                Frequency.BIWEEKLY -> occurrence.plusWeeks(2)
                Frequency.MONTHLY -> occurrence.plusMonths(1)
                Frequency.YEARLY -> occurrence.plusYears(1)
                Frequency.ONE_TIME -> occurrence
            }
            guard++
        }
        return rows
    }
    val paydayDates = data.paydays.associateWith(::paydayOccurrences)

    generateSequence(start) { day -> day.plusDays(1).takeIf { !it.isAfter(end) } }.forEach { day ->
        val income = data.paydays
            .filter { payday -> day in paydayDates.getValue(payday) }
            .sumOf { it.amount.coerceAtLeast(0.0) }
        val bills = data.bills.filter { bill ->
            !bill.isPaidFor() && runCatching { bill.dueDate() == day }.getOrDefault(false)
        }.sumOf { it.amount.coerceAtLeast(0.0) }
        running += income - bills
        if (running < lowest) {
            lowest = running
            lowestDate = day
        }
        if (income > 0.0 || bills > 0.0 || day == start || day == end) {
            result += CashFlowDay(day, income, bills, running)
        }
    }
    return CashFlowProjection(starting, running, lowest, lowestDate, result)
}

fun validTransactionSplits(transaction: FinanceTransaction): Boolean {
    val splits = transaction.splits.orEmpty()
    if (splits.isEmpty()) return true
    if (splits.any { it.amount <= 0.0 || it.category.isBlank() }) return false
    return abs(splits.sumOf { it.amount } - abs(transaction.amount)) <= 0.01
}

fun spendingAmountForCategory(transaction: FinanceTransaction, category: String): Double {
    if (transaction.transfer || transaction.income || transaction.pending || transaction.excludedFromSpending) return 0.0
    val splits = transaction.splits.orEmpty()
    return if (splits.isNotEmpty()) {
        splits.filter { it.category.equals(category, true) }.sumOf { it.amount.coerceAtLeast(0.0) }
    } else if (transaction.category.equals(category, true)) {
        transaction.amount.coerceAtLeast(0.0)
    } else 0.0
}

data class EmergencyFundEstimate(
    val monthlyEssentials: Double,
    val months: Int,
    val target: Double
)

fun emergencyFundEstimate(data: AppData, months: Int): EmergencyFundEstimate {
    val essentialBillCategories = setOf("housing", "utilities", "phone/internet", "insurance", "car", "medical")
    val essentialBudgetCategories = setOf("groceries", "kids", "medical", "car")
    val bills = data.bills
        .filter { it.category.trim().lowercase() in essentialBillCategories }
        .sumOf { monthlyEquivalent(it.amount, it.frequency) }
    val budgets = data.budgets
        .filter { it.category.trim().lowercase() in essentialBudgetCategories }
        .sumOf { budgetMonthlyEquivalent(it) }
    val monthly = bills + budgets
    return EmergencyFundEstimate(monthly, months.coerceAtLeast(1), monthly * months.coerceAtLeast(1))
}

private fun monthlyEquivalent(amount: Double, frequency: Frequency): Double = when (frequency) {
    Frequency.WEEKLY -> amount * 52.0 / 12.0
    Frequency.BIWEEKLY -> amount * 26.0 / 12.0
    Frequency.MONTHLY -> amount
    Frequency.YEARLY -> amount / 12.0
    Frequency.ONE_TIME -> 0.0
}

private fun budgetMonthlyEquivalent(budget: Budget): Double = when (budget.period) {
    BudgetPeriod.WEEKLY -> budget.amount * 52.0 / 12.0
    BudgetPeriod.BIWEEKLY, BudgetPeriod.PAYCHECK -> budget.amount * 26.0 / 12.0
    BudgetPeriod.MONTHLY -> budget.amount
    BudgetPeriod.YEARLY -> budget.amount / 12.0
    BudgetPeriod.CUSTOM -> budget.amount
}

data class SubscriptionCleanupItem(
    val merchantKey: String,
    val name: String,
    val latestAmount: Double,
    val priorAmount: Double?,
    val priceIncreased: Boolean,
    val lastDateIso: String
)

data class SubscriptionCleanup(
    val monthlyCost: Double,
    val annualCost: Double,
    val items: List<SubscriptionCleanupItem>,
    val possibleDuplicates: List<Pair<String,String>>
)

fun subscriptionCleanup(data: AppData): SubscriptionCleanup {
    val confirmed = data.subscriptionPreferences.filter { it.status == SubscriptionStatus.CONFIRMED }
    val items = confirmed.mapNotNull { pref ->
        val rows = data.transactions
            .filter { !it.pending && subscriptionKey(it.name) == pref.merchantKey && !it.transfer && !it.income }
            .sortedBy { it.dateIso }
        if (rows.isEmpty()) return@mapNotNull null
        val latest = rows.last()
        val prior = rows.dropLast(1).lastOrNull()
        val latestAmount = abs(latest.amount)
        val priorAmount = prior?.let { abs(it.amount) }
        SubscriptionCleanupItem(
            merchantKey = pref.merchantKey,
            name = pref.name,
            latestAmount = latestAmount,
            priorAmount = priorAmount,
            priceIncreased = priorAmount != null && latestAmount > priorAmount + 0.01,
            lastDateIso = latest.dateIso
        )
    }.sortedByDescending { it.latestAmount }

    val duplicates = items.flatMapIndexed { index, a ->
        items.drop(index + 1).mapNotNull { b ->
            val aWords = a.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.toSet()
            val bWords = b.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.toSet()
            if (aWords.intersect(bWords).isNotEmpty()) a.name to b.name else null
        }
    }
    val monthly = items.sumOf { it.latestAmount }
    return SubscriptionCleanup(monthly, monthly * 12.0, items, duplicates)
}

data class PaycheckComparison(
    val label: String,
    val expected: Double,
    val actual: Double,
    val difference: Double,
    val actualDateIso: String
)

fun paycheckComparisons(data: AppData, referenceDate: LocalDate = LocalDate.now()): List<PaycheckComparison> {
    val actuals = visibleIncomeTransactions(data.transactions)
        .filterNot { it.pending }
        .mapNotNull { row -> runCatching { LocalDate.parse(row.dateIso) to row }.getOrNull() }
    return data.paydays.mapNotNull { payday ->
        val match = actuals
            .filter { (date, _) -> abs(ChronoUnit.DAYS.between(date, referenceDate)) <= 45 }
            .minByOrNull { (_, row) ->
                val rowName = row.name.lowercase()
                val label = payday.label.lowercase()
                if (rowName.contains(label) || label.split(" ").any { it.length > 3 && rowName.contains(it) }) 0 else 1
            } ?: return@mapNotNull null
        val actual = abs(match.second.amount)
        PaycheckComparison(payday.label, payday.amount, actual, actual - payday.amount, match.first.toString())
    }
}

data class DebtScenario(
    val extraMonthly: Double,
    val months: Int,
    val interest: Double,
    val interestSaved: Double
)

fun debtMilestones(data: AppData): List<DebtScenario> {
    val baseline = calculateDebtStrategy(data.debts, 0.0, DebtStrategy.AVALANCHE)
    return listOf(25.0, 50.0, 100.0).map { extra ->
        val projection = calculateDebtStrategy(data.debts, extra, DebtStrategy.AVALANCHE)
        DebtScenario(extra, projection.months, projection.totalInterest, (baseline.totalInterest - projection.totalInterest).coerceAtLeast(0.0))
    }
}

data class HouseholdActivityItem(
    val dateIso: String,
    val title: String,
    val detail: String
)

fun householdActivity(data: AppData): List<HouseholdActivityItem> {
    val transactionEvents = data.transactions.filter { it.userClassificationOverride }.map {
        HouseholdActivityItem(it.dateIso, "${it.name} updated", "Classified as ${it.category}")
    }
    val goalEvents = data.savingsGoals.map {
        HouseholdActivityItem(
            it.targetDateIso ?: LocalDate.now().toString(),
            "${it.name} savings goal",
            "${it.savedAmount} of ${it.targetAmount} saved"
        )
    }
    val billEvents = data.bills.filter { it.paidDates.isNotEmpty() }.mapNotNull { bill ->
        bill.paidDates.maxOrNull()?.let { HouseholdActivityItem(it, "${bill.name} paid", "Bill marked paid") }
    }
    return (transactionEvents + goalEvents + billEvents).sortedByDescending { it.dateIso }.take(50)
}

fun exportTransactionsCsv(data: AppData): String {
    val header = "date,name,amount,category,account,pending,split_category,split_amount"
    val rows = data.transactions.flatMap { tx ->
        val account = data.accounts.firstOrNull { it.id == tx.accountId }?.name.orEmpty()
        val splits = tx.splits.orEmpty()
        if (splits.isEmpty()) {
            listOf(csvLine(tx.dateIso, tx.name, tx.amount.toString(), tx.category, account, tx.pending.toString(), "", ""))
        } else {
            splits.map { split ->
                csvLine(tx.dateIso, tx.name, tx.amount.toString(), tx.category, account, tx.pending.toString(), split.category, split.amount.toString())
            }
        }
    }
    return (listOf(header) + rows).joinToString("\n")
}

fun exportBillNestBackupJson(data: AppData): String = Gson().toJson(data)

private fun csvLine(vararg values: String): String = values.joinToString(",") { value ->
    "\"" + value.replace("\"", "\"\"") + "\""
}

fun recommendedSavingsOrder(goals: List<SavingsGoal>): List<SavingsGoal> = goals.sortedWith(
    compareBy<SavingsGoal> {
        when (it.priority ?: SavingsPriority.NORMAL) {
            SavingsPriority.EMERGENCY -> 0
            SavingsPriority.HIGH -> 1
            SavingsPriority.NORMAL -> 2
            SavingsPriority.LOW -> 3
        }
    }.thenBy { it.targetDateIso ?: "9999-12-31" }
)
