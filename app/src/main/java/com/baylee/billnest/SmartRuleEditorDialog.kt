package com.baylee.billnest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.AppData
import com.baylee.billnest.model.FinanceTransaction
import com.baylee.billnest.model.RulePreviewRow
import com.baylee.billnest.model.SmartRuleAction
import com.baylee.billnest.model.SmartRuleMatch
import com.baylee.billnest.model.SmartTransactionRule
import com.baylee.billnest.model.SubscriptionStatus
import com.baylee.billnest.model.TransactionClassification
import com.baylee.billnest.model.TransactionDirection
import com.baylee.billnest.model.effectiveDisplayName
import com.baylee.billnest.model.transactionClassification
import java.util.UUID

@Composable
internal fun ChoiceField(
    label: String,
    valueLabel: String,
    choices: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(valueLabel)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (text, action) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            action()
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SmartRuleEditorDialog(
    data: AppData,
    existing: SmartTransactionRule? = null,
    sourceTransaction: FinanceTransaction? = null,
    onDismiss: () -> Unit,
    onSave: (SmartTransactionRule) -> Unit
) {
    val stableId = remember(existing?.id, sourceTransaction?.id) { existing?.id ?: UUID.randomUUID().toString() }
    val stableCreatedAt = remember(existing?.id, sourceTransaction?.id) { existing?.updatedAtEpochMs ?: System.currentTimeMillis() }
    var name by remember(existing?.id, sourceTransaction?.id) {
        mutableStateOf(existing?.name ?: sourceTransaction?.let { "${it.effectiveDisplayName()} rule" }.orEmpty())
    }
    var enabled by remember(existing?.id) { mutableStateOf(existing?.enabled ?: true) }
    var priority by remember(existing?.id) { mutableStateOf((existing?.priority ?: 0).toString()) }
    var rawName by remember(existing?.id, sourceTransaction?.id) {
        mutableStateOf(existing?.match?.rawNameContains ?: sourceTransaction?.name.orEmpty())
    }
    var merchantProfileId by remember(existing?.id, sourceTransaction?.id) {
        mutableStateOf(existing?.match?.merchantProfileId ?: sourceTransaction?.merchantProfileId)
    }
    var accountId by remember(existing?.id, sourceTransaction?.id) {
        mutableStateOf(existing?.match?.accountId ?: sourceTransaction?.accountId)
    }
    var minAmount by remember(existing?.id) { mutableStateOf(existing?.match?.minAmount?.toString().orEmpty()) }
    var maxAmount by remember(existing?.id) { mutableStateOf(existing?.match?.maxAmount?.toString().orEmpty()) }
    var matchCategory by remember(existing?.id) { mutableStateOf(existing?.match?.category.orEmpty()) }
    var matchClassification by remember(existing?.id) { mutableStateOf(existing?.match?.classification) }
    var direction by remember(existing?.id) { mutableStateOf(existing?.match?.direction) }

    var displayName by remember(existing?.id) { mutableStateOf(existing?.action?.displayName.orEmpty()) }
    var actionCategory by remember(existing?.id) { mutableStateOf(existing?.action?.category.orEmpty()) }
    var actionClassification by remember(existing?.id) { mutableStateOf(existing?.action?.classification) }
    var exclusion by remember(existing?.id) { mutableStateOf(existing?.action?.excludeFromSpending) }
    var recurringStatus by remember(existing?.id) { mutableStateOf(existing?.action?.recurringStatus) }

    val candidate = SmartTransactionRule(
        id = stableId,
        name = name.trim(),
        enabled = enabled,
        priority = priority.toIntOrNull() ?: 0,
        match = SmartRuleMatch(
            rawNameContains = rawName.trim().takeIf { it.isNotBlank() },
            merchantProfileId = merchantProfileId,
            accountId = accountId,
            minAmount = minAmount.toDoubleOrNull(),
            maxAmount = maxAmount.toDoubleOrNull(),
            category = matchCategory.trim().takeIf { it.isNotBlank() },
            classification = matchClassification,
            direction = direction
        ),
        action = SmartRuleAction(
            displayName = displayName.trim().takeIf { it.isNotBlank() },
            category = actionCategory.trim().takeIf { it.isNotBlank() },
            classification = actionClassification,
            excludeFromSpending = exclusion,
            recurringStatus = recurringStatus
        ),
        updatedAtEpochMs = stableCreatedAt
    )
    val previewRows = remember(data, candidate) { smartRulePreviewRows(data, candidate) }
    var previewAccepted by remember(candidate) { mutableStateOf(false) }
    val needsPreview = smartRuleNeedsPreviewConfirmation(previewRows)
    val hasCondition = with(candidate.match) {
        !rawNameContains.isNullOrBlank() || merchantProfileId != null || accountId != null ||
            minAmount != null || maxAmount != null || !category.isNullOrBlank() || classification != null || direction != null
    }
    val hasAction = with(candidate.action) {
        !displayName.isNullOrBlank() || !category.isNullOrBlank() || classification != null ||
            excludeFromSpending != null || recurringStatus != null
    }
    val validRange = candidate.match.minAmount == null || candidate.match.maxAmount == null ||
        candidate.match.minAmount <= candidate.match.maxAmount
    val canSave = candidate.name.isNotBlank() && hasCondition && hasAction && validRange && (!needsPreview || previewAccepted)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Smart transaction rule" else "Edit smart rule") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 650.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Rule name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled")
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(priority, { priority = it.filter { ch -> ch.isDigit() || ch == '-' } }, label = { Text("Priority") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                Text("Match when", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(rawName, { rawName = it }, label = { Text("Raw bank name contains") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                ChoiceField(
                    label = "Merchant",
                    valueLabel = data.merchantProfiles.firstOrNull { it.id == merchantProfileId }?.displayName ?: "Any merchant",
                    choices = listOf("Any merchant" to { merchantProfileId = null }) + data.merchantProfiles.sortedBy { it.displayName.lowercase() }.map { profile ->
                        profile.displayName to { merchantProfileId = profile.id }
                    }
                )
                ChoiceField(
                    label = "Account",
                    valueLabel = data.accounts.firstOrNull { it.id == accountId }?.name ?: "Any account",
                    choices = listOf("Any account" to { accountId = null }) + data.accounts.sortedBy { it.name.lowercase() }.map { account ->
                        account.name to { accountId = account.id }
                    }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minAmount, { minAmount = it }, label = { Text("Min amount") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(maxAmount, { maxAmount = it }, label = { Text("Max amount") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                CategoryPickerField(
                    label = "Current category (optional)",
                    value = matchCategory,
                    data = data,
                    onValueChange = { matchCategory = it },
                    modifier = Modifier.fillMaxWidth()
                )
                ChoiceField(
                    label = "Current classification",
                    valueLabel = matchClassification?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Any classification",
                    choices = listOf("Any classification" to { matchClassification = null }) + TransactionClassification.entries.map { classification ->
                        classification.name.lowercase().replaceFirstChar { it.uppercase() } to { matchClassification = classification }
                    }
                )
                ChoiceField(
                    label = "Direction",
                    valueLabel = direction?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Any direction",
                    choices = listOf("Any direction" to { direction = null }) + TransactionDirection.entries.map { value ->
                        value.name.lowercase().replaceFirstChar { it.uppercase() } to { direction = value }
                    }
                )

                HorizontalDivider()
                Text("Then do", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(displayName, { displayName = it }, label = { Text("Display name (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                CategoryPickerField(
                    label = "Set category (optional)",
                    value = actionCategory,
                    data = data,
                    onValueChange = { actionCategory = it },
                    modifier = Modifier.fillMaxWidth()
                )
                ChoiceField(
                    label = "Set classification",
                    valueLabel = actionClassification?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Do not change",
                    choices = listOf("Do not change" to { actionClassification = null }) + TransactionClassification.entries.map { classification ->
                        classification.name.lowercase().replaceFirstChar { it.uppercase() } to { actionClassification = classification }
                    }
                )
                ChoiceField(
                    label = "Spending treatment",
                    valueLabel = when (exclusion) {
                        true -> "Exclude from spending"
                        false -> "Include in spending"
                        null -> "Do not change"
                    },
                    choices = listOf(
                        "Do not change" to { exclusion = null },
                        "Exclude from spending" to { exclusion = true },
                        "Include in spending" to { exclusion = false }
                    )
                )
                ChoiceField(
                    label = "Recurring status",
                    valueLabel = when (recurringStatus) {
                        SubscriptionStatus.CONFIRMED -> "Track as recurring"
                        SubscriptionStatus.IGNORED -> "Ignore recurring"
                        null -> "Do not change"
                    },
                    choices = listOf(
                        "Do not change" to { recurringStatus = null },
                        "Track as recurring" to { recurringStatus = SubscriptionStatus.CONFIRMED },
                        "Ignore recurring" to { recurringStatus = SubscriptionStatus.IGNORED }
                    )
                )

                HorizontalDivider()
                Text("Preview", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                if (previewRows.isEmpty()) {
                    Text(
                        "No current transactions match. The rule will still apply to future matches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "${previewRows.size} current transaction${if (previewRows.size == 1) "" else "s"} would change.",
                        fontWeight = FontWeight.SemiBold
                    )
                    previewRows.take(8).forEach { PreviewRow(it) }
                    if (previewRows.size > 8) {
                        Text("+ ${previewRows.size - 8} more matches", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = previewAccepted, onCheckedChange = { previewAccepted = it })
                        Text("I reviewed these changes")
                    }
                }
                if (!validRange) Text("Minimum amount must be less than or equal to maximum.", color = MaterialTheme.colorScheme.error)
                if (!hasCondition) Text("Add at least one match condition.", color = MaterialTheme.colorScheme.error)
                if (!hasAction) Text("Add at least one action.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(candidate.copy(updatedAtEpochMs = System.currentTimeMillis())) }, enabled = canSave) {
                Text("Save rule")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PreviewRow(row: RulePreviewRow) {
    val beforeClass = transactionClassification(row.before).name.lowercase()
    val afterClass = transactionClassification(row.after).name.lowercase()
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(row.before.effectiveDisplayName(), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${row.before.category} / $beforeClass  →  ${row.after.category} / $afterClass",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (row.before.effectiveDisplayName() != row.after.effectiveDisplayName()) {
            Text(
                "Name: ${row.before.effectiveDisplayName()} → ${row.after.effectiveDisplayName()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
