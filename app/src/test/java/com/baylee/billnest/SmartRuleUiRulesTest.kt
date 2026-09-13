package com.baylee.billnest

import com.baylee.billnest.model.AppData
import com.baylee.billnest.model.FinanceTransaction
import com.baylee.billnest.model.SmartRuleAction
import com.baylee.billnest.model.SmartRuleMatch
import com.baylee.billnest.model.SmartTransactionRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRuleUiRulesTest {
    @Test
    fun matchingRuleRequiresPreviewConfirmationBeforeSave() {
        val data = AppData(
            transactions = listOf(
                FinanceTransaction(id = "tx", name = "TARGET 123", amount = 45.0, dateIso = "2026-09-13")
            )
        )
        val candidate = SmartTransactionRule(
            id = "rule",
            name = "Target groceries",
            match = SmartRuleMatch(rawNameContains = "TARGET"),
            action = SmartRuleAction(category = "Groceries")
        )

        val rows = smartRulePreviewRows(data, candidate)

        assertTrue(rows.isNotEmpty())
        assertTrue(smartRuleNeedsPreviewConfirmation(rows))
    }

    @Test
    fun futureOnlyRuleCanSaveWithoutPreviewConfirmation() {
        val data = AppData(
            transactions = listOf(
                FinanceTransaction(id = "tx", name = "TARGET 123", amount = 45.0, dateIso = "2026-09-13")
            )
        )
        val candidate = SmartTransactionRule(
            id = "rule",
            name = "Future merchant",
            match = SmartRuleMatch(rawNameContains = "NOT HERE"),
            action = SmartRuleAction(category = "Groceries")
        )

        val rows = smartRulePreviewRows(data, candidate)

        assertTrue(rows.isEmpty())
        assertFalse(smartRuleNeedsPreviewConfirmation(rows))
    }
}
