package com.baylee.billnest

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.*

/** Asset-only account editor. Credit cards are intentionally created and edited under Debt. */
@Composable
fun AssetAccountDialog(original: Account?, onDismiss: () -> Unit, onSave: (Account) -> Unit) {
    val plaid = original?.source == AccountSource.PLAID
    var name by remember(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var balance by remember(original?.id) { mutableStateOf(original?.balance?.toString().orEmpty()) }
    var type by remember(original?.id) {
        mutableStateOf(original?.type?.takeUnless { it == AccountType.CREDIT } ?: AccountType.CHECKING)
    }
    var role by remember(original?.id) {
        mutableStateOf(original?.role?.takeUnless { it == AccountRole.CREDIT } ?: AccountRole.OTHER)
    }
    var includeInSpendable by remember(original?.id) { mutableStateOf(original?.includeInSpendable ?: true) }
    var typeExpanded by remember { mutableStateOf(false) }
    var roleExpanded by remember { mutableStateOf(false) }
    val assetTypes = AccountType.entries.filterNot { it == AccountType.CREDIT }
    val assetRoles = AccountRole.entries.filterNot { it == AccountRole.CREDIT }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "Add account" else "Edit account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Account name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = balance,
                    onValueChange = { balance = it },
                    label = { Text("Current balance") },
                    enabled = !plaid,
                    modifier = Modifier.fillMaxWidth()
                )
                Box {
                    OutlinedButton(onClick = { if (!plaid) typeExpanded = true }, enabled = !plaid) {
                        Text("Type: ${type.name.lowercase().replaceFirstChar { it.uppercase() }}")
                    }
                    DropdownMenu(typeExpanded, { typeExpanded = false }) {
                        assetTypes.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(value.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    type = value
                                    if (value == AccountType.SAVINGS || value == AccountType.INVESTMENT) {
                                        role = AccountRole.SAVINGS
                                        includeInSpendable = false
                                    }
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { roleExpanded = true }) {
                        Text("Role: ${role.name.lowercase().replaceFirstChar { it.uppercase() }}")
                    }
                    DropdownMenu(roleExpanded, { roleExpanded = false }) {
                        assetRoles.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(value.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    role = value
                                    if (value == AccountRole.SAVINGS) includeInSpendable = false
                                    roleExpanded = false
                                }
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Include in Spending Money")
                        Text("Savings and retirement can stay visible without being spendable.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = includeInSpendable,
                        onCheckedChange = { includeInSpendable = it },
                        enabled = role != AccountRole.SAVINGS
                    )
                }
                Text("Credit cards are added and managed from Debt, where APR, minimum payment, due date, and credit limit are tracked.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (plaid) Text("Bank balance and account type come from Plaid. Name, role, and spending behavior are controlled by you.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedBalance = if (plaid) original?.balance else balance.toDoubleOrNull()
                if (parsedBalance != null) {
                    onSave(
                        (original ?: Account(name = "", type = type, balance = parsedBalance)).copy(
                            name = name.ifBlank { "Account" },
                            type = if (plaid) original?.type?.takeUnless { it == AccountType.CREDIT } ?: type else type,
                            balance = parsedBalance,
                            role = role,
                            includeInSpendable = includeInSpendable && role != AccountRole.SAVINGS
                        )
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
