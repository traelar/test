package com.baylee.billnest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel
import com.baylee.billnest.ui.theme.BillNestColors
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun IncomePageV2(
    data: AppData,
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onEditPayday: (Payday) -> Unit
) {
    var editingTransaction by remember { mutableStateOf<FinanceTransaction?>(null) }
    var deletingTransaction by remember { mutableStateOf<FinanceTransaction?>(null) }
    val incomeTransactions = recentVisibleIncomeTransactions(data.transactions)
        .sortedByDescending { it.dateIso }
    val detected = detectPaydayPatterns(incomeTransactions).filterNot { suggestion ->
        data.paydays.any { it.label.equals(suggestion.label, true) && it.frequency == suggestion.frequency }
    }

    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Paydays & income", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Showing the latest 30 days of deposits. Older transactions stay saved for history and budgeting.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Income transactions", style = MaterialTheme.typography.titleLarge)
                Surface(
                    color = BillNestColors.positive.copy(alpha = .12f),
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, BillNestColors.positive.copy(alpha = .25f))
                ) {
                    Text(
                        incomeTransactions.size.toString(),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        color = BillNestColors.positive,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        if (incomeTransactions.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "No income transactions are classified as income in the last 30 days.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(incomeTransactions, key = { "income-${it.id}" }) { row ->
            val account = row.accountId?.let { id -> data.accounts.firstOrNull { it.id == id } }
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BillNestColors.card),
                border = BorderStroke(1.dp, BillNestColors.border)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(15.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(row.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                buildString {
                                    append(incomeDate(row.dateIso))
                                    account?.let {
                                        append(" • ")
                                        append(it.name)
                                        if (it.mask.isNotBlank()) append(" ••••${it.mask}")
                                    }
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                if (row.source == TransactionSource.PLAID) "Bank transaction" else "Manual transaction",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            incomeCurrency(row.amount),
                            color = BillNestColors.positive,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { editingTransaction = row }) {
                            Text("Edit / Reclassify")
                        }
                        IconButton(onClick = { deletingTransaction = row }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Delete income transaction",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        if (detected.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Detected payday schedules", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Built from income deposits visible in the last 30 days.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            items(detected, key = { "detected-${it.label}-${it.frequency}" }) { suggestion ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(suggestion.label, style = MaterialTheme.typography.titleMedium)
                        Text("Typical check ${incomeCurrency(suggestion.typicalAmount)} • ${suggestion.frequency.name.replace('_', ' ')}")
                        Text(
                            "Next expected ${incomeDate(suggestion.nextDateIso)} • based on ${suggestion.sampleCount} deposits",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = {
                            vm.addPayday(
                                Payday(
                                    label = suggestion.label,
                                    amount = suggestion.typicalAmount,
                                    nextDateIso = suggestion.nextDateIso,
                                    frequency = suggestion.frequency
                                )
                            )
                        }) { Text("Use this schedule") }
                    }
                }
            }
        }

        item { Text("Payday schedules", style = MaterialTheme.typography.titleLarge) }
        if (data.paydays.isEmpty()) {
            item {
                Text(
                    "No payday schedules added yet. Use + Payday to add one manually.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(data.paydays.sortedBy { it.nextDate() }, key = { "schedule-${it.id}" }) { payday ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(payday.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${incomeCurrency(payday.amount)} • ${incomeDate(payday.nextDateIso)} • ${payday.frequency.name.replace('_', ' ')}"
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.receivePayday(payday.id) }) { Text("Pay received") }
                        TextButton(onClick = { onEditPayday(payday) }) { Text("Edit schedule") }
                        TextButton(onClick = { vm.deletePayday(payday.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }

    editingTransaction?.let { transaction ->
        TransactionEditorDialog(
            data = data,
            existing = transaction,
            onDismiss = { editingTransaction = null },
            onSave = { updated ->
                vm.saveTransaction(updated)
                editingTransaction = null
            }
        )
    }

    deletingTransaction?.let { transaction ->
        AlertDialog(
            onDismissRequest = { deletingTransaction = null },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete income transaction?") },
            text = {
                Text(
                    if (transaction.source == TransactionSource.PLAID) {
                        "${transaction.name} will be removed from BillNest and kept hidden when your bank transactions refresh again."
                    } else {
                        "${transaction.name} will be permanently removed from BillNest."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteTransaction(transaction.id)
                        deletingTransaction = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingTransaction = null }) { Text("Cancel") }
            }
        )
    }
}

private fun incomeCurrency(value: Double): String =
    NumberFormat.getCurrencyInstance().format(abs(value))

private fun incomeDate(dateIso: String): String =
    runCatching {
        LocalDate.parse(dateIso).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    }.getOrDefault(dateIso)
