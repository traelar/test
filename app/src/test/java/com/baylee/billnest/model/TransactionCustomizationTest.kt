package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionCustomizationTest {
    @Test
    fun categorizingOneSameMerchantTransactionDoesNotChangeAnotherPurchase() {
        val groceries = FinanceTransaction(
            id = "walmart-groceries",
            name = "Walmart",
            amount = 86.42,
            dateIso = "2026-09-12",
            category = "Other",
            source = TransactionSource.PLAID
        )
        val nonGroceries = groceries.copy(
            id = "walmart-household",
            amount = 24.11,
            dateIso = "2026-09-10"
        )

        val edited = reclassifyTransaction(
            groceries,
            TransactionClassification.SPENDING,
            spendingCategory = "Groceries"
        )
        val rows = listOf(edited, nonGroceries)

        assertEquals("Groceries", rows.first { it.id == "walmart-groceries" }.category)
        assertTrue(rows.first { it.id == "walmart-groceries" }.userClassificationOverride)
        assertEquals("Other", rows.first { it.id == "walmart-household" }.category)
        assertFalse(rows.first { it.id == "walmart-household" }.userClassificationOverride)
    }

    @Test
    fun transactionCanBePromotedDirectlyToConfirmedSubscription() {
        val row = FinanceTransaction(
            id = "netflix-charge",
            name = "NETFLIX.COM",
            amount = 22.99,
            dateIso = "2026-09-12",
            category = "Subscriptions",
            source = TransactionSource.PLAID
        )

        val preference = subscriptionPreferenceForTransaction(row)

        assertEquals(subscriptionKey(row.name), preference.merchantKey)
        assertEquals("NETFLIX.COM", preference.name)
        assertEquals(SubscriptionStatus.CONFIRMED, preference.status)
    }
}
