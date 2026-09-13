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

    @Test
    fun incomingPipelineRunsLegacyThenMerchantThenSmartBeforeMergePreservesUserOverride() {
        val account = Account(
            id = "local-checking",
            name = "Checking",
            type = AccountType.CHECKING,
            source = AccountSource.PLAID,
            plaidAccountId = "plaid-checking",
            role = AccountRole.SPENDING
        )
        val legacy = TransactionRule(
            id = "legacy",
            merchantContains = "WM SUPERCENTER",
            renameTo = "Legacy Walmart",
            category = "Shopping"
        )
        val merchant = MerchantProfile(
            id = "walmart",
            displayName = "Walmart",
            aliases = listOf("WM SUPERCENTER"),
            preferredCategory = "Groceries",
            updatedAtEpochMs = 1L
        )
        val smart = SmartTransactionRule(
            id = "walmart-household",
            name = "Walmart household",
            priority = 20,
            match = SmartRuleMatch(
                merchantProfileId = merchant.id,
                accountId = account.id
            ),
            action = SmartRuleAction(
                displayName = "Walmart Household",
                category = "Household"
            ),
            updatedAtEpochMs = 2L
        )
        val saved = FinanceTransaction(
            id = "tx-1",
            name = "WM SUPERCENTER OLD",
            amount = 40.0,
            dateIso = "2026-09-12",
            category = "Medical",
            accountId = account.id,
            source = TransactionSource.PLAID,
            userClassificationOverride = true,
            displayNameOverride = "Walmart",
            merchantProfileId = merchant.id,
            appliedSmartRuleId = smart.id
        )
        val data = AppData(
            accounts = listOf(account),
            transactions = listOf(saved),
            transactionRules = listOf(legacy),
            merchantProfiles = listOf(merchant),
            smartTransactionRules = listOf(smart)
        )
        val incoming = FinanceTransaction(
            id = saved.id,
            name = "WM SUPERCENTER 1234",
            amount = 55.0,
            dateIso = "2026-09-13",
            category = "Other",
            accountId = account.plaidAccountId,
            source = TransactionSource.PLAID
        )

        val accountIds = data.accounts
            .filter { it.source == AccountSource.PLAID && !it.plaidAccountId.isNullOrBlank() }
            .associate { it.plaidAccountId!! to it.id }
        val mapped = incoming.copy(accountId = accountIds[incoming.accountId] ?: incoming.accountId)
        val processed = processIncomingTransactions(data, listOf(mapped)).transactions.single()

        assertEquals(account.id, processed.accountId)
        assertEquals("WM SUPERCENTER 1234", processed.name)
        assertEquals(merchant.id, processed.merchantProfileId)
        assertEquals(smart.id, processed.appliedSmartRuleId)
        assertEquals("Walmart Household", processed.displayNameOverride)
        assertEquals("Household", processed.category)

        val merged = mergePlaidTransactions(data.transactions, listOf(processed)).single()
        assertEquals("WM SUPERCENTER 1234", merged.name)
        assertEquals(55.0, merged.amount, 0.001)
        assertEquals(account.id, merged.accountId)
        assertEquals("Medical", merged.category)
        assertTrue(merged.userClassificationOverride)
        assertEquals("Walmart", merged.displayNameOverride)
        assertEquals(merchant.id, merged.merchantProfileId)
        assertEquals(smart.id, merged.appliedSmartRuleId)
    }
}
