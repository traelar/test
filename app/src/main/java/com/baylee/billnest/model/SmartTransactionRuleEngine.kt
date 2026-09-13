package com.baylee.billnest.model

import kotlin.math.abs

fun transactionClassification(transaction: FinanceTransaction): TransactionClassification = when {
    transaction.transfer -> TransactionClassification.TRANSFER
    transaction.income || transaction.category.equals("Income", ignoreCase = true) ||
        (transaction.source == TransactionSource.PLAID && transaction.amount < 0.0 && !transaction.userClassificationOverride) ->
        TransactionClassification.INCOME
    else -> TransactionClassification.SPENDING
}

fun transactionDirection(transaction: FinanceTransaction): TransactionDirection =
    if (transaction.amount < 0.0) TransactionDirection.INFLOW else TransactionDirection.OUTFLOW

fun smartRuleMatches(transaction: FinanceTransaction, rule: SmartTransactionRule): Boolean {
    if (!rule.enabled) return false
    val match = rule.match

    match.rawNameContains?.let { raw ->
        val needle = raw.trim()
        if (needle.isEmpty() || !transaction.name.contains(needle, ignoreCase = true)) return false
    }
    match.merchantProfileId?.let { if (transaction.merchantProfileId != it) return false }
    match.accountId?.let { if (transaction.accountId != it) return false }

    val amount = abs(transaction.amount)
    match.minAmount?.let { if (amount < it) return false }
    match.maxAmount?.let { if (amount > it) return false }

    match.category?.let { expected ->
        if (normalizeCategoryKey(transaction.category) != normalizeCategoryKey(expected)) return false
    }
    match.classification?.let { if (transactionClassification(transaction) != it) return false }
    match.direction?.let { if (transactionDirection(transaction) != it) return false }

    return true
}

private fun smartRuleSpecificity(rule: SmartTransactionRule): Int = with(rule.match) {
    listOf(
        rawNameContains?.takeIf { it.isNotBlank() },
        merchantProfileId,
        accountId,
        minAmount,
        maxAmount,
        category?.takeIf { it.isNotBlank() },
        classification,
        direction
    ).count { it != null }
}

fun winningSmartRule(
    transaction: FinanceTransaction,
    rules: List<SmartTransactionRule>
): SmartTransactionRule? = rules
    .filter { smartRuleMatches(transaction, it) }
    .sortedWith(
        compareByDescending<SmartTransactionRule> { it.priority }
            .thenByDescending { smartRuleSpecificity(it) }
            .thenByDescending { it.updatedAtEpochMs }
            .thenBy { it.id }
    )
    .firstOrNull()

private fun applyAutomaticRuleClassification(
    transaction: FinanceTransaction,
    classification: TransactionClassification
): FinanceTransaction = when (classification) {
    TransactionClassification.SPENDING -> transaction.copy(
        transfer = false,
        income = false,
        transferFromAccountId = null,
        transferToAccountId = null
    )
    TransactionClassification.INCOME -> transaction.copy(
        category = "Income",
        transfer = false,
        income = true,
        transferFromAccountId = null,
        transferToAccountId = null,
        splits = null
    )
    TransactionClassification.TRANSFER -> transaction.copy(
        category = "Transfer",
        transfer = true,
        income = false,
        transferFromAccountId = null,
        transferToAccountId = null,
        splits = null
    )
}

private fun applySmartRuleAction(
    transaction: FinanceTransaction,
    rule: SmartTransactionRule,
    data: AppData
): FinanceTransaction {
    var result = transaction.copy(appliedSmartRuleId = rule.id)
    val action = rule.action

    action.displayName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { result = result.copy(displayNameOverride = it) }

    if (!transaction.userClassificationOverride) {
        action.category
            ?.takeIf { it.isNotBlank() }
            ?.let { result = result.copy(category = canonicalCategoryName(data, it)) }

        action.classification?.let { classification ->
            result = applyAutomaticRuleClassification(result, classification)
        }
    }

    action.excludeFromSpending?.let { excluded ->
        result = result.copy(excludedFromSpending = excluded)
    }

    return result
}

private fun updateRecurringPreference(
    preferences: List<SubscriptionPreference>,
    transaction: FinanceTransaction,
    status: SubscriptionStatus
): List<SubscriptionPreference> {
    val displayName = transaction.effectiveDisplayName()
    val key = subscriptionKey(displayName)
    if (key.isBlank()) return preferences
    val preference = SubscriptionPreference(
        merchantKey = key,
        name = displayName,
        status = status
    )
    return preferences.filterNot { it.merchantKey == key } + preference
}

fun applySmartRuleSet(
    transactions: List<FinanceTransaction>,
    rules: List<SmartTransactionRule>,
    profiles: List<MerchantProfile>,
    subscriptions: List<SubscriptionPreference>,
    data: AppData
): SmartRuleBatchResult {
    val profiled = applyMerchantProfiles(transactions, profiles, data)
    var preferences = subscriptions
    val ruled = profiled.map { transaction ->
        val rule = winningSmartRule(transaction, rules) ?: return@map transaction
        val after = applySmartRuleAction(transaction, rule, data)
        rule.action.recurringStatus?.let { status ->
            preferences = updateRecurringPreference(preferences, after, status)
        }
        after
    }
    return SmartRuleBatchResult(ruled, preferences)
}

fun previewSmartRuleSet(
    transactions: List<FinanceTransaction>,
    rules: List<SmartTransactionRule>,
    profiles: List<MerchantProfile>,
    data: AppData
): List<RulePreviewRow> {
    val profiled = applyMerchantProfiles(transactions, profiles, data)
    return transactions.zip(profiled).mapNotNull { (before, transaction) ->
        val rule = winningSmartRule(transaction, rules) ?: return@mapNotNull null
        RulePreviewRow(
            transactionId = before.id,
            before = before,
            after = applySmartRuleAction(transaction, rule, data),
            winningRuleId = rule.id
        )
    }
}
