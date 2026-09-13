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

/**
 * Legacy rules now keep the authoritative bank-provided transaction name untouched. A rename is
 * presentation metadata, matching the smart-rule/merchant-profile model used by Alpha30.
 */
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
        displayNameOverride = matching.renameTo?.trim()?.takeIf { it.isNotEmpty() }
            ?: transaction.displayNameOverride,
        category = if (transaction.userClassificationOverride) {
            transaction.category
        } else {
            matching.category?.trim()?.takeIf { it.isNotEmpty() } ?: transaction.category
        },
        excludedFromSpending = matching.excludeFromSpending
    )
}

fun transactionTombstoneIds(data: AppData): Set<String> = buildSet {
    addAll(data.deletedPlaidTransactionIds)
    addAll(data.transactionTombstones.map { it.transactionId })
}
