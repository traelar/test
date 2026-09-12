package com.baylee.billnest.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Inclusive start/end window for one active budget period. */
data class BudgetPeriodWindow(val start: LocalDate, val end: LocalDate)

data class BudgetTransactionOverride(
    val id: String = UUID.randomUUID().toString(),
    val transactionId: String,
    val budgetId: String? = null,
    val periodStartIso: String,
    val periodEndIso: String? = null,
    val action: BudgetOverrideAction,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class BudgetAdjustment(
    val id: String = UUID.randomUUID().toString(),
    val sourceBudgetId: String,
    val destinationBudgetId: String,
    val amount: Double,
    val periodStartIso: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class BudgetMatch(
    val budgetId: String,
    val transactionId: String,
    val reason: String,
    val score: Int = 0
)

data class BudgetSummary(
    val budgetId: String,
    val window: BudgetPeriodWindow,
    val baseAmount: Double,
    val adjustmentAmount: Double,
    val rolloverAmount: Double,
    val effectiveAmount: Double,
    val spent: Double,
    val remaining: Double,
    val percentUsed: Double,
    val daysRemaining: Int,
    val dailyAllowance: Double,
    val projectedSpend: Double,
    val pace: BudgetPace,
    val matches: List<BudgetMatch>
)

data class VariableSpendingSummary(
    val planned: Double,
    val budgetedSpent: Double,
    val remaining: Double,
    val unbudgetedSpent: Double,
    val projectedSpend: Double,
    val pace: BudgetPace
)

data class BudgetSuggestion(
    val category: String,
    val last30Days: Double,
    val recentMonthlyAverage: Double,
    val recommendedAmount: Double,
    val merchants: List<String>
)

private val BIWEEKLY_ANCHOR: LocalDate = LocalDate.of(1970, 1, 5)
private val NON_VARIABLE_CATEGORIES = setOf(
    "income", "transfer", "savings", "saving", "reserve", "reserved funds",
    "investment", "retirement", "debt", "debt payment", "credit card payment", "loan payment", "mortgage payment"
)

fun budgetPeriodWindow(
    budget: Budget,
    paydays: List<Payday> = emptyList(),
    referenceDate: LocalDate = LocalDate.now()
): BudgetPeriodWindow = when (budget.period) {
    BudgetPeriod.WEEKLY -> {
        val start = referenceDate.minusDays((referenceDate.dayOfWeek.value - 1).toLong())
        BudgetPeriodWindow(start, start.plusDays(6))
    }
    BudgetPeriod.BIWEEKLY -> {
        val days = ChronoUnit.DAYS.between(BIWEEKLY_ANCHOR, referenceDate)
        val offset = Math.floorMod(days, 14L)
        val start = referenceDate.minusDays(offset)
        BudgetPeriodWindow(start, start.plusDays(13))
    }
    BudgetPeriod.PAYCHECK -> paycheckWindow(budget, paydays, referenceDate)
    BudgetPeriod.MONTHLY -> {
        val start = referenceDate.withDayOfMonth(1)
        BudgetPeriodWindow(start, start.plusMonths(1).minusDays(1))
    }
    BudgetPeriod.YEARLY -> {
        val start = referenceDate.withDayOfYear(1)
        BudgetPeriodWindow(start, start.plusYears(1).minusDays(1))
    }
    BudgetPeriod.CUSTOM -> {
        val start = budget.startDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: throw IllegalArgumentException("Custom budget requires a valid start date")
        val end = budget.endDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: throw IllegalArgumentException("Custom budget requires a valid end date")
        require(!end.isBefore(start)) { "Custom budget end date must not be before start date" }
        BudgetPeriodWindow(start, end)
    }
}

private fun paycheckWindow(budget: Budget, paydays: List<Payday>, referenceDate: LocalDate): BudgetPeriodWindow {
    val paydayId = budget.paydayId ?: throw IllegalArgumentException("Paycheck budget requires a payday")
    val payday = paydays.firstOrNull { it.id == paydayId }
        ?: throw IllegalArgumentException("Paycheck budget payday was not found")
    require(payday.frequency != Frequency.ONE_TIME) { "Paycheck budget requires a recurring payday" }

    var next = payday.nextDate()
    while (!referenceDate.isBefore(next)) next = advancePayday(next, payday.frequency)
    var start = retreatPayday(next, payday.frequency)
    while (start.isAfter(referenceDate)) {
        next = start
        start = retreatPayday(next, payday.frequency)
    }
    return BudgetPeriodWindow(start, next.minusDays(1))
}

private fun advancePayday(date: LocalDate, frequency: Frequency): LocalDate = when (frequency) {
    Frequency.WEEKLY -> date.plusWeeks(1)
    Frequency.BIWEEKLY -> date.plusWeeks(2)
    Frequency.MONTHLY -> date.plusMonths(1)
    Frequency.YEARLY -> date.plusYears(1)
    Frequency.ONE_TIME -> date
}

private fun retreatPayday(date: LocalDate, frequency: Frequency): LocalDate = when (frequency) {
    Frequency.WEEKLY -> date.minusWeeks(1)
    Frequency.BIWEEKLY -> date.minusWeeks(2)
    Frequency.MONTHLY -> date.minusMonths(1)
    Frequency.YEARLY -> date.minusYears(1)
    Frequency.ONE_TIME -> date
}

fun eligibleVariableSpendingTransactions(
    data: AppData,
    start: LocalDate,
    end: LocalDate
): List<FinanceTransaction> {
    val inferredIncomeIds = visibleIncomeTransactions(data.transactions).mapTo(mutableSetOf()) { it.id }
    val fixedBillIds = findBillMatches(data.bills, data.transactions)
        .filter { it.highConfidence }
        .mapTo(mutableSetOf()) { it.transactionId }

    return data.transactions.filter { row ->
        if (row.transfer || row.income || row.excludedFromSpending || row.id in inferredIncomeIds || row.id in fixedBillIds) return@filter false
        if (row.amount <= 0.0) return@filter false
        if (row.category.trim().lowercase() in NON_VARIABLE_CATEGORIES) return@filter false
        val date = runCatching { LocalDate.parse(row.dateIso) }.getOrNull() ?: return@filter false
        !date.isBefore(start) && !date.isAfter(end)
    }.sortedBy { it.dateIso }
}

private data class CandidateMatch(val budget: Budget, val reason: String, val score: Int)

fun resolveBudgetAssignments(
    data: AppData,
    referenceDate: LocalDate = LocalDate.now()
): List<BudgetMatch> {
    if (data.budgets.isEmpty() || data.transactions.isEmpty()) return emptyList()
    val windows = data.budgets.associateWith { budgetPeriodWindow(it, data.paydays, referenceDate) }
    val broadStart = windows.values.minOf { it.start }
    val broadEnd = windows.values.maxOf { it.end }
    val eligible = eligibleVariableSpendingTransactions(data, broadStart, broadEnd)

    return eligible.mapNotNull { row ->
        val date = runCatching { LocalDate.parse(row.dateIso) }.getOrNull() ?: return@mapNotNull null
        val activeOverride = data.budgetTransactionOverrides
            .filter { it.transactionId == row.id && overrideIsActive(it, data, windows, referenceDate) }
            .maxByOrNull { it.createdAtEpochMs }

        if (activeOverride?.action == BudgetOverrideAction.EXCLUDE) return@mapNotNull null
        if (activeOverride?.action == BudgetOverrideAction.ASSIGN && activeOverride.budgetId != null) {
            val target = data.budgets.firstOrNull { it.id == activeOverride.budgetId }
            val targetWindow = target?.let(windows::get)
            if (target != null && targetWindow != null && date.inWindow(targetWindow)) {
                return@mapNotNull BudgetMatch(target.id, row.id, "Manual assignment", 1000)
            }
        }

        val candidates = data.budgets.mapNotNull { budget ->
            val window = windows.getValue(budget)
            if (!date.inWindow(window)) return@mapNotNull null
            automaticCandidate(budget, row)
        }
        val winner = candidates.maxWithOrNull(compareBy<CandidateMatch> { it.score }.thenBy { -data.budgets.indexOf(it.budget) })
            ?: return@mapNotNull null
        BudgetMatch(winner.budget.id, row.id, winner.reason, winner.score)
    }
}

private fun automaticCandidate(budget: Budget, row: FinanceTransaction): CandidateMatch? {
    val name = row.name.lowercase()
    val category = row.category.trim()
    if (budget.excludedMerchants.any { it.isNotBlank() && name.contains(it.trim().lowercase()) }) return null
    if (budget.excludedCategories.any { it.equals(category, true) }) return null
    if (row.accountId != null && row.accountId in budget.excludedAccountIds) return null
    if (budget.includedAccountIds.isNotEmpty() && row.accountId !in budget.includedAccountIds) return null

    val merchantMatch = budget.includedMerchants.firstOrNull { it.isNotBlank() && name.contains(it.trim().lowercase()) }
    if (merchantMatch != null) return CandidateMatch(budget, "Merchant rule", 300)

    val categoryMatch = budget.includedCategories.firstOrNull { it.equals(category, true) }
    if (categoryMatch != null) return CandidateMatch(budget, "Category rule", 200)

    if (budget.includedCategories.isEmpty() && budget.includedMerchants.isEmpty() && budget.category.equals(category, true)) {
        return CandidateMatch(budget, "Category rule", 150)
    }

    if (budget.includedAccountIds.isNotEmpty() && row.accountId in budget.includedAccountIds) {
        return CandidateMatch(budget, "Account rule", 100)
    }
    return null
}

private fun overrideIsActive(
    override: BudgetTransactionOverride,
    data: AppData,
    windows: Map<Budget, BudgetPeriodWindow>,
    referenceDate: LocalDate
): Boolean {
    val start = runCatching { LocalDate.parse(override.periodStartIso) }.getOrNull() ?: return false
    val explicitEnd = override.periodEndIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (explicitEnd != null) return !referenceDate.isBefore(start) && !referenceDate.isAfter(explicitEnd)
    if (override.budgetId != null) {
        val budget = data.budgets.firstOrNull { it.id == override.budgetId } ?: return false
        return windows[budget]?.start == start
    }
    return windows.values.any { it.start == start }
}

fun assignTransactionToBudget(
    data: AppData,
    transactionId: String,
    budgetId: String?,
    referenceDate: LocalDate = LocalDate.now()
): AppData {
    require(data.transactions.any { it.id == transactionId }) { "Transaction not found" }
    val windows = data.budgets.associateWith { budgetPeriodWindow(it, data.paydays, referenceDate) }
    val targetWindow = budgetId?.let { id ->
        val budget = data.budgets.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Budget not found")
        windows.getValue(budget)
    }
    val start = targetWindow?.start ?: windows.values.minOfOrNull { it.start } ?: referenceDate.withDayOfMonth(1)
    val end = targetWindow?.end ?: windows.values.maxOfOrNull { it.end } ?: referenceDate.plusMonths(1).minusDays(1)
    val override = BudgetTransactionOverride(
        transactionId = transactionId,
        budgetId = budgetId,
        periodStartIso = start.toString(),
        periodEndIso = end.toString(),
        action = if (budgetId == null) BudgetOverrideAction.EXCLUDE else BudgetOverrideAction.ASSIGN
    )
    return data.copy(
        budgetTransactionOverrides = data.budgetTransactionOverrides.filterNot { old ->
            old.transactionId == transactionId && overrideIsActive(old, data, windows, referenceDate)
        } + override
    )
}

fun moveBudgetMoney(
    data: AppData,
    sourceBudgetId: String,
    destinationBudgetId: String,
    amount: Double,
    referenceDate: LocalDate = LocalDate.now()
): AppData {
    require(sourceBudgetId != destinationBudgetId) { "Choose two different budgets" }
    require(amount > 0.0) { "Amount must be positive" }
    val source = data.budgets.firstOrNull { it.id == sourceBudgetId } ?: throw IllegalArgumentException("Source budget not found")
    val destination = data.budgets.firstOrNull { it.id == destinationBudgetId } ?: throw IllegalArgumentException("Destination budget not found")
    val sourceWindow = budgetPeriodWindow(source, data.paydays, referenceDate)
    val destinationWindow = budgetPeriodWindow(destination, data.paydays, referenceDate)
    require(sourceWindow == destinationWindow) { "Budgets must use the same active period to move money" }
    val sourceSummary = calculateBudgetSummary(data, source, referenceDate)
    require(amount <= sourceSummary.effectiveAmount + 0.0001) { "Amount exceeds source allocation" }

    val adjustment = BudgetAdjustment(
        sourceBudgetId = sourceBudgetId,
        destinationBudgetId = destinationBudgetId,
        amount = amount,
        periodStartIso = sourceWindow.start.toString()
    )
    return data.copy(budgetAdjustments = data.budgetAdjustments + adjustment)
}

fun calculateBudgetSummary(
    data: AppData,
    budget: Budget,
    referenceDate: LocalDate = LocalDate.now()
): BudgetSummary = calculateBudgetSummaryInternal(data, budget, referenceDate, includeRollover = true)

private fun calculateBudgetSummaryInternal(
    data: AppData,
    budget: Budget,
    referenceDate: LocalDate,
    includeRollover: Boolean
): BudgetSummary {
    val window = budgetPeriodWindow(budget, data.paydays, referenceDate)
    val assignments = resolveBudgetAssignments(data, referenceDate).filter { it.budgetId == budget.id }
    val byId = data.transactions.associateBy { it.id }
    val spent = assignments.sumOf { byId[it.transactionId]?.amount?.coerceAtLeast(0.0) ?: 0.0 }
    val adjustments = adjustmentFor(data, budget.id, window.start)
    val rollover = if (includeRollover) calculateRollover(data, budget, window, referenceDate) else 0.0
    val effective = (budget.amount + adjustments + rollover).coerceAtLeast(0.0)
    val remaining = effective - spent
    val percent = if (effective > 0.0) spent / effective else if (spent > 0.0) 1.0 else 0.0
    val daysRemaining = max(0, ChronoUnit.DAYS.between(referenceDate.coerceAtLeast(window.start), window.end).toInt())
    val daily = if (daysRemaining > 0) remaining.coerceAtLeast(0.0) / daysRemaining else remaining.coerceAtLeast(0.0)
    val totalDays = ChronoUnit.DAYS.between(window.start, window.end).toInt() + 1
    val elapsedEnd = minDate(maxDate(referenceDate, window.start), window.end)
    val elapsedDays = ChronoUnit.DAYS.between(window.start, elapsedEnd).toInt() + 1
    val elapsedFraction = (elapsedDays.toDouble() / totalDays.toDouble()).coerceIn(1.0 / totalDays, 1.0)
    val projected = if (spent <= 0.0) 0.0 else spent / elapsedFraction
    val warningThreshold = budget.warningPercent.coerceIn(1, 100) / 100.0
    val pace = when {
        remaining < -0.005 || percent > 1.0 -> BudgetPace.OVER
        !budget.paceTracking -> if (percent >= warningThreshold) BudgetPace.WARNING else BudgetPace.ON_TRACK
        percent >= warningThreshold || projected > effective + 0.005 -> BudgetPace.WARNING
        effective > 0.0 && projected < effective * 0.80 -> BudgetPace.UNDER
        else -> BudgetPace.ON_TRACK
    }
    return BudgetSummary(
        budgetId = budget.id,
        window = window,
        baseAmount = budget.amount,
        adjustmentAmount = adjustments,
        rolloverAmount = rollover,
        effectiveAmount = effective,
        spent = spent,
        remaining = remaining,
        percentUsed = percent,
        daysRemaining = daysRemaining,
        dailyAllowance = daily,
        projectedSpend = projected,
        pace = pace,
        matches = assignments
    )
}

private fun adjustmentFor(data: AppData, budgetId: String, periodStart: LocalDate): Double =
    data.budgetAdjustments.filter { it.periodStartIso == periodStart.toString() }.sumOf { adjustment ->
        when (budgetId) {
            adjustment.sourceBudgetId -> -adjustment.amount
            adjustment.destinationBudgetId -> adjustment.amount
            else -> 0.0
        }
    }

private fun calculateRollover(
    data: AppData,
    budget: Budget,
    current: BudgetPeriodWindow,
    referenceDate: LocalDate
): Double {
    val mode = if (budget.rollover && budget.rolloverMode == BudgetRolloverMode.RESET) {
        BudgetRolloverMode.CARRY_UNUSED
    } else budget.rolloverMode
    if (mode == BudgetRolloverMode.RESET || budget.period == BudgetPeriod.CUSTOM) return 0.0
    val previousReference = current.start.minusDays(1)
    val previousWindow = budgetPeriodWindow(budget, data.paydays, previousReference)
    val previousAssignments = resolveBudgetAssignments(data, previousReference).filter { it.budgetId == budget.id }
    val byId = data.transactions.associateBy { it.id }
    val previousSpent = previousAssignments.sumOf { byId[it.transactionId]?.amount?.coerceAtLeast(0.0) ?: 0.0 }
    val previousAllocation = (budget.amount + adjustmentFor(data, budget.id, previousWindow.start)).coerceAtLeast(0.0)
    val balance = previousAllocation - previousSpent
    return when (mode) {
        BudgetRolloverMode.RESET -> 0.0
        BudgetRolloverMode.CARRY_UNUSED -> balance.coerceAtLeast(0.0)
        BudgetRolloverMode.CARRY_BALANCE -> balance
    }
}

fun calculateVariableSpendingSummary(
    data: AppData,
    referenceDate: LocalDate = LocalDate.now()
): VariableSpendingSummary {
    if (data.budgets.isEmpty()) {
        val start = referenceDate.withDayOfMonth(1)
        val end = start.plusMonths(1).minusDays(1)
        val unbudgeted = eligibleVariableSpendingTransactions(data, start, end).sumOf { it.amount }
        return VariableSpendingSummary(0.0, 0.0, 0.0, unbudgeted, 0.0, if (unbudgeted > 0) BudgetPace.WARNING else BudgetPace.ON_TRACK)
    }
    val summaries = data.budgets.map { calculateBudgetSummary(data, it, referenceDate) }
    val assignments = resolveBudgetAssignments(data, referenceDate)
    val assignedIds = assignments.mapTo(mutableSetOf()) { it.transactionId }
    val windows = data.budgets.map { budgetPeriodWindow(it, data.paydays, referenceDate) }
    val broadStart = windows.minOf { it.start }
    val broadEnd = windows.maxOf { it.end }
    val unbudgeted = eligibleVariableSpendingTransactions(data, broadStart, broadEnd)
        .filter { row -> row.id !in assignedIds && windows.any { window -> runCatching { LocalDate.parse(row.dateIso) }.getOrNull()?.inWindow(window) == true } }
        .sumOf { it.amount }
    val planned = summaries.sumOf { it.effectiveAmount }
    val spent = summaries.sumOf { it.spent }
    val projected = summaries.sumOf { it.projectedSpend }
    val pace = when {
        spent > planned -> BudgetPace.OVER
        summaries.any { it.pace == BudgetPace.OVER } -> BudgetPace.OVER
        summaries.any { it.pace == BudgetPace.WARNING } -> BudgetPace.WARNING
        summaries.isNotEmpty() && summaries.all { it.pace == BudgetPace.UNDER } -> BudgetPace.UNDER
        else -> BudgetPace.ON_TRACK
    }
    return VariableSpendingSummary(planned, spent, planned - spent, unbudgeted, projected, pace)
}

fun suggestBudgets(
    data: AppData,
    referenceDate: LocalDate = LocalDate.now()
): List<BudgetSuggestion> {
    val start90 = referenceDate.minusDays(89)
    val eligible = eligibleVariableSpendingTransactions(data, start90, referenceDate)
    val start30 = referenceDate.minusDays(29)
    return eligible.groupBy { it.category.ifBlank { "Other" } }
        .filterKeys { !it.equals("Other", true) }
        .map { (category, rows) ->
            val last30 = rows.filter { row ->
                val date = runCatching { LocalDate.parse(row.dateIso) }.getOrNull() ?: return@filter false
                !date.isBefore(start30)
            }.sumOf { it.amount }
            val average = rows.sumOf { it.amount } / 3.0
            val basis = when {
                last30 > 0.0 && average > 0.0 -> min(last30, average)
                last30 > 0.0 -> last30
                else -> average
            }
            val recommended = if (basis <= 0.0) 0.0 else ceil((basis * 0.95) / 25.0) * 25.0
            BudgetSuggestion(
                category = category,
                last30Days = last30,
                recentMonthlyAverage = average,
                recommendedAmount = recommended,
                merchants = rows.groupBy { it.name }.entries.sortedByDescending { entry -> entry.value.sumOf { it.amount } }.take(5).map { it.key }
            )
        }
        .sortedByDescending { it.recentMonthlyAverage }
}

private fun LocalDate.inWindow(window: BudgetPeriodWindow): Boolean = !isBefore(window.start) && !isAfter(window.end)
private fun minDate(a: LocalDate, b: LocalDate): LocalDate = if (a.isBefore(b)) a else b
private fun maxDate(a: LocalDate, b: LocalDate): LocalDate = if (a.isAfter(b)) a else b
private fun LocalDate.coerceAtLeast(other: LocalDate): LocalDate = maxDate(this, other)