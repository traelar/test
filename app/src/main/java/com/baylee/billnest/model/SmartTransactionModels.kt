package com.baylee.billnest.model

import java.util.UUID

enum class TransactionDirection { INFLOW, OUTFLOW }
enum class MerchantConfirmation { AUTO, USER_CONFIRMED }
enum class ReviewDisposition { RESOLVED, DISMISSED }
enum class ReviewType {
    UNCATEGORIZED,
    UNKNOWN_MERCHANT,
    POSSIBLE_TRANSFER,
    POSSIBLE_INCOME,
    POSSIBLE_RECURRING,
    POTENTIAL_DUPLICATE,
    CATEGORY_CONFLICT,
    UNUSUAL_AMOUNT,
    ACCOUNT_METADATA_MISSING,
    DEBT_METADATA_MISSING
}

data class MerchantProfile(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val preferredCategory: String? = null,
    val defaultClassification: TransactionClassification? = null,
    val linkedBillId: String? = null,
    val subscriptionMerchantKey: String? = null,
    val confirmation: MerchantConfirmation = MerchantConfirmation.USER_CONFIRMED,
    val lastSeenEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class SmartRuleMatch(
    val rawNameContains: String? = null,
    val merchantProfileId: String? = null,
    val accountId: String? = null,
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    val category: String? = null,
    val classification: TransactionClassification? = null,
    val direction: TransactionDirection? = null
)

data class SmartRuleAction(
    val displayName: String? = null,
    val category: String? = null,
    val classification: TransactionClassification? = null,
    val excludeFromSpending: Boolean? = null,
    val recurringStatus: SubscriptionStatus? = null
)

data class SmartTransactionRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val match: SmartRuleMatch,
    val action: SmartRuleAction,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class RulePreviewRow(
    val transactionId: String,
    val before: FinanceTransaction,
    val after: FinanceTransaction,
    val winningRuleId: String
)

data class SmartRuleBatchResult(
    val transactions: List<FinanceTransaction>,
    val subscriptionPreferences: List<SubscriptionPreference>
)

data class ReviewResolution(
    val fingerprint: String,
    val disposition: ReviewDisposition,
    val resolvedAtEpochMs: Long = System.currentTimeMillis()
)

data class ReviewItem(
    val fingerprint: String,
    val type: ReviewType,
    val transactionIds: List<String> = emptyList(),
    val accountId: String? = null,
    val debtId: String? = null,
    val title: String,
    val explanation: String,
    val confidence: Double,
    val suggestedCategory: String? = null,
    val suggestedClassification: TransactionClassification? = null,
    val suggestedMerchantName: String? = null
)
