package com.baylee.billnest.model

/**
 * Bill categories start with BillNest's defaults, then inherit meaningful categories the household
 * already uses on spending transactions and existing bills. Matching is case-insensitive so a bank
 * category such as "housing" does not create a duplicate beside "Housing".
 */
fun billCategoryOptions(data: AppData): List<String> {
    val result = mutableListOf<String>()
    val seen = mutableSetOf<String>()

    fun add(value: String) {
        val cleaned = value.trim()
        if (cleaned.isBlank()) return
        val key = cleaned.lowercase()
        if (key in seen) return
        seen += key
        result += cleaned
    }

    BillCategories.forEach(::add)
    data.bills.forEach { add(it.category) }
    data.transactions
        .filterNot { it.transfer || it.income }
        .map { it.category }
        .filterNot { category ->
            val normalized = category.trim().lowercase().replace('_', ' ')
            normalized == "income" || normalized == "transfer" || normalized.startsWith("transfer ")
        }
        .forEach(::add)

    return result
}
