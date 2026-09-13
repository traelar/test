package com.baylee.billnest.model

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private fun meaningfulBillCategory(value: String): String? {
    val cleaned = value.trim()
    if (cleaned.isBlank()) return null
    val normalized = cleaned.lowercase().replace('_', ' ')
    if (normalized == "other" || normalized == "income" || normalized == "transfer" || normalized.startsWith("transfer ")) return null
    return normalized
}

/**
 * Finds the strongest transaction match for one bill occurrence. Category is allowed to supply the
 * semantic signal when bank merchant text is opaque (for example an ACH withdrawal categorized as Mortgage).
 */
fun findBillPaymentMatch(bill: Bill, transactions: List<FinanceTransaction>): BillMatchSuggestion? {
    if (bill.isPaidFor()) return null
    val due = runCatching { bill.dueDate() }.getOrNull() ?: return null
    val billCategory = meaningfulBillCategory(bill.category)
    val billWords = normalizeBillText(bill.name).split(' ').filter { it.length > 2 }.toSet()

    return transactions.asSequence()
        .filterNot { it.transfer || it.income || it.excludedFromSpending }
        .mapNotNull { transaction ->
            val date = runCatching { LocalDate.parse(transaction.dateIso) }.getOrNull() ?: return@mapNotNull null
            val dayGap = abs(ChronoUnit.DAYS.between(due, date))
            val amountGap = abs(abs(transaction.amount) - bill.amount.coerceAtLeast(0.0))
            val amountRatio = if (bill.amount > 0.0) amountGap / bill.amount else 1.0
            if (dayGap > 7 || amountRatio > 0.20) return@mapNotNull null

            val transactionWords = normalizeBillText(transaction.name).split(' ').filter { it.length > 2 }.toSet()
            val nameMatch = billWords.intersect(transactionWords).isNotEmpty()
            val transactionCategory = meaningfulBillCategory(transaction.category)
            val categoryMatch = billCategory != null && transactionCategory != null && billCategory == transactionCategory
            val semanticMatch = nameMatch || categoryMatch
            val confidence = (
                (1.0 - amountRatio) * 0.65 +
                    (1.0 - dayGap / 7.0) * 0.20 +
                    if (semanticMatch) 0.15 else 0.0
                ).coerceIn(0.0, 1.0)

            BillMatchSuggestion(
                billId = bill.id,
                transactionId = transaction.id,
                confidence = confidence,
                highConfidence = confidence >= 0.90
            )
        }
        .maxByOrNull { it.confidence }
}

private fun normalizeBillText(value: String): String = value.lowercase()
    .replace(Regex("\\d+"), " ")
    .replace(Regex("[^a-z]+"), " ")
    .replace(Regex("\\b(payment|purchase|debit|online|pos|withdrawal|ach)\\b"), " ")
    .trim()

/** Records cleared occurrences and advances recurring bills without consuming one transaction twice. */
fun reconcileBillPaymentState(bill: Bill, transactions: List<FinanceTransaction>): Bill {
    var current = bill
    val usedTransactionIds = mutableSetOf<String>()
    repeat(24) {
        val due = runCatching { current.dueDate() }.getOrNull() ?: return current
        val dueKey = due.toString()
        val alreadyPaid = dueKey in current.paidDates
        val match = if (alreadyPaid) null else findBillPaymentMatch(
            current,
            transactions.filterNot { it.id in usedTransactionIds }
        )
        if (!alreadyPaid && match?.highConfidence != true) return current

        if (match != null) usedTransactionIds += match.transactionId
        val paidDates = (current.paidDates + dueKey).distinct()
        if (current.frequency == Frequency.ONE_TIME) {
            return current.copy(paidDates = paidDates)
        }
        current = current.copy(
            dueDateIso = advanceBillOccurrence(due, current.frequency).toString(),
            paidDates = paidDates
        )
    }
    return current
}

private fun advanceBillOccurrence(date: LocalDate, frequency: Frequency): LocalDate = when (frequency) {
    Frequency.ONE_TIME -> date
    Frequency.WEEKLY -> date.plusWeeks(1)
    Frequency.BIWEEKLY -> date.plusWeeks(2)
    Frequency.MONTHLY -> date.plusMonths(1)
    Frequency.YEARLY -> date.plusYears(1)
}

/**
 * Date-aware dashboard summary. Only unresolved obligations due by the end of the current month are
 * reserved from today's already-current account balances. A high-confidence cleared transaction
 * prevents the same bill from being subtracted twice.
 */
fun calculateMoneySummary(data: AppData, referenceDate: LocalDate): MoneySummary {
    val total = data.accounts
        .filter { it.role != AccountRole.CREDIT && it.type != AccountType.CREDIT }
        .sumOf { it.balance }
    val retirement = data.accounts
        .filter { it.type == AccountType.INVESTMENT }
        .sumOf { it.balance }
    val savings = data.accounts
        .filter { it.role == AccountRole.SAVINGS && it.type != AccountType.INVESTMENT }
        .sumOf { it.balance }
    val spendable = data.accounts
        .filter {
            it.includeInSpendable &&
                it.role != AccountRole.SAVINGS &&
                it.role != AccountRole.CREDIT &&
                it.type != AccountType.CREDIT
        }
        .sumOf { it.balance }
    val reserved = data.reservedFunds.sumOf { it.amount.coerceAtLeast(0.0) }
    val reservedFromSpending = data.reservedFunds.filter { fund ->
        val linked = fund.accountId?.let { id -> data.accounts.firstOrNull { it.id == id } }
        linked == null || (
            linked.includeInSpendable &&
                linked.role != AccountRole.SAVINGS &&
                linked.role != AccountRole.CREDIT &&
                linked.type != AccountType.CREDIT
            )
    }.sumOf { it.amount.coerceAtLeast(0.0) }

    val monthEnd = YearMonth.from(referenceDate).atEndOfMonth()
    val unresolved = data.bills.filter { bill ->
        val due = runCatching { bill.dueDate() }.getOrNull() ?: return@filter false
        if (due.isAfter(monthEnd) || bill.isPaidFor()) return@filter false
        findBillPaymentMatch(bill, data.transactions)?.highConfidence != true
    }
    val upcoming = unresolved.sumOf { it.amount.coerceAtLeast(0.0) }

    return MoneySummary(
        totalMoney = total,
        spendingMoney = spendable,
        savings = savings,
        retirement = retirement,
        reserved = reserved,
        reservedFromSpending = reservedFromSpending,
        upcomingBills = upcoming,
        availableAfterUpcomingBills = spendable - reservedFromSpending - upcoming
    )
}
