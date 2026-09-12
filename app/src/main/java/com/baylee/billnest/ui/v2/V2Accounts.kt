package com.baylee.billnest.ui.v2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baylee.billnest.data.BankConnectionIssue
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel

@Composable
internal fun V2AccountsScreen(
    data: AppData,
    vm: MainViewModel,
    bankIssues: List<BankConnectionIssue>,
    onConnectBank: () -> Unit,
    onReconnectBank: (String) -> Unit,
    modifier: Modifier,
    onEditManual: (Account) -> Unit
) {
    val ordered = AccountFinance.sortAccounts(data.accounts, data.accountPreferences)
    val cash = CashPosition.calculate(data.accounts, data.accountPreferences, data.reservedFunds)
    var editingPreference by remember { mutableStateOf<Account?>(null) }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyMiniCard("Total Money", cash.totalMoney, Modifier.weight(1f))
                MoneyMiniCard("Spending Money", cash.spendingMoney, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyMiniCard("Reserved", cash.reservedMoney, Modifier.weight(1f))
                MoneyMiniCard("Free Savings", cash.freeSavings, Modifier.weight(1f))
            }
        }
        item {
            Button(onClick = onConnectBank) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Connect bank")
            }
        }
        items(bankIssues, key = { "issue-${it.itemId}" }) { issue ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(issue.label ?: "Bank connection", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (issue.requiresReconnect) "Sign in to this bank again to resume syncing." else issue.message,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (issue.requiresReconnect) Button(onClick = { onReconnectBank(issue.itemId) }) { Text("Reconnect bank") }
                }
            }
        }
        if (ordered.isEmpty()) item { Text("No accounts yet. Connect a bank or use + Account for a manual account.") }
        items(ordered, key = { AccountFinance.stableKey(it) }) { account ->
            val sourceIndex = data.accounts.indexOfFirst { AccountFinance.stableKey(it) == AccountFinance.stableKey(account) }.coerceAtLeast(0)
            val pref = AccountFinance.preferenceFor(account, data.accountPreferences, sourceIndex)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(AccountFinance.displayName(account, data.accountPreferences, sourceIndex), style = MaterialTheme.typography.titleMedium)
                            Text(accountRoleLabel(pref.role) + if (account.mask.isNotBlank()) " ••••${account.mask}" else "")
                            Text(if (account.source == AccountSource.PLAID) "Bank connected" else "Manual account", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(money(account.balance), style = MaterialTheme.typography.titleLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = {}, label = { Text(if (pref.includeInSpending) "Spendable" else "Not spendable") })
                        AssistChip(onClick = {}, label = { Text(if (pref.includeInTotal) "In total" else "Excluded") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { vm.moveAccount(pref.accountKey, -1) }) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                        IconButton(onClick = { vm.moveAccount(pref.accountKey, 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                        TextButton(onClick = { editingPreference = account }) { Text("Money settings") }
                        if (account.source == AccountSource.MANUAL) TextButton(onClick = { onEditManual(account) }) { Text("Edit") }
                    }
                }
            }
        }
    }

    editingPreference?.let { account ->
        AccountPreferenceDialog(data, account, onDismiss = { editingPreference = null }) {
            vm.updateAccountPreference(it)
            editingPreference = null
        }
    }
}

@Composable
private fun AccountPreferenceDialog(
    data: AppData,
    account: Account,
    onDismiss: () -> Unit,
    onSave: (AccountPreference) -> Unit
) {
    val index = data.accounts.indexOfFirst { AccountFinance.stableKey(it) == AccountFinance.stableKey(account) }.coerceAtLeast(0)
    val current = AccountFinance.preferenceFor(account, data.accountPreferences, index)
    var role by remember { mutableStateOf(current.role) }
    var inTotal by remember { mutableStateOf(current.includeInTotal) }
    var spendable by remember { mutableStateOf(current.includeInSpending) }
    var name by remember { mutableStateOf(current.customName ?: account.name) }
    var roleMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account money settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedButton(onClick = { roleMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Role: ${accountRoleLabel(role)}") }
                    DropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                        AccountRole.entries.forEach { value ->
                            DropdownMenuItem(text = { Text(accountRoleLabel(value)) }, onClick = { role = value; roleMenu = false })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Include in Total Money")
                    Switch(inTotal, { inTotal = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Include in Spending Money")
                    Switch(spendable, { spendable = it })
                }
                Text("Savings can stay in Total Money without being treated as spendable cash.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(current.copy(
                    role = role,
                    includeInTotal = inTotal,
                    includeInSpending = spendable,
                    customName = name.trim().takeIf { it.isNotBlank() }
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
