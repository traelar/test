package com.baylee.billnest.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

private const val REVIEW_SCAN_DAYS = 120L

private fun reviewFingerprint(type: ReviewType, vararg parts: String): String =
    (listOf(type.name.lowercase()) + parts.map { it.trim().lowercase() })
        .joinToString(":")

private fun parseReviewDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

private fun reviewRecentTransactions(data: AppData, referenceDate: LocalDate): List<FinanceTransaction> {
    val earliest = referenceDate.minusDays(REVIEW_SCAN_DAYS)
    return data.transactions.filter { transaction ->
        if (transaction.pending) return@filter false
        val date = parseReviewDate(transaction.dateIso) ?: return@filter false
        !date.isBefore(earliest) && !date.isAfter(referenceDate.plusDays(1))
    }
}

private fun merchantForReview(
    transaction: FinanceTransaction,
    profiles: List<MerchantProfile>
): MerchantProfile? = transaction.merchantProfileId
    ?.let { id -> profiles.firstOrNull { it.id == id } }
    ?: resolveMerchantProfile(transaction, profiles)

private fun titleCaseMerchant(raw: String): String = merchantIdentityKey(raw)
    .split(' ')
    .filter { it.isNotBlank() }
    .joinToString(" ") { word -> word.replaceFirstChar { char -> char.uppercase() } }
    .ifBlank { raw.trim().ifBlank { "Unknown merchant" } }

private fun moneyKey(amount: Double): String = (abs(amount) * 100.0).roundToLong().toString()

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    if (sorted.isEmpty()) return 0.0
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
}

fun reviewResolutionFor(
    item: ReviewItem,
    disposition: ReviewDisposition,
    resolvedAtEpochMs: Long = System.currentTimeMillis()
): ReviewResolution = ReviewResolution(
    fingerprint = item.fingerprint,
    disposition = disposition,
    resolvedAtEpochMs = resolvedAtEpochMs
)

/**
 * Build a deterministic, non-mutating list of finance records that deserve a human look.
 * Resolutions suppress the exact facts that were reviewed; if the underlying facts change, a new
 * fingerprint can surface the issue again.
 */
