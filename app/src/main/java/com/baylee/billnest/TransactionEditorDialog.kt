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
import com.baylee.billnest.ui.theme.BillNestColors
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
    var splitMode by remember(existing?.id) { mutableStateOf(existing?.splits.orEmpty().isNotEmpty()) }
    var splits by remember(existing?.id) { mutableStateOf(existing?.splits.orEmpty()) }

    val parsedAmount = if (isPlaid) existing?.amount else amount.toDoubleOrNull()?.coerceAtLeast(0.0)
    val validDate = runCatching { LocalDate.parse(dateIso) }.isSuccess
    val transferValid = classification != TransactionClassification.TRANSFER ||
        (fromAccountId.isNotBlank() && toAccountId.isNotBlank() && fromAccountId != toAccountId)
    val expectedSplitTotal = parsedAmount?.let { abs(it) } ?: 0.0
    val splitValid = !splitMode || (
        splits.size >= 2 &&
            splits.all { it.category.isNotBlank() && it.amount > 0.0 } &&
            abs(splits.sumOf { it.amount } - expectedSplitTotal) <= 0.01
        )
    val canSave = (existing != null && isPlaid || (name.isNotBlank() && parsedAmount != null && validDate && transferValid)) && splitValid
    val plaidCanSave = existing != null && isPlaid && transferValid && splitValid

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
                        CategoryPickerField(
                            data = data,
                            value = category,
                            onValueChange = { category = it },
                            label = "Category for this transaction",
                            extraOptions = splits.map { it.category }
                        )
                        Text(
                            "This changes only this purchase. Use a transaction rule only when you want matching merchant charges changed automatically.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Split this transaction")
                                Text("Use different categories for parts of the same purchase.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = splitMode,
                                onCheckedChange = { enabled ->
                                    splitMode = enabled
                                    if (enabled && splits.size < 2) {
                                        val half = expectedSplitTotal / 2.0
                                        splits = listOf(
                                            TransactionSplit(category = canonicalCategoryName(data, category.ifBlank { "Other" }), amount = half),
                                            TransactionSplit(category = "Other", amount = expectedSplitTotal - half)
                                        )
                                    }
                                }
                            )
                        }
                        if (splitMode) {
                            splits.forEachIndexed { index, split ->
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        CategoryPickerField(
                                            data = data,
                                            value = split.category,
                                            onValueChange = { value ->
                                                splits = splits.toMutableList().also { rows -> rows[index] = split.copy(category = value) }
                                            },
                                            label = "Split category",
                                            extraOptions = listOf(category) + splits.map { it.category }
                                        )
                                        OutlinedTextField(
                                            value = if (split.amount == 0.0) "" else split.amount.toString(),
                                            onValueChange = { value ->
                                                val filtered = value.filter { ch -> ch.isDigit() || ch == '.' }
                                                splits = splits.toMutableList().also { rows -> rows[index] = split.copy(amount = filtered.toDoubleOrNull() ?: 0.0) }
                                            },
                                            label = { Text("Split amount") },
                                            prefix = { Text("$") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        if (splits.size > 2) {
                                            TextButton(onClick = { splits = splits.filterIndexed { rowIndex, _ -> rowIndex != index } }) {
                                                Text("Remove split", color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = { splits = splits + TransactionSplit(category = "Other", amount = 0.0) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("+ Add split") }
                            Text(
                                "Split total ${java.text.NumberFormat.getCurrencyInstance().format(splits.sumOf { it.amount })} of ${java.text.NumberFormat.getCurrencyInstance().format(expectedSplitTotal)}",
                                color = if (splitValid) BillNestColors.positive else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
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
                    val canonicalCategory = canonicalCategoryName(data, category.ifBlank { "Other" })
                    val canonicalSplits = splits.map { split ->
                        split.copy(category = canonicalCategoryName(data, split.category))
                    }
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
                            category = canonicalCategory,
                            accountId = if (classification == TransactionClassification.TRANSFER) fromAccountId.ifBlank { null } else null,
                            source = TransactionSource.MANUAL
                        )
                    }
                    val classified = reclassifyTransaction(
                        transaction = base,
                        classification = classification,
                        fromAccountId = fromAccountId.ifBlank { null },
                        toAccountId = toAccountId.ifBlank { null },
                        spendingCategory = canonicalCategory
                    )
                    onSave(
                        classified.copy(
                            splits = if (classification == TransactionClassification.SPENDING && splitMode) canonicalSplits else null
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
