package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionOverrideTest {
    @Test
    fun `household override changes category name and spending treatment without mutating source id`() {
        val source = FinanceTransaction(
            id = "plaid:tx-1",
            source = TransactionSource.PLAID,
            plaidTransactionId = "tx-1",
            name = "POS PURCHASE 123",
            merchantName = "Target",
            amount = 72.50,
            category = "General Merchandise",
            type = TransactionType.EXPENSE
        )
        val override = TransactionOverride(
            transactionId = source.id,
            category = "Groceries",
            customName = "Weekly groceries",
            excludedFromSpending = true
        )

        val effective = TransactionRules.applyOverride(source, override)

        assertEquals("plaid:tx-1", effective.id)
        assertEquals("Groceries", effective.category)
        assertEquals("Weekly groceries", effective.name)
        assertTrue(effective.excludedFromSpending)
    }
}
