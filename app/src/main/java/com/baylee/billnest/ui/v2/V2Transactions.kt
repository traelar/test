package com.baylee.billnest.ui.v2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baylee.billnest.data.FinanceAutomationCoordinator
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel

@Composable
internal fun TransactionsScreen(data: AppData, vm: MainViewModel, modifier: Modifier) {
    var showAdd by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var accountKey by remember { mutableStateOf<String?>(null) }
    val all = (data.plaidTransactions + data.manualTransactions).sortedByDescending { it.dateIso }
    val categories = listOf("All") + all.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    val visible = all.filter { tx ->
        val searchMatch = query.isBlank() || tx.name.contains(query, true) || tx.merchantName.orEmpty().contains(query, true) || tx.category.contains(query, true)
        val categoryMatch = category == "All" || tx.category.equals(category, true)
        val accountMatch = accountKey == null || tx.accountKey == accountKey
        searchMatch && categoryMatch && accountMatch
    }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(query, { query = it }, label = { Text("Search transactions") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterMenu("Category", category, categories, Modifier.weight(1f)) { category = it }
                AccountFilterMenu(data, accountKey, Modifier.weight(1f)) { accountKey = it }
            }
        }
        item { Button(onClick = { showAdd = true }) { Text("+ Manual transaction") } }
        if (visible.isEmpty()) item { Text("No matching transactions. Connected bank activity will appear here automatically.") }
        items(visible, key = { it.id }) { tx ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(tx.merchantName ?: tx.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(transactionAmount(tx), style = MaterialTheme.typography.titleMedium)
                    }
                    Text("${tx.dateIso} • ${tx.category}${if (tx.type == TransactionType.TRANSFER) " • Transfer" else ""}")
                    Text(if (tx.source == TransactionSource.PLAID) "Bank transaction" else "Manual transaction", style = MaterialTheme.typography.bodySmall)
                    if (tx.pending) Text("Pending", style = MaterialTheme.typography.labelMedium)
                    if (tx.source == TransactionSource.MANUAL) TextButton(onClick = { vm.deleteManualTransaction(tx.id) }) { Text("Delete") }
                }
            }
        }
    }

    if (showAdd) TransactionDialog(data, { showAdd = false }) { vm.addManualTransaction(it); showAdd = false }
}

@Composable
internal fun ReviewMatchesScreen(data: AppData, vm: MainViewModel, modifier: Modifier) {
    val automation = remember(vm.repo) { FinanceAutomationCoordinator(vm.repo) }
    val transactions = (data.plaidTransactions + data.manualTransactions).associateBy { it.id }
    val bills = data.bills.associateBy { it.id }
    val pending = data.billMatches.filter { it.status == MatchStatus.NEEDS_REVIEW }
    val history = data.billMatches.filter { it.status == MatchStatus.AUTO_MATCHED || it.status == MatchStatus.CONFIRMED }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (pending.isEmpty()) item { Text("Nothing needs review right now.") }
        items(pending, key = { it.id }) { match ->
            val bill = bills[match.billId]
            val tx = transactions[match.transactionId]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(bill?.name ?: "Bill", style = MaterialTheme.typography.titleMedium)
                    Text("Possible payment: ${tx?.merchantName ?: tx?.name ?: "Transaction"} • ${money(tx?.amount ?: 0.0)}")
                    Text("Confidence ${(match.score * 100).toInt()}% • ${match.reason}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { automation.approve(match.id) }) { Text("Confirm paid") }
                        OutlinedButton(onClick = { automation.reject(match.id) }) { Text("Not this bill") }
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            item { Text("Matched payments", style = MaterialTheme.typography.titleLarge) }
            items(history.sortedByDescending { it.matchedDueDateIso }, key = { "history-${it.id}" }) { match ->
                val bill = bills[match.billId]
                val tx = transactions[match.transactionId]
                ListItem(
                    headlineContent = { Text(bill?.name ?: "Bill payment") },
                    supportingContent = { Text("${tx?.merchantName ?: tx?.name ?: "Transaction"} • ${match.matchedDueDateIso}") },
                    trailingContent = { TextButton(onClick = { automation.undo(match.id) }) { Text("Undo") } }
                )
            }
        }
    }
}

