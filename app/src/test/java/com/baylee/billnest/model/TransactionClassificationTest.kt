package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionClassificationTest {
    @Test
    fun financeTransactionStoresManualTransferClassificationMetadata() {
        val fields = FinanceTransaction::class.java.declaredFields.map { it.name }.toSet()

        assertTrue("FinanceTransaction should store the manually selected source account", "transferFromAccountId" in fields)
        assertTrue("FinanceTransaction should store the manually selected destination account", "transferToAccountId" in fields)
        assertTrue("FinanceTransaction should remember that the user overrode Plaid's classification", "userClassificationOverride" in fields)
    }

    @Test
    fun plaidRefreshPreservesManualTransferClassification() {
        val mergeMethod = runCatching {
            Class.forName("com.baylee.billnest.model.FinanceModelsKt")
                .getDeclaredMethod("mergePlaidTransactions", List::class.java, List::class.java)
        }.getOrNull()
        assertNotNull("Plaid refresh should use a merge that preserves manual transaction classification", mergeMethod)

        val existing = FinanceTransaction(
            id = "plaid:tx-1",
            name = "Transfer deposit",
            amount = -500.0,
            dateIso = "2026-09-10",
            category = "Transfer",
            accountId = "checking",
            source = TransactionSource.PLAID,
            transfer = true,
            income = false,
            transferFromAccountId = "savings",
            transferToAccountId = "checking",
            userClassificationOverride = true
        )
        val refreshed = FinanceTransaction(
            id = "plaid:tx-1",
            name = "Bank transfer deposit",
            amount = -500.0,
            dateIso = "2026-09-10",
            category = "Income",
            accountId = "checking",
            source = TransactionSource.PLAID,
            transfer = false,
            income = true
        )

        @Suppress("UNCHECKED_CAST")
        val result = mergeMethod!!.invoke(null, listOf(existing), listOf(refreshed)) as List<FinanceTransaction>
        val merged = result.single { it.id == existing.id }

        assertTrue(merged.transfer)
        assertEquals(false, merged.income)
        assertEquals("Transfer", merged.category)
        assertEquals("savings", merged.transferFromAccountId)
        assertEquals("checking", merged.transferToAccountId)
        assertTrue(merged.userClassificationOverride)
        assertEquals("Bank transfer deposit", merged.name)
    }
}
