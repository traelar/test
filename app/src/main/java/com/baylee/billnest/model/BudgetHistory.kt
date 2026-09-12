package com.baylee.billnest.model

import java.time.LocalDate

data class BudgetHistoryEntry(
    val budgetId: String,
    val window: BudgetPeriodWindow,
    val planned: Double,
    val spent: Double,
    val remaining: Double,
    val percentUsed: Double,
    val pace: BudgetPace
)

data class BudgetAlert(
    val budgetId: String,
    val budgetName: String,
    val pace: BudgetPace,
    val percentUsed: Double,
    val projectedSpend: Double,
    val effectiveAmount: Double,
    val message: String
)

fun budgetHistory(
    data: AppData,
    budget: Budget,
    periods: Int = 6,
    referenceDate: LocalDate = LocalDate.now()
): List<BudgetHistoryEntry> {
    if (periods <= 0) return emptyList()
    if (budget.period == BudgetPeriod.CUSTOM) {
        val summary = calculateBudgetSummary(data, budget, referenceDate)
        return listOf(summary.toHistoryEntry())
    }

    val result = mutableListOf<BudgetHistoryEntry>()
    var reference = referenceDate
    repeat(periods) {
        val window = budgetPeriodWindow(budget, data.paydays, reference)
        val summaryReference = if (reference.isAfter(window.end)) window.end else reference
        val summary = calculateBudgetSummary(data, budget, summaryReference)
        result += summary.toHistoryEntry()
        reference = window.start.minusDays(1)
    }
    return result
}

fun budgetAlerts(
    data: AppData,
    referenceDate: LocalDate = LocalDate.now()
): List<BudgetAlert> = data.budgets.mapNotNull { budget ->
    val summary = runCatching { calculateBudgetSummary(data, budget, referenceDate) }.getOrNull()
        ?: return@mapNotNull null
    val warningThreshold = budget.warningPercent.coerceIn(1, 100) / 100.0
    val shouldAlert = summary.pace == BudgetPace.OVER ||
        summary.pace == BudgetPace.WARNING ||
        summary.percentUsed >= warningThreshold ||
        summary.projectedSpend > summary.effectiveAmount + 0.005
    if (!shouldAlert) return@mapNotNull null

    val message = when {
        summary.pace == BudgetPace.OVER || summary.remaining < 0.0 ->
            "${budget.name} is over budget by ${formatBudgetAlertMoney(-summary.remaining)}."
        summary.projectedSpend > summary.effectiveAmount + 0.005 ->
            "${budget.name} is projected to reach ${formatBudgetAlertMoney(summary.projectedSpend)} against ${formatBudgetAlertMoney(summary.effectiveAmount)} planned."
        else ->
            "${budget.name} has used ${(summary.percentUsed * 100).toInt()}% of its current-period budget."
    }
    BudgetAlert(
        budgetId = budget.id,
        budgetName = budget.name,
        pace = summary.pace,
        percentUsed = summary.percentUsed,
        projectedSpend = summary.projectedSpend,
        effectiveAmount = summary.effectiveAmount,
        message = message
    )
}.sortedWith(compareByDescending<BudgetAlert> { it.pace == BudgetPace.OVER }.thenByDescending { it.percentUsed })

private fun BudgetSummary.toHistoryEntry(): BudgetHistoryEntry = BudgetHistoryEntry(
    budgetId = budgetId,
    window = window,
    planned = effectiveAmount,
    spent = spent,
    remaining = remaining,
    percentUsed = percentUsed,
    pace = pace
)

private fun formatBudgetAlertMoney(value: Double): String = "$" + String.format("%,.2f", value.coerceAtLeast(0.0))