@Composable
internal fun SubscriptionsScreen(data: AppData, vm: MainViewModel, modifier: Modifier) {
    val detected = RecurringDetector.detect(data.plaidTransactions + data.manualTransactions)
    val overrides = data.subscriptionOverrides.associateBy { it.merchantKey }
    val visible = detected.filterNot { overrides[it.merchantKey]?.ignored == true }
    val ignored = detected.filter { overrides[it.merchantKey]?.ignored == true }
    val monthly = visible.sumOf { it.monthlyEstimate }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Estimated recurring monthly cost", style = MaterialTheme.typography.labelLarge)
                    Text(money(monthly), style = MaterialTheme.typography.headlineMedium)
                    Text("Detected from repeated bank/manual charges. You can ignore things that are not subscriptions.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (visible.isEmpty()) item { Text("No recurring charges detected yet. BillNest needs a few occurrences before it can recognize a pattern.") }
        items(visible, key = { it.merchantKey }) { recurring ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(overrides[recurring.merchantKey]?.displayName ?: recurring.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("About ${money(recurring.averageAmount)} monthly • ${recurring.occurrences} observed charges")
                    Text("Last seen ${recurring.lastDateIso}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.upsertSubscriptionOverride(SubscriptionOverride(recurring.merchantKey, ignored = true)) }) { Text("Not a subscription") }
                }
            }
        }
        if (ignored.isNotEmpty()) {
            item { Text("Ignored", style = MaterialTheme.typography.titleLarge) }
            items(ignored, key = { "ignored-${it.merchantKey}" }) { recurring ->
                ListItem(
                    headlineContent = { Text(recurring.displayName) },
                    trailingContent = { TextButton(onClick = { vm.upsertSubscriptionOverride(SubscriptionOverride(recurring.merchantKey, ignored = false)) }) { Text("Restore") } }
                )
            }
        }
    }
}

@Composable
private fun TransactionDialog(data: AppData, onDismiss: () -> Unit, onSave: (FinanceTransaction) -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Other") }
    var type by remember { mutableStateOf(TransactionType.EXPENSE) }
    var accountKey by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add manual transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") })
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount") })
                SimpleEnumPicker("Type", type, TransactionType.entries) { type = it }
                OutlinedTextField(category, { category = it }, label = { Text("Category") })
                SimpleAccountPicker(data, accountKey, "Account") { accountKey = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                amount.toDoubleOrNull()?.let { parsed ->
                    onSave(FinanceTransaction(
                        name = name.ifBlank { "Transaction" },
                        amount = parsed,
                        type = type,
                        category = category.ifBlank { "Other" },
                        accountKey = accountKey,
                        excludedFromSpending = type == TransactionType.TRANSFER
                    ))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FilterMenu(label: String, selected: String, values: List<String>, modifier: Modifier = Modifier, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $selected") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { onSelected(value); expanded = false }) }
        }
    }
}

@Composable
private fun AccountFilterMenu(data: AppData, selected: String?, modifier: Modifier = Modifier, onSelected: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val accounts = AccountFinance.sortAccounts(data.accounts, data.accountPreferences)
    val selectedAccount = accounts.firstOrNull { AccountFinance.stableKey(it) == selected }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Account: ${selectedAccount?.name ?: "All"}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("All") }, onClick = { onSelected(null); expanded = false })
            accounts.forEach { account ->
                DropdownMenuItem(text = { Text(account.name) }, onClick = { onSelected(AccountFinance.stableKey(account)); expanded = false })
            }
        }
    }
}

private fun transactionAmount(tx: FinanceTransaction): String = when (tx.type) {
    TransactionType.INCOME -> "+${money(tx.amount)}"
    TransactionType.EXPENSE -> "−${money(tx.amount)}"
    TransactionType.TRANSFER -> money(tx.amount)
}
