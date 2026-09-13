package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartTransactionModelsTest {
    @Test
    fun plaidRefreshPreservesBillNestOwnedMetadata() {
        val saved = FinanceTransaction(
            id = "tx-1",
            name = "WM SUPERCENTER 1234",
            amount = 42.0,
            dateIso = "2026-09-12",
            category = "Groceries",
            source = TransactionSource.PLAID,
            displayNameOverride = "Walmart",
            merchantProfileId = "merchant-walmart",
            appliedSmartRuleId = "rule-walmart"
        )
        val fresh = saved.copy(
            name = "WAL-MART #1234",
            amount = 44.0,
            displayNameOverride = null,
            merchantProfileId = null,
            appliedSmartRuleId = null
        )

        val merged = mergePlaidTransactions(listOf(saved), listOf(fresh)).single()

        assertEquals("WAL-MART #1234", merged.name)
        assertEquals(44.0, merged.amount, 0.001)
        assertEquals("Walmart", merged.displayNameOverride)
        assertEquals("merchant-walmart", merged.merchantProfileId)
        assertEquals("rule-walmart", merged.appliedSmartRuleId)
    }
}
