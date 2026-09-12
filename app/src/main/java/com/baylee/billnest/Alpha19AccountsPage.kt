package com.baylee.billnest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baylee.billnest.data.BankConnectionIssue
import com.baylee.billnest.data.canRefreshBanks
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel
import com.baylee.billnest.ui.theme.BillNestColors
import java.text.NumberFormat

private val accountMoney = NumberFormat.getCurrencyInstance()

@Composable
fun AccountsPageV3(
    data: AppData,
    vm: MainViewModel,
    bankIssues: List<BankConnectionIssue>,
    modifier: Modifier = Modifier,
    onEdit: (Account) -> Unit,
    onConnectBank: () -> Unit,
    onReconnectBank: (String) -> Unit,
    onRefreshBanks: () -> Unit
) {
    val visible = visibleAssetAccounts(data.accounts)
        .sortedWith(compareBy<Account> { it.displayOrder }.thenBy { it.name })
    val money = calculateMoneySummary(data)

    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Accounts", style = MaterialTheme.typography.headlineSmall)
                Text("Checking, savings, cash, and retirement live here. Credit cards are managed under Debt.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BillNestColors.card),
                border = BorderStroke(1.dp, BillNestColors.border)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Total assets", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(accountMoney.format(money.totalMoney), style = MaterialTheme.typography.headlineMedium)
                    Text("Spending ${accountMoney.format(money.spendingMoney)} • Savings ${accountMoney.format(money.savings)}")
                    Text("Retirement ${accountMoney.format(money.retirement)} • Reserved ${accountMoney.format(money.reserved)}")
                    Text("${visible.size} visible asset account${if (visible.size == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnectBank) { Text("Connect bank") }
                OutlinedButton(onClick = onRefreshBanks, enabled = canRefreshBanks(data.backendUrl)) { Text("Refresh") }
            }
        }
        items(bankIssues, key = { "bank-issue-${it.itemId}" }) { issue ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = BillNestColors.card)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(issue.label?.takeIf { it.isNotBlank() } ?: "Bank connection", style = MaterialTheme.typography.titleMedium)
                    Text(if (issue.requiresReconnect) "Connection needs to be repaired" else "Bank connection needs attention", color = BillNestColors.danger)
                    Text(
                        if (issue.requiresReconnect) "Your bank is asking you to sign in again before BillNest can refresh this account."
                        else issue.message.ifBlank { "BillNest could not refresh this bank right now." },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (issue.requiresReconnect) Button({ onReconnectBank(issue.itemId) }) { Text("Reconnect bank") }
                }
            }
        }
        if (visible.isEmpty() && bankIssues.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = BillNestColors.card)) {
                    Text("No asset accounts yet. Add checking/savings manually or connect a bank. Add credit cards from Debt.", Modifier.padding(16.dp))
                }
            }
        }
        items(visible, key = { it.id }) { account ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BillNestColors.card),
                border = BorderStroke(1.dp, BillNestColors.border)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(account.name, style = MaterialTheme.typography.titleMedium)
                            Text(account.type.name.lowercase().replaceFirstChar { it.uppercase() } + if (account.mask.isNotBlank()) " ••••${account.mask}" else "")
                            Text(if (account.source == AccountSource.PLAID) "Connected with Plaid" else "Manual account", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text("${account.role.name.lowercase().replaceFirstChar { it.uppercase() }} • ${if (account.includeInSpendable) "Included in spending" else "Excluded from spending"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(accountMoney.format(account.balance), style = MaterialTheme.typography.titleLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({ vm.moveAccount(account.id, -1) }) { Text("↑") }
                        TextButton({ vm.moveAccount(account.id, 1) }) { Text("↓") }
                        TextButton({ onEdit(account) }) { Text("Edit") }
                        if (account.source == AccountSource.MANUAL) {
                            TextButton({ vm.deleteAccount(account.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}
