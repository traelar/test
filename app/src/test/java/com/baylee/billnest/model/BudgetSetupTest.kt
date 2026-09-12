package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetSetupTest {
    private val referenceDate = LocalDate.of(2026, 9, 12)

    @Test
    fun setupSuggestionsSkipCategoriesAlreadyCoveredByExistingBudgets() {
        val data = AppData(
            budgets = listOf(
                Budget(
                    id = "groceries",
                    name = "My Grocery Plan",
                    amount = 600.0,
                    category = "Groceries",
                    includedCategories = listOf("Groceries")
                )
            ),
            transactions = listOf(
                FinanceTransaction(id = "g1", name = "Aldi", amount = 120.0, dateIso = "2026-09-05", category = "Groceries"),
                FinanceTransaction(id = "d1", name = "Restaurant", amount = 90.0, dateIso = "2026-09-06", category = "Dining"),
                FinanceTransaction(id = "d2", name = "Restaurant", amount = 110.0, dateIso = "2026-08-10", category = "Dining")
            )
        )

        val suggestions = suggestBudgetSetup(data, referenceDate)

        assertEquals(listOf("Dining"), suggestions.map { it.category })
        assertTrue(suggestions.single().recommendedAmount > 0.0)
    }

    @Test
    fun setupSuggestionsTreatLegacyBudgetCategoryAsAlreadyCovered() {
        val data = AppData(
            budgets = listOf(Budget(name = "Fuel", amount = 250.0, category = "Gas")),
            transactions = listOf(
                FinanceTransaction(id = "gas", name = "Kwik Trip", amount = 85.0, dateIso = "2026-09-05", category = "Gas"),
                FinanceTransaction(id = "shop", name = "Target", amount = 150.0, dateIso = "2026-09-05", category = "Shopping")
            )
        )

        val suggestions = suggestBudgetSetup(data, referenceDate)

        assertEquals(listOf("Shopping"), suggestions.map { it.category })
    }

    @Test
    fun selectedSetupDraftsCreateMonthlyCategoryBudgetsWithoutChangingExistingBudgets() {
        val drafts = listOf(
            BudgetSetupDraft(category = "Dining", name = "Eating Out", amount = 225.0),
            BudgetSetupDraft(category = "Gas", name = "Fuel", amount = 300.0)
        )

        val budgets = buildBudgetsFromSetupDrafts(drafts)

        assertEquals(2, budgets.size)
        assertEquals("Eating Out", budgets[0].name)
        assertEquals(225.0, budgets[0].amount, 0.001)
        assertEquals(BudgetPeriod.MONTHLY, budgets[0].period)
        assertEquals(listOf("Dining"), budgets[0].includedCategories)
        assertEquals("Dining", budgets[0].category)
        assertEquals("Fuel", budgets[1].name)
        assertEquals(listOf("Gas"), budgets[1].includedCategories)
    }
}
