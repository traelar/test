package com.baylee.billnest.model

import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

fun reviewFingerprint(type: ReviewType, entityKey: String, evidenceKey: String): String {
    val raw = "${type.name}|$entityKey|$evidenceKey"
    return MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun reviewDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

private fun reviewMoneyKey(amount: Double): String = (abs(amount) * 100.0).roundToLong().toString()

private fun reviewMedian(values: List<Double>): Double {
    val sorted = values.sorted()
    if (sorted.isEmpty()) return 0.0
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}

private fun profileForReview(transaction: FinanceTransaction, data: AppData): MerchantProfile? =
    transaction.merchantProfileId
        ?.let { id -> data.merchantProfiles.firstOrNull { it.id == id } }
        ?: resolveMerchantProfile(transaction, data.merchantProfiles)

private fun reviewMerchantKey(transaction: FinanceTransaction, data: AppData): String {
    val profile = profileForReview(transaction, data)
    return profile?.let { "profile:${it.id}" }
        ?: "merchant:${merchantIdentityKey(transaction.name).ifBlank { transaction.name.trim().lowercase() }}"
}

private fun merchantTitle(raw: String): String = merchantIdentityKey(raw)
    .split(' ')
    .filter { it.isNotBlank() }
    .joinToString(" ") { token -> token.replaceFirstChar { it.uppercase() } }
    .ifBlank { raw.trim().ifBlank { "Unknown merchant" } }

private fun isComparableSpending(transaction: FinanceTransaction): Boolean =
    !transaction.pending &&
        !transaction.transfer &&
        !transaction.income &&
        transaction.amount >= 0.0

private fun isUsefulCategory(value: String): Boolean {
    val normalized = normalizeCategoryKey(value)
    return normalized.isNotBlank() &&
        normalized != "other" &&
        normalized != "income" &&
        normalized != "transfer" &&
        !normalized.startsWith("transfer ")
}

/**
 * Generate all current Stage A review candidates. Detection is pure: this function never edits,
 * reclassifies, deletes, tracks, or otherwise mutates finance data.
 */
fun generateReviewItems(data: AppData): List<ReviewItem> {
    val rows = data.transactions.filterNot { it.pending }
    val items = mutableListOf<ReviewItem>()

    // Uncategorized spending.
    rows.filter { transaction ->
        isComparableSpending(transaction) &&
            (transaction.category.isBlank() || transaction.category.equals("Other", ignoreCase = true))
    }.forEach { transaction ->
        val evidence = "amount=${reviewMoneyKey(transaction.amount)}|category=${normalizeCategoryKey(transaction.category)}"
        items += ReviewItem(
            fingerprint = reviewFingerprint(ReviewType.UNCATEGORIZED, transaction.id, evidence),
            type = ReviewType.UNCATEGORIZED,
            transactionIds = listOf(transaction.id),
            title = "Choose a category for ${transaction.effectiveDisplayName()}",
            explanation = "This spending transaction is still uncategorized, so budgets and Insights cannot place it accurately yet.",
            confidence = 0.98,
            suggestedCategory = profileForReview(transaction, data)?.preferredCategory
        )
    }

    // Unknown merchant. Repeated descriptions collapse into one merchant-level item.
    rows.filter { transaction ->
        transaction.source == TransactionSource.PLAID && profileForReview(transaction, data) == null
    }.groupBy { merchantIdentityKey(it.name).ifBlank { it.name.trim().lowercase() } }
        .forEach { (identity, merchantRows) ->
            val ids = merchantRows.map { it.id }.sorted()
            val latest = merchantRows.maxWithOrNull(compareBy<FinanceTransaction> { it.dateIso }.thenBy { it.id })
                ?: return@forEach
            items += ReviewItem(
                fingerprint = reviewFingerprint(ReviewType.UNKNOWN_MERCHANT, identity, identity),
                type = ReviewType.UNKNOWN_MERCHANT,
                transactionIds = ids,
                title = "Identify ${merchantTitle(latest.name)}",
                explanation = "BillNest does not have a confirmed merchant profile for this bank description.",
                confidence = 0.74,
                suggestedMerchantName = merchantTitle(latest.name)
            )
        }

    // Possible transfer pair: opposite signs, equal absolute amount within $0.01, different accounts,
    // dates no more than three days apart.
    val transferCandidates = rows.filter { !it.transfer && it.accountId != null && abs(it.amount) >= 0.01 }
        .sortedBy { it.id }
    transferCandidates.forEachIndexed { index, first ->
        for (second in transferCandidates.drop(index + 1)) {
            if (first.accountId == second.accountId) continue
            if ((first.amount < 0.0) == (second.amount < 0.0)) continue
            if (abs(abs(first.amount) - abs(second.amount)) > 0.01) continue
            val firstDate = reviewDate(first.dateIso) ?: continue
            val secondDate = reviewDate(second.dateIso) ?: continue
            if (abs(ChronoUnit.DAYS.between(firstDate, secondDate)) > 3L) continue

            val ids = listOf(first.id, second.id).sorted()
            val evidence = "amount=${reviewMoneyKey(first.amount)}|dates=${listOf(first.dateIso, second.dateIso).sorted().joinToString(",")}" 
            items += ReviewItem(
                fingerprint = reviewFingerprint(ReviewType.POSSIBLE_TRANSFER, ids.joinToString("|"), evidence),
                type = ReviewType.POSSIBLE_TRANSFER,
                transactionIds = ids,
                title = "Possible transfer between accounts",
                explanation = "Two different accounts show the same amount moving in opposite directions within three days.",
                confidence = 0.95,
                suggestedClassification = TransactionClassification.TRANSFER
            )
        }
    }

    // Possible income/paycheck: unclassified inflow with strong payroll language or a payer that
    // already forms a payday-like cadence.
    val payrollWords = Regex("\\b(payroll|paycheck|salary|direct deposit|wages?)\\b", RegexOption.IGNORE_CASE)
    val paydayKeys = detectPaydayPatterns(rows).map { merchantIdentityKey(it.label) }.filter { it.isNotBlank() }.toSet()
    rows.filter { transaction ->
        val identity = merchantIdentityKey(transaction.name)
        transaction.amount < 0.0 &&
            !transaction.transfer &&
            !transaction.income &&
            !transaction.category.equals("Income", ignoreCase = true) &&
            !transaction.userClassificationOverride &&
            (payrollWords.containsMatchIn(transaction.name) || identity in paydayKeys)
    }.forEach { transaction ->
        val evidence = "amount=${reviewMoneyKey(transaction.amount)}|merchant=${merchantIdentityKey(transaction.name)}"
        items += ReviewItem(
            fingerprint = reviewFingerprint(ReviewType.POSSIBLE_INCOME, transaction.id, evidence),
            type = ReviewType.POSSIBLE_INCOME,
            transactionIds = listOf(transaction.id),
            title = "Possible income from ${transaction.effectiveDisplayName()}",
            explanation = "This inflow looks like a paycheck or direct deposit but has not been confirmed as income.",
            confidence = 0.93,
            suggestedClassification = TransactionClassification.INCOME
        )
    }

    // Current recurring detector, excluding both confirmed and ignored preferences.
    val handledSubscriptionKeys = data.subscriptionPreferences.map { it.merchantKey }.toSet()
    detectSubscriptions(rows).forEach { suggestion ->
        val preferenceKey = subscriptionKey(suggestion.name)
        if (preferenceKey in handledSubscriptionKeys) return@forEach
        val identity = merchantIdentityKey(suggestion.name)
        val transactionIds = rows.filter { transaction ->
            !transaction.transfer &&
                !transaction.income &&
                merchantIdentityKey(transaction.name) == identity
        }.map { it.id }.sorted()
        val amountBucket = reviewMoneyKey(suggestion.typicalAmount)
        val evidence = "frequency=${suggestion.frequency.name}|amount=$amountBucket"
        items += ReviewItem(
            fingerprint = reviewFingerprint(ReviewType.POSSIBLE_RECURRING, identity, evidence),
            type = ReviewType.POSSIBLE_RECURRING,
            transactionIds = transactionIds,
            title = "Recurring charge: ${suggestion.name}",
            explanation = "BillNest found a repeating ${suggestion.frequency.name.lowercase()} charge with a consistent amount.",
            confidence = 0.88,
            suggestedMerchantName = suggestion.name
        )
    }

    // Potential duplicate: same merchant/account/absolute amount and dates within one day.
    val duplicateCandidates = rows.filter { !it.transfer && it.accountId != null }.sortedBy { it.id }
    duplicateCandidates.forEachIndexed { index, first ->
        for (second in duplicateCandidates.drop(index + 1)) {
            if (first.accountId != second.accountId) continue
            if (reviewMerchantKey(first, data) != reviewMerchantKey(second, data)) continue
            if (abs(abs(first.amount) - abs(second.amount)) > 0.01) continue
            val firstDate = reviewDate(first.dateIso) ?: continue
            val secondDate = reviewDate(second.dateIso) ?: continue
            if (abs(ChronoUnit.DAYS.between(firstDate, secondDate)) > 1L) continue

            val ids = listOf(first.id, second.id).sorted()
            val evidence = "merchant=${reviewMerchantKey(first, data)}|account=${first.accountId}|amount=${reviewMoneyKey(first.amount)}|dates=${listOf(first.dateIso, second.dateIso).sorted().joinToString(",")}" 
            items += ReviewItem(
                fingerprint = reviewFingerprint(ReviewType.POTENTIAL_DUPLICATE, ids.joinToString("|"), evidence),
                type = ReviewType.POTENTIAL_DUPLICATE,
                transactionIds = ids,
                title = "Potential duplicate transaction",
                explanation = "These transactions have the same merchant, account, and amount within one day.",
                confidence = 0.97
            )
        }
    }

    // Category conflict: historical same-merchant rows establish a >=70% dominant category.
    val categoryCandidates = rows.filter(::isComparableSpending)
    categoryCandidates.forEach { current ->
        if (current.userClassificationOverride || !isUsefulCategory(current.category)) return@forEach
        val currentDate = reviewDate(current.dateIso) ?: return@forEach
        val merchantKey = reviewMerchantKey(current, data)
        val history = categoryCandidates.filter { prior ->
            prior.id != current.id &&
                reviewMerchantKey(prior, data) == merchantKey &&
                isUsefulCategory(prior.category) &&
                (reviewDate(prior.dateIso)?.isBefore(currentDate) == true ||
                    (prior.dateIso == current.dateIso && prior.id < current.id))
        }
        if (history.size < 2) return@forEach

        val categoryGroups = history.groupBy { normalizeCategoryKey(it.category) }
        val dominant = categoryGroups.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<FinanceTransaction>>> { it.value.size }.thenBy { it.key })
            .firstOrNull() ?: return@forEach
        val share = dominant.value.size.toDouble() / history.size.toDouble()
        if (share < 0.70 || dominant.key == normalizeCategoryKey(current.category)) return@forEach
        val suggested = canonicalCategoryName(data, dominant.value.first().category)
        val evidence = "current=${normalizeCategoryKey(current.category)}|suggested=${normalizeCategoryKey(suggested)}|history=${history.size}|share=${(share * 100).roundToLong()}"
        items += ReviewItem(
            fingerprint = reviewFingerprint(ReviewType.CATEGORY_CONFLICT, current.id, evidence),
            type = ReviewType.CATEGORY_CONFLICT,
            transactionIds = listOf(current.id),
            title = "Category conflict for ${current.effectiveDisplayName()}",
            explanation = "Most previous transactions from this merchant use $suggested instead of ${current.category}.",
            confidence = share.coerceIn(0.70, 0.99),
            suggestedCategory = suggested
        )
    }

    // Unusual amount: at least three prior merchant amounts, current >=2x median and >=$25 above it.
    rows.filter { isComparableSpending(it) && abs(it.amount) >= 0.01 }
        .groupBy { reviewMerchantKey(it, data) }
        .forEach { (merchantKey, merchantRows) ->
            val ordered = merchantRows.sortedWith(compareBy<FinanceTransaction> { it.dateIso }.thenBy { it.id })
            ordered.forEachIndexed { index, current ->
                if (index < 3) return@forEachIndexed
                val prior = ordered.take(index).map { abs(it.amount) }
                val normal = reviewMedian(prior)
                val currentAmount = abs(current.amount)
                if (normal <= 0.0 || currentAmount < normal * 2.0 || currentAmount - normal < 25.0) return@forEachIndexed
                val evidence = "merchant=$merchantKey|amount=${reviewMoneyKey(current.amount)}|median=${reviewMoneyKey(normal)}"
                items += ReviewItem(
                    fingerprint = reviewFingerprint(ReviewType.UNUSUAL_AMOUNT, current.id, evidence),
                    type = ReviewType.UNUSUAL_AMOUNT,
                    transactionIds = listOf(current.id),
                    title = "Unusual amount at ${current.effectiveDisplayName()}",
                    explanation = "This amount is at least twice the recent median and more than $25 above it.",
                    confidence = 0.86,
                    suggestedMerchantName = current.effectiveDisplayName()
                )
            }
        }

    // Account metadata: Stage A only needs an explicit role classification.
    data.accounts.filter { it.role == AccountRole.OTHER }.forEach { account ->
        items += ReviewItem(
            fingerprint = reviewFingerprint(ReviewType.ACCOUNT_METADATA_MISSING, account.id, "missing=role"),
            type = ReviewType.ACCOUNT_METADATA_MISSING,
            accountId = account.id,
            title = "Choose a role for ${account.name}",
            explanation = "BillNest needs to know whether this account is spending, savings, credit, or another role.",
            confidence = 0.91
        )
    }

    // Debt metadata: only Stage A fields, not future statement intelligence.
    data.debts.filter { it.balance > 0.0 }.forEach { debt ->
        val missing = buildList {
            if (debt.apr <= 0.0) add("apr")
            if (debt.minimumPayment <= 0.0) add("minimum")
            if (debt.type == DebtType.CREDIT_CARD && debt.creditLimit <= 0.0) add("limit")
        }
        if (missing.isEmpty()) return@forEach
        val evidence = "missing=${missing.sorted().joinToString(",")}" 
        items += ReviewItem(
            fingerprint = reviewFingerprint(ReviewType.DEBT_METADATA_MISSING, debt.id, evidence),
            type = ReviewType.DEBT_METADATA_MISSING,
            debtId = debt.id,
            title = "Finish debt details for ${debt.name}",
            explanation = "Add the missing debt details so payoff and utilization calculations stay accurate.",
            confidence = 0.92
        )
    }

    val transactionById = rows.associateBy { it.id }
    fun reviewItemDate(item: ReviewItem): String = item.transactionIds
        .mapNotNull { transactionById[it]?.dateIso }
        .maxOrNull()
        .orEmpty()

    return items
        .distinctBy { it.fingerprint }
        .sortedWith(
            compareByDescending<ReviewItem> { it.confidence }
                .thenByDescending { reviewItemDate(it) }
                .thenBy { it.fingerprint }
        )
}

fun visibleReviewItems(data: AppData): List<ReviewItem> {
    val suppressed = data.reviewResolutions.map { it.fingerprint }.toSet()
    return generateReviewItems(data).filterNot { it.fingerprint in suppressed }
}

fun reviewAttentionCount(data: AppData): Int = visibleReviewItems(data).size

fun reviewResolutionFor(
    item: ReviewItem,
    disposition: ReviewDisposition,
    resolvedAtEpochMs: Long = System.currentTimeMillis()
): ReviewResolution = ReviewResolution(
    fingerprint = item.fingerprint,
    disposition = disposition,
    resolvedAtEpochMs = resolvedAtEpochMs
)