fun generateReviewInbox(
    data: AppData,
    merchantProfiles: List<MerchantProfile> = emptyList(),
    resolutions: List<ReviewResolution> = emptyList(),
    referenceDate: LocalDate = LocalDate.now()
): List<ReviewItem> {
    val rows = reviewRecentTransactions(data, referenceDate)
    val items = mutableListOf<ReviewItem>()

    // Missing spending categories.
    rows.filter { transaction ->
        !transaction.transfer &&
            !transaction.income &&
            transaction.amount >= 0.0 &&
            (transaction.category.isBlank() || transaction.category.equals("Other", ignoreCase = true))
    }.forEach { transaction ->
        val profile = merchantForReview(transaction, merchantProfiles)
        items += ReviewItem(
            fingerprint = reviewFingerprint(ReviewType.UNCATEGORIZED, transaction.id),
            type = ReviewType.UNCATEGORIZED,
            transactionIds = listOf(transaction.id),
            title = "Choose a category for ${transaction.effectiveDisplayName()}",
            explanation = "This spending transaction is still in Other, so budgets and Insights can be more accurate after you categorize it.",
            confidence = 0.96,
            suggestedCategory = profile?.preferredCategory
        )
    }

    // Unknown Plaid merchants are grouped so repeated transactions create one useful review item.
    rows.filter { transaction ->
        transaction.source == TransactionSource.PLAID && merchantForReview(transaction, merchantProfiles) == null
    }.groupBy { merchantIdentityKey(it.name).ifBlank { it.name.trim().lowercase() } }
        .toSortedMap()
        .forEach { (key, grouped) ->
            val orderedIds = grouped.map { it.id }.sorted()
            val sample = grouped.maxByOrNull { it.dateIso } ?: return@forEach
            items += ReviewItem(
                fingerprint = reviewFingerprint(ReviewType.UNKNOWN_MERCHANT, key),
                type = ReviewType.UNKNOWN_MERCHANT,
                transactionIds = orderedIds,
                title = "Identify ${titleCaseMerchant(sample.name)}",
                explanation = "BillNest does not have a confirmed merchant profile for this bank description yet.",
                confidence = 0.72,
                suggestedMerchantName = titleCaseMerchant(sample.name)
            )
        }

    // Opposite-direction, same-amount movements between two known accounts are likely transfers.
    val transferCandidates = rows.filter {
        !it.transfer && it.accountId != null && abs(it.amount) >= 0.01
    }.sortedBy { it.id }
    val transferPairs = mutableSetOf<String>()
    transferCandidates.forEachIndexed { index, first ->
        for (second in transferCandidates.drop(index + 1)) {
            if (first.accountId == second.accountId) continue
            if ((first.amount < 0.0) == (second.amount < 0.0)) continue
            if (abs(abs(first.amount) - abs(second.amount)) > 0.01) continue
            val firstDate = parseReviewDate(first.dateIso) ?: continue
            val secondDate = parseReviewDate(second.dateIso) ?: continue
            if (abs(ChronoUnit.DAYS.between(firstDate, secondDate)) > 2L) continue
            val ids = listOf(first.id, second.id).sorted()
            val pairKey = ids.joinToString("|")
            if (!transferPairs.add(pairKey)) continue
            items += ReviewItem(
                fingerprint = reviewFingerprint(ReviewType.POSSIBLE_TRANSFER, ids[0], ids[1], moneyKey(first.amount)),
                type = ReviewType.POSSIBLE_TRANSFER,
                transactionIds = ids,
                title = "Possible transfer between accounts",
                explanation = "Two accounts show the same amount moving in opposite directions within two days.",
                confidence = 0.94,
                suggestedClassification = TransactionClassification.TRANSFER
            )
        }
    }

    // Strong payroll wording on an unclassified Plaid inflow.
    val incomeSignal = Regex("\\b(payroll|paycheck|salary|direct deposit|wages?)\\b", RegexOption.IGNORE_CASE)
    rows.filter { transaction ->
        transaction.source == TransactionSource.PLAID &&
            transaction.amount < 0.0 &&
            !transaction.transfer &&
            !transaction.income &&
            !transaction.userClassificationOverride &&
            incomeSignal.containsMatchIn(transaction.name)
    }.forEach { transaction ->
        items += ReviewItem(
            fingerprint = reviewFingerprint(ReviewType.POSSIBLE_INCOME, transaction.id),
            type = ReviewType.POSSIBLE_INCOME,
            transactionIds = listOf(transaction.id),
            title = "Possible income from ${transaction.effectiveDisplayName()}",
            explanation = "This inflow looks like payroll but has not been confirmed as income.",
            confidence = 0.93,
            suggestedClassification = TransactionClassification.INCOME
        )
    }

    // Reuse the established recurring detector, but expose all matching transaction IDs for review.
    val existingSubscriptionKeys = data.subscriptionPreferences.map { it.merchantKey }.toSet()
    detectSubscriptions(rows).forEach { suggestion ->
        val key = subscriptionKey(suggestion.name)
        if (key in existingSubscriptionKeys) return@forEach
        val identity = merchantIdentityKey(suggestion.name)
        val matchedIds = rows.filter { transaction ->
            !transaction.pending &&
                !transaction.transfer &&
                !transaction.income &&
                merchantIdentityKey(transaction.name) == identity
        }.map { it.id }.sorted()
        items += ReviewItem(
            fingerprint = reviewFingerprint(ReviewType.POSSIBLE_RECURRING, identity),
            type = ReviewType.POSSIBLE_RECURRING,
            transactionIds = matchedIds,
            title = "Recurring charge: ${suggestion.name}",
            explanation = "BillNest found a repeating ${suggestion.frequency.name.lowercase()} charge with a stable amount.",
            confidence = 0.88,
            suggestedMerchantName = suggestion.name
        )
    }

    // Exact duplicate candidates: same account, date, direction, amount, and normalized merchant.
    rows.filter { it.accountId != null && !it.transfer }
        .groupBy { transaction ->
            listOf(
                transaction.accountId.orEmpty(),
                transaction.dateIso,
                transactionDirection(transaction).name,
                moneyKey(transaction.amount),
                merchantIdentityKey(transaction.name)
            ).joinToString("|")
        }
        .values
        .filter { it.size > 1 }
        .forEach { grouped ->
            val ids = grouped.map { it.id }.sorted()
            items += ReviewItem(
                fingerprint = reviewFingerprint(ReviewType.POTENTIAL_DUPLICATE, *ids.toTypedArray()),
                type = ReviewType.POTENTIAL_DUPLICATE,
                transactionIds = ids,
                title = "Potential duplicate transaction",
                explanation = "These transactions share the same account, date, amount, direction, and merchant identity.",
                confidence = 0.97
            )
        }

    // A profile preference that disagrees with an automatic category deserves review; manual choices win.
    rows.filterNot { it.userClassificationOverride }.forEach { transaction ->
        val profile = merchantForReview(transaction, merchantProfiles) ?: return@forEach
        val preferred = profile.preferredCategory?.takeIf { it.isNotBlank() } ?: return@forEach
        if (normalizeCategoryKey(transaction.category) == normalizeCategoryKey(preferred)) return@forEach
        items += ReviewItem(
            fingerprint = reviewFingerprint(
                ReviewType.CATEGORY_CONFLICT,
                transaction.id,
                profile.id,
                normalizeCategoryKey(transaction.category),
                normalizeCategoryKey(preferred)
            ),
            type = ReviewType.CATEGORY_CONFLICT,
            transactionIds = listOf(transaction.id),
            title = "Category conflict for ${profile.displayName}",
            explanation = "The transaction category differs from the preferred category saved for this merchant.",
            confidence = 0.86,
            suggestedCategory = canonicalCategoryName(data, preferred),
            suggestedMerchantName = profile.displayName
        )
    }

    // Detect the newest material outlier only after at least four historical observations.
    rows.filter { !it.transfer && !it.income && abs(it.amount) >= 0.01 }
        .groupBy { transaction ->
            transaction.merchantProfileId?.let { "profile:$it" }
                ?: "merchant:${merchantIdentityKey(transaction.name)}"
        }
        .values
        .filter { it.size >= 5 }
        .forEach { grouped ->
            val ordered = grouped.sortedWith(compareBy<FinanceTransaction> { it.dateIso }.thenBy { it.id })
            val latest = ordered.last()
            val history = ordered.dropLast(1).map { abs(it.amount) }
            if (history.size < 4) return@forEach
            val normal = median(history)
            val current = abs(latest.amount)
            if (normal <= 0.0) return@forEach
            val ratio = current / normal
            if (abs(current - normal) < 20.0 || (ratio <= 1.75 && ratio >= 0.40)) return@forEach
            items += ReviewItem(
                fingerprint = reviewFingerprint(
                    ReviewType.UNUSUAL_AMOUNT,
                    latest.merchantProfileId ?: merchantIdentityKey(latest.name),
                    latest.id,
                    moneyKey(latest.amount)
                ),
                type = ReviewType.UNUSUAL_AMOUNT,
                transactionIds = listOf(latest.id),
                title = "Unusual amount at ${latest.effectiveDisplayName()}",
                explanation = "This charge is far outside the recent amount pattern for the same merchant.",
                confidence = 0.84,
                suggestedMerchantName = latest.effectiveDisplayName()
            )
        }

    data.accounts.filter { account ->
        account.role == AccountRole.OTHER || account.type == AccountType.OTHER
    }.forEach { account ->
        val missing = buildList {
            if (account.role == AccountRole.OTHER) add("role")
            if (account.type == AccountType.OTHER) add("type")
        }
        items += ReviewItem(
            fingerprint = reviewFingerprint(ReviewType.ACCOUNT_METADATA_MISSING, account.id, missing.joinToString("+")),
            type = ReviewType.ACCOUNT_METADATA_MISSING,
            accountId = account.id,
            title = "Finish setting up ${account.name}",
            explanation = "BillNest needs ${missing.joinToString(" and ")} information to classify this account correctly.",
            confidence = 0.90
        )
    }

    data.debts.filter { it.balance > 0.0 }.forEach { debt ->
        val missing = buildList {
            if (debt.apr <= 0.0) add("APR")
            if (debt.minimumPayment <= 0.0) add("minimum payment")
            if (debt.type == DebtType.CREDIT_CARD && debt.creditLimit <= 0.0) add("credit limit")
        }
        if (missing.isEmpty()) return@forEach
        items += ReviewItem(
            fingerprint = reviewFingerprint(ReviewType.DEBT_METADATA_MISSING, debt.id, missing.joinToString("+")),
            type = ReviewType.DEBT_METADATA_MISSING,
            debtId = debt.id,
            title = "Finish debt details for ${debt.name}",
            explanation = "Add ${missing.joinToString(", ")} so payoff forecasts and utilization are accurate.",
            confidence = 0.92
        )
    }

    val resolvedFingerprints = resolutions.map { it.fingerprint }.toSet()
    return items
        .filterNot { it.fingerprint in resolvedFingerprints }
        .distinctBy { it.fingerprint }
        .sortedWith(
            compareByDescending<ReviewItem> { it.confidence }
                .thenBy { it.type.ordinal }
                .thenBy { it.title }
                .thenBy { it.fingerprint }
        )
}
