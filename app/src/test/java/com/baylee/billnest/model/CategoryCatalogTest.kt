package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryCatalogTest {
    @Test
    fun categoryOptionsMergeMainAndSplitCategoriesWithoutDuplicates() {
        val data = AppData(
            transactions = listOf(
                FinanceTransaction(
                    id = "one",
                    name = "Store",
                    amount = 25.0,
                    dateIso = "2026-09-13",
                    category = " groceries "
                ),
                FinanceTransaction(
                    id = "two",
                    name = "Cafe",
                    amount = 10.0,
                    dateIso = "2026-09-13",
                    category = "Other",
                    splits = listOf(
                        TransactionSplit(category = "GROCERIES", amount = 4.0),
                        TransactionSplit(category = "Work Drinks", amount = 6.0)
                    )
                )
            )
        )

        val options = categoryOptions(data)

        assertEquals(1, options.count { normalizeCategoryKey(it) == "groceries" })
        assertTrue(options.any { it == "Work Drinks" })
    }

    @Test
    fun canonicalCategorySelectionReusesExistingDisplayName() {
        val data = AppData(
            transactions = listOf(
                FinanceTransaction(
                    id = "one",
                    name = "Cafe",
                    amount = 8.0,
                    dateIso = "2026-09-13",
                    category = "Work Drinks"
                )
            )
        )

        assertEquals("Work Drinks", canonicalCategoryName(data, "  work   drinks  "))
        assertEquals("New Category", canonicalCategoryName(data, "  New   Category  "))
    }
}
