package com.baylee.billnest.model

import java.time.LocalDate

data class BudgetSetupDraft(
    val category: String,
    val name: String = category,
    val amount: Double
)

/**
 * Returns only new variable-budget suggestions. Existing customized budgets win: if a category is
 * already covered by either the modern includedCategories rules or the legacy category field, that
 * category is not suggested again.
 */
fun suggestBudgetSetup(
    data: AppData,
    referenceDate: LocalDate = LocalDate.now()
): List<BudgetSuggestion> {
    val coveredCategories = data.budgets.flatMap { budget ->
        buildList {
            budget.category.trim().takeIf { it.isNotBlank() && !it.equals("Other", true) }?.let(::add)
            addAll(budget.includedCategories.filter { it.isNotBlank() })
        }
    }.map { it.trim().lowercase() }.toSet()

    return suggestBudgets(data, referenceDate)
        .filter { suggestion -> suggestion.recommendedAmount > 0.0 && suggestion.category.trim().lowercase() !in coveredCategories }
}

fun buildBudgetsFromSetupDrafts(drafts: List<BudgetSetupDraft>): List<Budget> = drafts
    .filter { it.category.isNotBlank() && it.name.isNotBlank() && it.amount > 0.0 }
    .map { draft ->
        val category = draft.category.trim()
        Budget(
            name = draft.name.trim(),
            amount = draft.amount,
            category = category,
            period = BudgetPeriod.MONTHLY,
            includedCategories = listOf(category)
        )
    }
