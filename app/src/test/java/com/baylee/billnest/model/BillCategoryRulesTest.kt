package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillCategoryRulesTest {
    @Test
    fun billCategoryOptionsIncludeUserTransactionAndExistingBillCategoriesWithoutDuplicates() {
        val data = AppData(
            bills = listOf(
                Bill(
                    name = "Property tax",
                    amount = 100.0,
                    dueDateIso = "2026-09-20",
                    category = "Property Tax"
                )
            ),
            transactions = listOf(
                FinanceTransaction(
                    id = "mortgage-tx",
                    name = "Mortgage payment",
                    amount = 1450.0,
                    dateIso = "2026-09-01",
                    category = "Mortgage"
                ),
                FinanceTransaction(
                    id = "housing-tx",
                    name = "Rent-like test",
                    amount = 100.0,
                    dateIso = "2026-09-02",
                    category = "housing"
                )
            )
        )

        val options = billCategoryOptions(data)

        assertTrue("Mortgage should be available to Add Bill", "Mortgage" in options)
        assertTrue("Existing bill categories should remain selectable", "Property Tax" in options)
        assertEquals(1, options.count { it.equals("Housing", ignoreCase = true) })
    }
}
