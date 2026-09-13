package com.baylee.billnest.model

/**
 * Categories are shared across BillNest. Values that differ only by case, underscores, or repeated
 * whitespace resolve to one category so the UI cannot create duplicate-looking buckets.
 */
fun cleanCategoryName(value: String): String = value
    .trim()
    .replace('_', ' ')
    .replace(Regex("\\s+"), " ")

fun normalizeCategoryKey(value: String): String = cleanCategoryName(value).lowercase()

private fun isSystemCategory(value: String): Boolean {
    val normalized = normalizeCategoryKey(value)
    return normalized == "income" || normalized == "transfer" || normalized.startsWith("transfer ")
}

/**
 * Build the reusable category catalog from BillNest defaults plus every category the household is
 * already using in bills, transactions, splits, budgets, merchant profiles, and transaction rules.
 */
fun categoryOptions(data: AppData): List<String> {
    val result = mutableListOf<String>()
    val seen = mutableSetOf<String>()

    fun add(value: String?) {
        val cleaned = cleanCategoryName(value.orEmpty())
        if (cleaned.isBlank() || isSystemCategory(cleaned)) return
        val key = normalizeCategoryKey(cleaned)
        if (!seen.add(key)) return
        result += cleaned
    }

    BillCategories.forEach(::add)
    data.bills.forEach { add(it.category) }
    data.transactions.forEach { transaction ->
        if (!transaction.transfer && !transaction.income) add(transaction.category)
        transaction.splits.orEmpty().forEach { add(it.category) }
    }
    data.budgets.forEach { budget ->
        add(budget.category)
        budget.includedCategories.forEach(::add)
        budget.excludedCategories.forEach(::add)
    }
    data.transactionRules.forEach { add(it.category) }
    data.merchantProfiles.forEach { add(it.preferredCategory) }
    data.smartTransactionRules.forEach { add(it.action.category) }

    return result
}

/** Reuse the existing display spelling when a category already exists; otherwise clean the new name. */
fun canonicalCategoryName(data: AppData, value: String): String {
    val cleaned = cleanCategoryName(value).ifBlank { "Other" }
    val key = normalizeCategoryKey(cleaned)
    return categoryOptions(data).firstOrNull { normalizeCategoryKey(it) == key } ?: cleaned
}

/** Backward-compatible bill helper now uses the same shared category catalog as the rest of BillNest. */
fun billCategoryOptions(data: AppData): List<String> = categoryOptions(data)
