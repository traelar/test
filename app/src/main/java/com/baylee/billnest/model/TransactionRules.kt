package com.baylee.billnest.model

import java.util.UUID

data class TransactionRule(
    val id: String = UUID.randomUUID().toString(),
    val merchantContains: String,
    val renameTo: String? = null,
    val category: String? = null,
    val excludeFromSpending: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class TransactionTombstone(
    val transactionId: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

fun applyTransactionRules(
    transactions: List<FinanceTransaction>,
    rules: List<TransactionRule>
): List<FinanceTransaction> = transactions.map { transaction ->
    val matching = rules
        .filter { rule ->
            val needle = rule.merchantContains.trim()
            needle.isNotEmpty() && transaction.name.contains(needle, ignoreCase = true)
        }
        .maxWithOrNull(
            compareBy<TransactionRule> { it.merchantContains.trim().length }
                .thenBy { it.updatedAtEpochMs }
        )
        ?: return@map transaction

    transaction.copy(
        name = matching.renameTo?.trim()?.takeIf { it.isNotEmpty() } ?: transaction.name,
        category = matching.category?.trim()?.takeIf { it.isNotEmpty() } ?: transaction.category,
        excludedFromSpending = matching.excludeFromSpending
    )
}

fun transactionTombstoneIds(data: AppData): Set<String> = buildSet {
    addAll(data.deletedPlaidTransactionIds)
    addAll(data.transactionTombstones.map { it.transactionId })
}
