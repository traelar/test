package com.baylee.billnest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.AppData
import com.baylee.billnest.model.FinanceTransaction
import com.baylee.billnest.model.SubscriptionPreference
import com.baylee.billnest.model.SubscriptionStatus
import com.baylee.billnest.model.TransactionSource
import com.baylee.billnest.model.effectiveDisplayName
import com.baylee.billnest.model.subscriptionKey
import com.baylee.billnest.ui.MainViewModel
import com.baylee.billnest.ui.theme.BillNestColors
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private enum class TransactionV4Action { EDIT, SUBSCRIPTION, RULE, DELETE }

private fun transactionV4ActionRows(includeSubscription: Boolean): List<List<TransactionV4Action>> =
    if (includeSubscription) {
        listOf(
            listOf(TransactionV4Action.EDIT, TransactionV4Action.SUBSCRIPTION),
            listOf(TransactionV4Action.RULE, TransactionV4Action.DELETE)
        )
    } else {
        listOf(
            listOf(TransactionV4Action.EDIT, TransactionV4Action.RULE),
            listOf(TransactionV4Action.DELETE)
        )
    }

private val transactionV4Money = NumberFormat.getCurrencyInstance()
private fun transactionV4Money(value: Double): String = transactionV4Money.format(value)
private fun transactionV4Date(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
}.getOrDefault(value)

@Composable
private fun TransactionV4Card(content: @Composable Column.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BillNestColors.card),
        border = BorderStroke(1.dp, BillNestColors.border)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
