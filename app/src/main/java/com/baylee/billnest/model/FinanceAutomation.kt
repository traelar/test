package com.baylee.billnest.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class BillMatchProposal(
    val billId: String,
    val transactionId: String,
    val matchedDueDateIso: String,
    val score: Double,
    val status: MatchStatus,
    val reason: String
)

data class RecurringCharge(
    val merchantKey: String,
    val displayName: String,
    val averageAmount: Double,
    val monthlyEstimate: Double,
    val lastDateIso: String,
    val occurrences: Int
)

object BillMatcher {
    const val AUTO_THRESHOLD = 0.85
    const val REVIEW_THRESHOLD = 0.60

    fun bestProposal(transaction: FinanceTransaction, bills: List<Bill>): BillMatchProposal? {
        if (transaction.type != TransactionType.EXPENSE || transaction.pending || transaction.excludedFromSpending) return null
        val txDate = runCatching { LocalDate.parse(transaction.dateIso) }.getOrNull() ?: return null
        val scored = bills.filter { !it.isPaidFor() }.mapNotNull { bill ->
            val due = runCatching { bill.dueDate() }.getOrNull() ?: return@mapNotNull null
            val days = abs(ChronoUnit.DAYS.between(due, txDate))
            if (days > 18) return@mapNotNull null
            val amountDiff = abs(transaction.amount - bill.amount)
            val amountTolerance = maxOf(1.0, bill.amount * 0.02)
            val amountScore = when {
                amountDiff <= amountTolerance -> 0.45
                amountDiff <= maxOf(3.0, bill.amount * 0.05) -> 0.30
                amountDiff <= maxOf(8.0, bill.amount * 0.10) -> 0.15
                else -> 0.0
            }
            if (amountScore == 0.0) return@mapNotNull null
            val dateScore = when {
                days <= 1 -> 0.30
                days <= 3 -> 0.25
                days <= 7 -> 0.20
                days <= 14 -> 0.10
                else -> 0.05
            }
            val accountScore = if (!bill.accountId.isNullOrBlank() && bill.accountId == transaction.accountKey) 0.15 else 0.0
            val nameScore = merchantSimilarity(bill.name, transaction.merchantName ?: transaction.name) * 0.20
            val score = (amountScore + dateScore + accountScore + nameScore).coerceAtMost(1.0)
            val status = when {
                score >= AUTO_THRESHOLD -> MatchStatus.AUTO_MATCHED
                score >= REVIEW_THRESHOLD -> MatchStatus.NEEDS_REVIEW
                else -> return@mapNotNull null
            }
            BillMatchProposal(
                billId = bill.id,
                transactionId = transaction.id,
                matchedDueDateIso = bill.dueDateIso,
                score = score,
                status = status,
                reason = buildString {
                    append("amount")
                    if (days <= 7) append(", date")
                    if (accountScore > 0) append(", account")
                    if (nameScore > 0.05) append(", merchant")
                }
            )
        }
        return scored.maxByOrNull { it.score }
    }

    private fun merchantSimilarity(left: String, right: String): Double {
        val a = words(left)
        val b = words(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a.joinToString("") == b.joinToString("")) return 1.0
        val intersection = a.intersect(b).size.toDouble()
        return (intersection / maxOf(a.size, b.size).toDouble()).coerceIn(0.0, 1.0)
    }

    private fun words(value: String): Set<String> = value.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(Regex("\\s+"))
        .filter { it.length >= 3 }
        .toSet()
}

object RecurringDetector {
    fun detect(transactions: List<FinanceTransaction>): List<RecurringCharge> {
        val groups = transactions
            .filter { it.type == TransactionType.EXPENSE && !it.pending && !it.excludedFromSpending }
            .groupBy { merchantKey(it.merchantName ?: it.name) }

        return groups.mapNotNull { (key, raw) ->
            if (key.isBlank() || raw.size < 3) return@mapNotNull null
            val sorted = raw.sortedBy { it.dateIso }
            val dates = sorted.mapNotNull { runCatching { LocalDate.parse(it.dateIso) }.getOrNull() }
            if (dates.size < 3) return@mapNotNull null
            val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }
            val monthlyLike = gaps.count { it in 20..40 } >= gaps.size.coerceAtLeast(1) * 0.6
            if (!monthlyLike) return@mapNotNull null
            val average = sorted.map { it.amount }.average()
            if (average <= 0.0) return@mapNotNull null
            val withinRange = sorted.count { abs(it.amount - average) <= maxOf(2.0, average * 0.15) }
            if (withinRange < 3) return@mapNotNull null
            RecurringCharge(
                merchantKey = key,
                displayName = sorted.last().merchantName?.takeIf { it.isNotBlank() } ?: sorted.last().name,
                averageAmount = average,
                monthlyEstimate = average,
                lastDateIso = sorted.last().dateIso,
                occurrences = sorted.size
            )
        }.sortedByDescending { it.monthlyEstimate }
    }

    private fun merchantKey(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\b(com|inc|llc|payment|purchase|debit|card)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
