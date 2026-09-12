package com.baylee.billnest.data

import com.baylee.billnest.model.*
import java.time.LocalDate

class FinanceAutomationCoordinator(private val repo: BillRepository) {
    fun processNewTransactions() {
        val data = repo.data.value
        val alreadyConsidered = data.billMatches.map { it.transactionId }.toSet()
        val bills = data.bills.filter { !it.isPaidFor() }
        val transactions = (data.plaidTransactions + data.manualTransactions)
            .filterNot { it.id in alreadyConsidered }
            .sortedBy { it.dateIso }

        transactions.forEach { transaction ->
            val proposal = BillMatcher.bestProposal(transaction, bills) ?: return@forEach
            val match = BillTransactionMatch(
                id = "${proposal.billId}:${proposal.transactionId}",
                billId = proposal.billId,
                transactionId = proposal.transactionId,
                matchedDueDateIso = proposal.matchedDueDateIso,
                score = proposal.score,
                status = proposal.status,
                reason = proposal.reason
            )
            if (proposal.status == MatchStatus.AUTO_MATCHED) repo.markPaid(proposal.billId)
            repo.upsertBillMatch(match)
        }
    }

    fun approve(matchId: String) {
        val match = repo.data.value.billMatches.firstOrNull { it.id == matchId } ?: return
        if (match.status != MatchStatus.NEEDS_REVIEW) return
        repo.markPaid(match.billId)
        repo.upsertBillMatch(match.copy(status = MatchStatus.CONFIRMED))
    }

    fun reject(matchId: String) {
        val match = repo.data.value.billMatches.firstOrNull { it.id == matchId } ?: return
        repo.upsertBillMatch(match.copy(status = MatchStatus.REJECTED))
    }

    fun undo(matchId: String) {
        val data = repo.data.value
        val match = data.billMatches.firstOrNull { it.id == matchId } ?: return
        val bill = data.bills.firstOrNull { it.id == match.billId } ?: return
        val matchedDate = match.matchedDueDateIso.takeIf { it.isNotBlank() } ?: return
        val matchedLocalDate = runCatching { LocalDate.parse(matchedDate) }.getOrNull() ?: return
        val remainingPaid = bill.paidDates.filterNot { it == matchedDate }
        val laterPaidExists = remainingPaid.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.any { it.isAfter(matchedLocalDate) }
        val restoredDue = if (
            bill.frequency != Frequency.ONE_TIME &&
            bill.dueDate().isAfter(matchedLocalDate) &&
            !laterPaidExists
        ) matchedDate else bill.dueDateIso
        repo.updateBill(bill.copy(paidDates = remainingPaid, dueDateIso = restoredDue))
        repo.upsertBillMatch(match.copy(status = MatchStatus.REJECTED))
    }
}
