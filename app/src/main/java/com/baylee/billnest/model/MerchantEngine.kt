package com.baylee.billnest.model

/** Normalize bank merchant text for matching without altering the Plaid-owned raw name. */
fun merchantIdentityKey(value: String): String = value
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), " ")
    .split(Regex("\\s+"))
    .filter { token -> token.isNotBlank() && !token.all(Char::isDigit) }
    .joinToString(" ")
    .trim()

fun resolveMerchantProfile(
    transaction: FinanceTransaction,
    profiles: List<MerchantProfile>
): MerchantProfile? {
    val transactionKey = merchantIdentityKey(transaction.name)
    if (transactionKey.isBlank()) return null

    return profiles
        .flatMap { profile ->
            (profile.aliases + profile.displayName)
                .map { merchantIdentityKey(it) }
                .filter { it.isNotBlank() && transactionKey.contains(it) }
                .map { aliasKey -> profile to aliasKey.length }
        }
        .sortedWith(
            compareByDescending<Pair<MerchantProfile, Int>> { it.second }
                .thenByDescending { it.first.updatedAtEpochMs }
                .thenBy { it.first.id }
        )
        .firstOrNull()
        ?.first
}

private fun applyAutomaticMerchantClassification(
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

fun applyMerchantProfile(
    transaction: FinanceTransaction,
    profile: MerchantProfile,
    data: AppData
): FinanceTransaction {
    var result = transaction.copy(
        displayNameOverride = profile.displayName.trim().takeIf { it.isNotBlank() },
        merchantProfileId = profile.id
    )

    if (transaction.userClassificationOverride) return result

    profile.preferredCategory
        ?.takeIf { it.isNotBlank() }
        ?.let { category -> result = result.copy(category = canonicalCategoryName(data, category)) }

    profile.defaultClassification?.let { classification ->
        result = applyAutomaticMerchantClassification(result, classification)
    }

    return result
}

fun applyMerchantProfiles(
    transactions: List<FinanceTransaction>,
    profiles: List<MerchantProfile>,
    data: AppData
): List<FinanceTransaction> = transactions.map { transaction ->
    resolveMerchantProfile(transaction, profiles)
        ?.let { profile -> applyMerchantProfile(transaction, profile, data) }
        ?: transaction
}
