package com.baylee.billnest.model

enum class TransactionClassification { SPENDING, INCOME, TRANSFER }

/**
 * Apply a user-owned classification to an existing transaction. The override is
 * persisted independently of the Plaid-owned transaction details so later bank
 * refreshes cannot silently turn a transfer back into income or spending.
 */
fun reclassifyTransaction(
    transaction: FinanceTransaction,
    classification: TransactionClassification,
    fromAccountId: String? = null,
    toAccountId: String? = null,
    spendingCategory: String = transaction.category
): FinanceTransaction = when (classification) {
    TransactionClassification.SPENDING -> transaction.copy(
        category = spendingCategory.ifBlank { "Other" },
        transfer = false,
        income = false,
        transferFromAccountId = null,
        transferToAccountId = null,
        userClassificationOverride = true
    )
    TransactionClassification.INCOME -> transaction.copy(
        category = "Income",
        transfer = false,
        income = true,
        transferFromAccountId = null,
        transferToAccountId = null,
        userClassificationOverride = true
    )
    TransactionClassification.TRANSFER -> transaction.copy(
        category = "Transfer",
        transfer = true,
        income = false,
        transferFromAccountId = fromAccountId,
        transferToAccountId = toAccountId,
        userClassificationOverride = true
    )
}

/**
 * Refresh Plaid-owned transaction details without overwriting a classification the
 * user explicitly set inside BillNest.
 */
fun mergePlaidTransactions(
    existing: List<FinanceTransaction>,
    incoming: List<FinanceTransaction>
): List<FinanceTransaction> {
    val existingById = existing.associateBy { it.id }
    val refreshed = incoming.map { fresh ->
        val saved = existingById[fresh.id]
        if (saved?.userClassificationOverride == true) {
            fresh.copy(
                category = saved.category,
                transfer = saved.transfer,
                income = saved.income,
                transferFromAccountId = saved.transferFromAccountId,
                transferToAccountId = saved.transferToAccountId,
                userClassificationOverride = true
            )
        } else {
            fresh
        }
    }
    val incomingIds = incoming.map { it.id }.toSet()
    val retained = existing.filterNot { it.id in incomingIds }
    return (retained + refreshed).sortedByDescending { it.dateIso }
}
