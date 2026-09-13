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
 * Legacy rule application retained for existing callers. The newer smart-rule engine stores a
 * presentation rename in displayNameOverride so Plaid identity can stay authoritative, while this
 * compatibility path continues to expose the renamed name exactly as older screens/tests expect.
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

    val rename = matching.renameTo?.trim()?.takeIf { it.isNotEmpty() }
    transaction.copy(
        name = rename ?: transaction.name,
        displayNameOverride = rename ?: transaction.displayNameOverride,
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
