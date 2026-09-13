package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class InsightsCategoryDrilldownTest {
    @Test
    fun categoryDrilldownReturnsOnlyTransactionsContributingToSelectedCategory() {
        val data = AppData(
            transactions = listOf(
                FinanceTransaction(
                    id = "grocery-only",
                    name = "Aldi",
                    amount = 75.0,
                    dateIso = "2026-09-05",
                    category = "Groceries"
                ),
                FinanceTransaction(
                    id = "split-target",
                    name = "Target",
                    amount = 100.0,
                    dateIso = "2026-09-06",
                    category = "Other",
                    splits = listOf(
                        TransactionSplit(category = "Groceries", amount = 60.0),
                        TransactionSplit(category = "Kids", amount = 40.0)
                    )
                ),
                FinanceTransaction(
                    id = "restaurant",
                    name = "Pizza",
                    amount = 30.0,
                    dateIso = "2026-09-07",
                    category = "Dining"
                ),
                FinanceTransaction(
                    id = "pending",
                    name = "Pending grocery",
                    amount = 22.0,
                    dateIso = "2026-09-08",
                    category = "Groceries",
                    pending = true
                )
            )
        )

        val rows = categorySpendingBreakdown(data, YearMonth.of(2026, 9), "Groceries")

        assertEquals(2, rows.size)
        assertEquals(135.0, rows.sumOf { it.amount }, 0.001)
        assertTrue(rows.any { it.transaction.id == "grocery-only" && it.amount == 75.0 && !it.fromSplit })
        assertTrue(rows.any { it.transaction.id == "split-target" && it.amount == 60.0 && it.fromSplit })
    }

    @Test
    fun categoryDrilldownIsScopedToSelectedMonth() {
        val data = AppData(
            transactions = listOf(
                FinanceTransaction(name = "September", amount = 20.0, dateIso = "2026-09-10", category = "Groceries"),
                FinanceTransaction(name = "August", amount = 99.0, dateIso = "2026-08-10", category = "Groceries")
            )
        )

        val rows = categorySpendingBreakdown(data, YearMonth.of(2026, 9), "Groceries")

        assertEquals(1, rows.size)
        assertEquals("September", rows.single().transaction.name)
    }
}