fun TransactionsPageV4(
    data: AppData,
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onOpenReviewInbox: () -> Unit,
    onOpenMerchantsAndRules: () -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FinanceTransaction?>(null) }
    var smartRuleFor by remember { mutableStateOf<FinanceTransaction?>(null) }
    var newSmartRule by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("All") }

    val rows = remember(data.transactions, query, typeFilter) {
        data.transactions.filter { row ->
            val inferredIncome = row.income || row.category.equals("Income", true) ||
                (row.source == TransactionSource.PLAID && row.amount < 0.0 && !row.userClassificationOverride)
            val displayName = row.effectiveDisplayName()
            val queryMatch = query.isBlank() || displayName.contains(query, true) || row.name.contains(query, true) || row.category.contains(query, true)
            queryMatch && when (typeFilter) {
                "Income" -> !row.pending && inferredIncome && !row.transfer
                "Transfers" -> !row.pending && row.transfer
                "Spending" -> !row.pending && !inferredIncome && !row.transfer
                "Pending" -> row.pending
                "Excluded" -> row.excludedFromSpending
                else -> true
            }
        }.sortedByDescending { it.dateIso }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("Transactions", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Clean merchant names, review uncertain activity, or automate future matches without changing Plaid's raw bank data.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { adding = true }) { Text("+ Add") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenReviewInbox, modifier = Modifier.weight(1f)) { Text("Review") }
                    OutlinedButton(onClick = onOpenMerchantsAndRules, modifier = Modifier.weight(1f)) { Text("Merchants & Rules") }
                }
            }
        }

        item {
            TransactionV4Card {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Smart automation", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${data.smartTransactionRules.size} smart rule${if (data.smartTransactionRules.size == 1) "" else "s"} • ${data.merchantProfiles.size} merchant profile${if (data.merchantProfiles.size == 1) "" else "s"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { newSmartRule = true }) { Text("+ Rule") }
                }
                data.smartTransactionRules.sortedWith(compareByDescending<com.baylee.billnest.model.SmartTransactionRule> { it.priority }.thenBy { it.name }).take(3).forEach { rule ->
                    HorizontalDivider(color = BillNestColors.border.copy(alpha = .5f))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(rule.name.ifBlank { "Smart rule" }, style = MaterialTheme.typography.bodyMedium)
                        val summary = buildList {
                            rule.match.rawNameContains?.let { add("name contains $it") }
                            rule.action.displayName?.let { add("rename → $it") }
                            rule.action.category?.let { add("category → $it") }
                            rule.action.classification?.let { add(it.name.lowercase()) }
                        }.joinToString(" • ")
                        if (summary.isNotBlank()) Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (data.transactionRules.isNotEmpty()) {
                    Text(
                        "${data.transactionRules.size} legacy rule${if (data.transactionRules.size == 1) "" else "s"} can be converted from Merchants & Rules.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search transactions") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("All", "Spending", "Income", "Transfers").forEach { choice ->
                    TextButton(onClick = { typeFilter = choice }, modifier = Modifier.weight(1f)) {
                        Text(choice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Pending", "Excluded").forEach { choice ->
                    OutlinedButton(onClick = { typeFilter = choice }, modifier = Modifier.weight(1f)) {
                        Text(choice, maxLines = 1)
                    }
                }
            }
            Text("Showing: $typeFilter", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }

        if (rows.isEmpty()) {
            item { TransactionV4Card { Text("No transactions match this view.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }

        items(rows, key = { it.id }) { row ->
            val inferredIncome = row.income || row.category.equals("Income", true) ||
                (row.source == TransactionSource.PLAID && row.amount < 0.0 && !row.userClassificationOverride)
            val displayName = row.effectiveDisplayName()
            val from = row.transferFromAccountId?.let { id -> data.accounts.firstOrNull { it.id == id }?.name }
            val to = row.transferToAccountId?.let { id -> data.accounts.firstOrNull { it.id == id }?.name }
            val kind = when {
                row.transfer -> if (from != null || to != null) "Transfer • ${from ?: "Unknown"} → ${to ?: "Unknown"}" else "Transfer"
                inferredIncome -> "Income"
                else -> row.category
            }
            val merchantKey = subscriptionKey(displayName)
            val trackedSubscription = data.subscriptionPreferences.any {
                it.merchantKey == merchantKey && it.status == SubscriptionStatus.CONFIRMED
            }

            TransactionV4Card {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(displayName, style = MaterialTheme.typography.titleMedium)
                        if (displayName != row.name) {
                            Text("Bank: ${row.name}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("$kind • ${transactionV4Date(row.dateIso)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val tags = buildList {
                            if (row.pending) add("Pending")
                            if (row.splits.orEmpty().isNotEmpty()) add("Split")
                            if (row.excludedFromSpending) add("Excluded")
                            if (row.userClassificationOverride) add("Manual")
                            if (row.source == TransactionSource.PLAID) add("Bank")
                            if (row.merchantProfileId != null) add("Merchant")
                            if (row.appliedSmartRuleId != null) add("Automated")
                        }
                        if (tags.isNotEmpty()) Text(tags.joinToString(" • "), color = BillNestColors.info, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        if (inferredIncome && !row.transfer) "+${transactionV4Money(abs(row.amount))}" else transactionV4Money(abs(row.amount)),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (inferredIncome && !row.transfer) BillNestColors.positive else if (row.transfer) BillNestColors.info else MaterialTheme.colorScheme.onSurface
                    )
                }

                transactionV4ActionRows(!row.income && !row.transfer).forEach { actionRow ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        actionRow.forEach { action ->
                            TextButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    when (action) {
                                        TransactionV4Action.EDIT -> editing = row
                                        TransactionV4Action.SUBSCRIPTION -> {
                                            if (trackedSubscription) {
                                                vm.deleteSubscriptionPreference(merchantKey)
                                            } else {
                                                vm.saveSubscriptionPreference(
                                                    SubscriptionPreference(merchantKey, displayName, SubscriptionStatus.CONFIRMED)
                                                )
                                            }
                                        }
                                        TransactionV4Action.RULE -> smartRuleFor = row
                                        TransactionV4Action.DELETE -> vm.deleteTransaction(row.id)
                                    }
                                }
                            ) {
                                Text(
                                    when (action) {
                                        TransactionV4Action.EDIT -> "Edit"
                                        TransactionV4Action.SUBSCRIPTION -> if (trackedSubscription) "Untrack sub" else "Track sub"
                                        TransactionV4Action.RULE -> "Make rule"
                                        TransactionV4Action.DELETE -> "Delete"
                                    },
                                    color = if (action == TransactionV4Action.DELETE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        TransactionEditorDialog(
            data = data,
            existing = editing,
            onDismiss = { adding = false; editing = null },
            onSave = {
                vm.saveTransaction(it)
                adding = false
                editing = null
            }
        )
    }

    if (newSmartRule || smartRuleFor != null) {
        SmartRuleEditorDialog(
            data = data,
            sourceTransaction = smartRuleFor,
            onDismiss = { newSmartRule = false; smartRuleFor = null },
            onSave = {
                vm.saveSmartTransactionRule(it)
                newSmartRule = false
                smartRuleFor = null
            }
        )
    }
}
