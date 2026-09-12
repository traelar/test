package com.baylee.billnest

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.*
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs

@Composable
fun TransactionEditorDialog(
    data: AppData,
    existing: FinanceTransaction?,
    onDismiss: () -> Unit,
    onSave: (FinanceTransaction) -> Unit
) {
    val isPlaid = existing?.source == TransactionSource.PLAID
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var amount by remember(existing?.id) { mutableStateOf(existing?.amount?.let { abs(it).toString() }.orEmpty()) }
    var dateIso by remember(existing?.id) { mutableStateOf(existing?.dateIso ?: LocalDate.now().toString()) }
    var category by remember(existing?.id) {
        mutableStateOf(existing?.category?.takeUnless { it.equals("Income", true) || it.equals("Transfer", true) } ?: "Other")
    }
    var classification by remember(existing?.id) {
        mutableStateOf(
            when {
                existing?.transfer == true -> TransactionClassification.TRANSFER
                existing?.income == true -> TransactionClassification.INCOME
                else -> TransactionClassification.SPENDING
            }
        )
    }
    var fromAccountId by remember(existing?.id) {
        mutableStateOf(
            existing?.transferFromAccountId
                ?: existing?.takeIf { it.amount > 0.0 }?.accountId
                ?: ""
        )
    }
    var toAccountId by remember(existing?.id) {
        mutableStateOf(
            existing?.transferToAccountId
                ?: existing?.takeIf { it.amount < 0.0 }?.accountId
                ?: ""
        )
    }
    var typeExpanded by remember { mutableStateOf(false) }
    var fromExpanded by remember { mutableStateOf(false) }
    var toExpanded by remember { mutableStateOf(false) }

    val parsedAmount = if (isPlaid) existing?.amount else amount.toDoubleOrNull()?.coerceAtLeast(0.0)
    val validDate = runCatching { LocalDate.parse(dateIso) }.isSuccess
    val transferValid = classification != TransactionClassification.TRANSFER ||
        (fromAccountId.isNotBlank() && toAccountId.isNotBlank() && fromAccountId != toAccountId)
    val canSave = existing != null && isPlaid ||
        (name.isNotBlank() && parsedAmount != null && validDate && transferValid)
    val plaidCanSave = existing != null && isPlaid && transferValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add transaction" else "Edit transaction") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isPlaid && existing != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(existing.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${java.text.NumberFormat.getCurrencyInstance().format(abs(existing.amount))} • ${existing.dateIso}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Bank-synced details stay unchanged. Your BillNest classification is saved separately.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Amount") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = dateIso,
                        onValueChange = { dateIso = it },
                        label = { Text("Date (YYYY-MM-DD)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = dateIso.isNotBlank() && !validDate
                    )
                }

                Text("Classification", style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { typeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when (classification) {
                                TransactionClassification.SPENDING -> "Spending"
                                TransactionClassification.INCOME -> "Income"
                                TransactionClassification.TRANSFER -> "Transfer between accounts"
                            }
                        )
                    }
                    DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        TransactionClassification.entries.forEach { choice ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (choice) {
                                            TransactionClassification.SPENDING -> "Spending"
                                            TransactionClassification.INCOME -> "Income"
                                            TransactionClassification.TRANSFER -> "Transfer"
                                        }
                                    )
                                },
                                onClick = {
                                    classification = choice
                                    typeExpanded = false
                                }
                            )
                        }
                    }
                }

                when (classification) {
                    TransactionClassification.SPENDING -> {
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it },
                            label = { Text("Spending category") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    TransactionClassification.INCOME -> {
                        Text(
                            "This transaction counts as income and will no longer be treated as spending.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    TransactionClassification.TRANSFER -> {
                        Text(
                            "Transfers are excluded from income, spending, and budget totals.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        AccountPicker(
                            label = "From account",
                            selectedId = fromAccountId,
                            accounts = data.accounts,
                            expanded = fromExpanded,
                            onExpandedChange = { fromExpanded = it },
                            onSelected = { fromAccountId = it }
                        )
                        AccountPicker(
                            label = "To account",
                            selectedId = toAccountId,
                            accounts = data.accounts,
                            expanded = toExpanded,
                            onExpandedChange = { toExpanded = it },
                            onSelected = { toAccountId = it }
                        )
                        if (fromAccountId.isNotBlank() && fromAccountId == toAccountId) {
                            Text(
                                "From and To accounts must be different.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = if (isPlaid) plaidCanSave else canSave,
                onClick = {
                    val base = if (existing != null) {
                        if (isPlaid) existing else existing.copy(
                            name = name.ifBlank { existing.name },
                            amount = parsedAmount ?: existing.amount,
                            dateIso = dateIso
                        )
                    } else {
                        FinanceTransaction(
                            id = UUID.randomUUID().toString(),
                            name = name.ifBlank { "Transaction" },
                            amount = parsedAmount ?: 0.0,
                            dateIso = dateIso,
                            category = category.ifBlank { "Other" },
                            accountId = if (classification == TransactionClassification.TRANSFER) fromAccountId.ifBlank { null } else null,
                            source = TransactionSource.MANUAL
                        )
                    }
                    onSave(
                        reclassifyTransaction(
                            transaction = base,
                            classification = classification,
                            fromAccountId = fromAccountId.ifBlank { null },
                            toAccountId = toAccountId.ifBlank { null },
                            spendingCategory = category.ifBlank { "Other" }
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AccountPicker(
    label: String,
    selectedId: String,
    accounts: List<Account>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit
) {
    val selected = accounts.firstOrNull { it.id == selectedId }
    Box {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label)
                Text(selected?.name ?: "Choose account", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(account.name)
                            if (account.mask.isNotBlank()) {
                                Text("••••${account.mask}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = {
                        onSelected(account.id)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}
