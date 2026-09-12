package com.baylee.billnest.model

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
