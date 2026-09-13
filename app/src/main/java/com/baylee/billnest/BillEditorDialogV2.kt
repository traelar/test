package com.baylee.billnest

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.*
import java.time.LocalDate

/** Bill editor with household-derived categories and a bounded, scrollable category menu. */
@Composable
fun BillEditorDialogV2(
    data: AppData,
    original: Bill?,
    onDismiss: () -> Unit,
    onSave: (Bill) -> Unit
) {
    var name by remember(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var amount by remember(original?.id) { mutableStateOf(original?.amount?.toString().orEmpty()) }
    var date by remember(original?.id) {
        mutableStateOf(original?.dueDateIso ?: LocalDate.now().plusDays(7).toString())
    }
    var autopay by remember(original?.id) { mutableStateOf(original?.autopay ?: false) }
    var variable by remember(original?.id) { mutableStateOf(original?.variableAmount ?: false) }
    var frequency by remember(original?.id) { mutableStateOf(original?.frequency ?: Frequency.MONTHLY) }
    var category by remember(original?.id) { mutableStateOf(original?.category ?: "Other") }
    var accountId by remember(original?.id) { mutableStateOf(original?.accountId) }
    var notes by remember(original?.id) { mutableStateOf(original?.notes.orEmpty()) }
    var freqExpanded by remember { mutableStateOf(false) }
    var accountExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "Add bill" else "Edit bill") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Bill name") },
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
                DatePickerButton(label = "Due date", dateIso = date, onDateSelected = { date = it })

                CategoryPickerField(
                    data = data,
                    value = category,
                    onValueChange = { category = it },
                    label = "Category"
                )

                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { accountExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Paid from: ${data.accounts.firstOrNull { it.id == accountId }?.name ?: "Unassigned"}")
                    }
                    DropdownMenu(
                        expanded = accountExpanded,
                        onDismissRequest = { accountExpanded = false },
                        modifier = Modifier.heightIn(max = 320.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Unassigned") },
                            onClick = {
                                accountId = null
                                accountExpanded = false
                            }
                        )
                        data.accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = {
                                    accountId = account.id
                                    accountExpanded = false
                                }
                            )
                        }
                    }
                }

                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { freqExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Repeats: ${frequency.name.replace('_', ' ')}")
                    }
                    DropdownMenu(
                        expanded = freqExpanded,
                        onDismissRequest = { freqExpanded = false }
                    ) {
                        Frequency.entries.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.name.replace('_', ' ')) },
                                onClick = {
                                    frequency = choice
                                    freqExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth()) {
                    Checkbox(checked = autopay, onCheckedChange = { autopay = it })
                    Text("Autopay", modifier = Modifier.padding(top = 12.dp))
                }
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(checked = variable, onCheckedChange = { variable = it })
                    Text("Amount changes month to month", modifier = Modifier.padding(top = 12.dp))
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedAmount = amount.toDoubleOrNull()
                val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
                if (parsedAmount != null && parsedDate != null) {
                    onSave(
                        (original ?: Bill(name = "", amount = 0.0, dueDateIso = parsedDate.toString())).copy(
                            name = name.ifBlank { "Bill" },
                            amount = parsedAmount.coerceAtLeast(0.0),
                            dueDateIso = parsedDate.toString(),
                            frequency = frequency,
                            autopay = autopay,
                            category = canonicalCategoryName(data, category),
                            notes = notes,
                            variableAmount = variable,
                            accountId = accountId
                        )
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
