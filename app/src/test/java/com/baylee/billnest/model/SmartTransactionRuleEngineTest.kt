package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTransactionRuleEngineTest {
    @Test
    fun combinedConditionsMustAllMatch() {
        val tx = FinanceTransaction(
            id = "tx",
            name = "WM SUPERCENTER 1234",
            amount = 85.0,
            dateIso = "2026-09-13",
            category = "Other",
            accountId = "checking",
            merchantProfileId = "walmart"
        )
        val rule = SmartTransactionRule(
            id = "rule",
            name = "Walmart groceries",
            match = SmartRuleMatch(
                rawNameContains = "WM SUPERCENTER",
                merchantProfileId = "walmart",
                accountId = "checking",
                minAmount = 50.0,
                maxAmount = 100.0,
                category = "Other",
                classification = TransactionClassification.SPENDING,
                direction = TransactionDirection.OUTFLOW
            ),
            action = SmartRuleAction(category = "Groceries")
        )

        assertTrue(smartRuleMatches(tx, rule))
        assertFalse(smartRuleMatches(tx.copy(accountId = "savings"), rule))
    }

    @Test
    fun higherPriorityWinsBeforeSpecificityAndTimestamp() {
        val tx = FinanceTransaction(name = "STORE 123", amount = 20.0, dateIso = "2026-09-13")
        val highPriority = SmartTransactionRule(
            id = "high",
            name = "High priority",
            priority = 10,
            match = SmartRuleMatch(rawNameContains = "STORE"),
            action = SmartRuleAction(category = "Groceries"),
            updatedAtEpochMs = 1L
        )
        val specificNewer = SmartTransactionRule(
            id = "specific",
            name = "More specific",
            priority = 5,
            match = SmartRuleMatch(rawNameContains = "STORE", minAmount = 10.0, maxAmount = 30.0),
            action = SmartRuleAction(category = "Other"),
            updatedAtEpochMs = 999L
        )

        assertEquals("high", winningSmartRule(tx, listOf(specificNewer, highPriority))?.id)
    }

    @Test
    fun previewDoesNotMutateInputAndShowsDisplayRename() {
        val tx = FinanceTransaction(id = "tx", name = "UGLY BANK NAME", amount = 12.0, dateIso = "2026-09-13")
        val original = listOf(tx)
        val rule = SmartTransactionRule(
            id = "rename",
            name = "Clean name",
            match = SmartRuleMatch(rawNameContains = "UGLY BANK"),
            action = SmartRuleAction(displayName = "Clean Merchant")
        )

        val preview = previewSmartRuleSet(original, listOf(rule), emptyList(), AppData()).single()

        assertEquals("UGLY BANK NAME", original.single().name)
        assertNull(original.single().displayNameOverride)
        assertEquals("UGLY BANK NAME", preview.after.name)
        assertEquals("Clean Merchant", preview.after.displayNameOverride)
        assertEquals("rename", preview.winningRuleId)
    }

    @Test
    fun automaticRuleClassificationDoesNotReplaceExplicitUserClassification() {
        val tx = FinanceTransaction(
            name = "PAYROLL REVERSAL",
            amount = 50.0,
            dateIso = "2026-09-13",
            category = "Other",
            userClassificationOverride = true,
            income = false,
            transfer = false
        )
        val rule = SmartTransactionRule(
            name = "Treat payroll as income",
            match = SmartRuleMatch(rawNameContains = "PAYROLL"),
            action = SmartRuleAction(classification = TransactionClassification.INCOME, category = "Income")
        )

        val result = applySmartRuleSet(listOf(tx), listOf(rule), emptyList(), emptyList(), AppData()).transactions.single()

        assertFalse(result.income)
        assertFalse(result.transfer)
        assertEquals("Other", result.category)
        assertTrue(result.userClassificationOverride)
    }

    @Test
    fun recurringActionCreatesPreferenceWithoutChangingRawMerchantName() {
        val tx = FinanceTransaction(name = "STREAM CO 928", amount = 15.0, dateIso = "2026-09-13")
        val rule = SmartTransactionRule(
            id = "stream-rule",
            name = "Track streaming",
            match = SmartRuleMatch(rawNameContains = "STREAM CO"),
            action = SmartRuleAction(displayName = "Stream Co", recurringStatus = SubscriptionStatus.CONFIRMED)
        )

        val result = applySmartRuleSet(listOf(tx), listOf(rule), emptyList(), emptyList(), AppData())

        assertEquals("STREAM CO 928", result.transactions.single().name)
        assertEquals("Stream Co", result.transactions.single().displayNameOverride)
        assertEquals(SubscriptionStatus.CONFIRMED, result.subscriptionPreferences.single().status)
        assertEquals(subscriptionKey("Stream Co"), result.subscriptionPreferences.single().merchantKey)
    }

    @Test
    fun legacyRenameRuleUsesDisplayMetadataInsteadOfOverwritingBankName() {
        val tx = FinanceTransaction(name = "RAW BANK MERCHANT", amount = 10.0, dateIso = "2026-09-13")
        val rule = TransactionRule(merchantContains = "RAW BANK", renameTo = "Clean Merchant")

        val result = applyTransactionRules(listOf(tx), listOf(rule)).single()

        assertEquals("RAW BANK MERCHANT", result.name)
        assertEquals("Clean Merchant", result.displayNameOverride)
    }
}
