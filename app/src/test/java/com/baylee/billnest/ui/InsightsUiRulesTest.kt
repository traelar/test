package com.baylee.billnest.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class InsightsUiRulesTest {
    @Test
    fun noBudgetsUsesSetupMessageInsteadOfClaimingBudgetsAreWithinRules() {
        assertEquals(
            "No budgets set up yet. Add a budget to start receiving spending alerts.",
            insightsBudgetEmptyState(hasBudgets = false)
        )
    }

    @Test
    fun configuredBudgetsWithoutAlertsUseHealthyMessage() {
        assertEquals(
            "No current budget alerts. Your active budgets are within their warning rules.",
            insightsBudgetEmptyState(hasBudgets = true)
        )
    }
}
