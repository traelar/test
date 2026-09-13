package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SavingsGoalRulesTest {
    @Test
    fun computesRemainingProgressAndPaydaysNeeded() {
        val goal = SavingsGoal(
            id = "vacation",
            name = "Vacation",
            targetAmount = 2000.0,
            savedAmount = 500.0,
            paydayContribution = 150.0
        )

        val summary = savingsGoalProgress(goal)

        assertEquals(1500.0, summary.remaining, 0.001)
        assertEquals(0.25, summary.progress, 0.001)
        assertEquals(10, summary.paydaysRemaining)
    }

    @Test
    fun completedGoalHasNoRemainingPaydays() {
        val goal = SavingsGoal(
            name = "Emergency fund",
            targetAmount = 1000.0,
            savedAmount = 1000.0,
            paydayContribution = 100.0
        )

        val summary = savingsGoalProgress(goal)

        assertEquals(0.0, summary.remaining, 0.001)
        assertEquals(1.0, summary.progress, 0.001)
        assertEquals(0, summary.paydaysRemaining)
    }

    @Test
    fun missingContributionDoesNotInventCompletionEstimate() {
        val goal = SavingsGoal(
            name = "Car repairs",
            targetAmount = 1000.0,
            savedAmount = 250.0,
            paydayContribution = 0.0
        )

        assertNull(savingsGoalProgress(goal).paydaysRemaining)
    }

    @Test
    fun targetDatePaceReportsAmountNeededPerMonth() {
        val goal = SavingsGoal(
            name = "Christmas",
            targetAmount = 1200.0,
            savedAmount = 300.0,
            targetDateIso = "2026-12-31"
        )

        val pace = savingsGoalTargetPace(goal, LocalDate.of(2026, 9, 12))

        assertEquals(300.0, pace!!.neededPerMonth, 0.01)
        assertEquals(4, pace.monthsRemaining)
    }
}
