package com.baylee.billnest.model

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil

data class SavingsGoalProgress(
    val progress: Double,
    val remaining: Double,
    val paydaysRemaining: Int?
)

data class SavingsGoalTargetPace(
    val monthsRemaining: Int,
    val neededPerMonth: Double
)

fun savingsGoalProgress(goal: SavingsGoal): SavingsGoalProgress {
    val target = goal.targetAmount.coerceAtLeast(0.0)
    val saved = goal.savedAmount.coerceAtLeast(0.0)
    val remaining = (target - saved).coerceAtLeast(0.0)
    val progress = if (target <= 0.0) 0.0 else (saved / target).coerceIn(0.0, 1.0)
    val paydaysRemaining = when {
        remaining <= 0.005 -> 0
        goal.paydayContribution <= 0.0 -> null
        else -> ceil(remaining / goal.paydayContribution).toInt()
    }
    return SavingsGoalProgress(progress, remaining, paydaysRemaining)
}

fun savingsGoalTargetPace(
    goal: SavingsGoal,
    referenceDate: LocalDate = LocalDate.now()
): SavingsGoalTargetPace? {
    val targetDate = goal.targetDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    if (targetDate.isBefore(referenceDate)) return SavingsGoalTargetPace(
        monthsRemaining = 0,
        neededPerMonth = savingsGoalProgress(goal).remaining
    )
    val start = YearMonth.from(referenceDate)
    val end = YearMonth.from(targetDate)
    val months = ((end.year - start.year) * 12 + end.monthValue - start.monthValue + 1).coerceAtLeast(1)
    return SavingsGoalTargetPace(
        monthsRemaining = months,
        neededPerMonth = savingsGoalProgress(goal).remaining / months
    )
}
