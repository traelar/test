package com.baylee.billnest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.AppData
import com.baylee.billnest.model.FinanceTransaction
import com.baylee.billnest.model.MerchantConfirmation
import com.baylee.billnest.model.MerchantProfile
import com.baylee.billnest.model.SmartTransactionRule
import com.baylee.billnest.model.TransactionClassification
import com.baylee.billnest.model.effectiveDisplayName
import com.baylee.billnest.model.toSmartRule
import com.baylee.billnest.ui.MainViewModel
import java.util.UUID

@Composable
fun MerchantManagerPage(
    data: AppData,
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    var search by remember { mutableStateOf("") }
    var editingMerchant by remember { mutableStateOf<MerchantProfile?>(null) }
    var creatingMerchant by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<SmartTransactionRule?>(null) }
    var creatingRule by remember { mutableStateOf(false) }

    val query = search.trim().lowercase()
    val merchants = remember(data.merchantProfiles, query) {
        data.merchantProfiles
            .filter { profile ->
                query.isBlank() || profile.displayName.lowercase().contains(query) ||
                    profile.aliases.any { it.lowercase().contains(query) } ||
                    profile.preferredCategory.orEmpty().lowercase().contains(query)
            }
            .sortedBy { it.displayName.lowercase() }
    }
    val rules = remember(data.smartTransactionRules, query) {
        data.smartTransactionRules
            .filter { rule ->
                query.isBlank() || rule.name.lowercase().contains(query) ||
                    rule.match.rawNameContains.orEmpty().lowercase().contains(query) ||
                    rule.action.category.orEmpty().lowercase().contains(query)
            }
            .sortedWith(compareByDescending<SmartTransactionRule> { it.enabled }.thenByDescending { it.priority }.thenBy { it.name.lowercase() })
    }
    val legacy = remember(data.transactionRules, query) {
        data.transactionRules.filter { rule ->
            query.isBlank() || rule.merchantContains.lowercase().contains(query) ||
                rule.renameTo.orEmpty().lowercase().contains(query) || rule.category.orEmpty().lowercase().contains(query)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Merchants & Rules", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Clean up bank merchant names once, set preferred categories, and automate future transactions without changing the raw bank data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search merchants and rules") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { creatingMerchant = true }, modifier = Modifier.weight(1f)) { Text("+ Merchant") }
                    Button(onClick = { creatingRule = true }, modifier = Modifier.weight(1f)) { Text("+ Rule") }
                }
            }
        }

        item { SectionHeading("Merchant profiles", merchants.size) }
        if (merchants.isEmpty()) {
            item { EmptyManagerCard("No merchant profiles match this search.") }
        } else {
            items(merchants, key = { "merchant-${it.id}" }) { profile ->
                MerchantProfileCard(
                    profile = profile,
                    data = data,
                    onEdit = { editingMerchant = profile },
                    onDelete = { vm.deleteMerchantProfile(profile.id) }
                )
            }
        }

        item { SectionHeading("Smart rules", rules.size) }
        if (rules.isEmpty()) {
            item { EmptyManagerCard("No smart rules match this search.") }
        } else {
            items(rules, key = { "rule-${it.id}" }) { rule ->
                SmartRuleCard(
                    rule = rule,
                    data = data,
                    onEdit = { editingRule = rule },
                    onDelete = { vm.deleteSmartTransactionRule(rule.id) }
                )
            }
        }

        if (legacy.isNotEmpty()) {
            item { SectionHeading("Legacy rules", legacy.size) }
            item {
                Text(
                    "These older merchant rules still work. Convert only when you want the richer smart-rule controls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(legacy, key = { "legacy-${it.id}" }) { rule ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(rule.renameTo?.takeIf { it.isNotBlank() } ?: rule.merchantContains, fontWeight = FontWeight.SemiBold)
                        Text("Matches raw name containing “${rule.merchantContains}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val actions = buildList {
                            rule.category?.takeIf { it.isNotBlank() }?.let { add("category → $it") }
                            rule.renameTo?.takeIf { it.isNotBlank() }?.let { add("display name → $it") }
                            if (rule.excludeFromSpending) add("exclude from spending")
                        }
                        if (actions.isNotEmpty()) Text(actions.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            vm.saveSmartTransactionRule(rule.toSmartRule())
                            vm.deleteTransactionRule(rule.id)
                        }) { Text("Convert to smart rule") }
                    }
                }
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 32.dp)) }
    }

    if (creatingMerchant || editingMerchant != null) {
        MerchantEditorDialog(
            data = data,
            existing = editingMerchant,
            onDismiss = {
                creatingMerchant = false
                editingMerchant = null
            },
            onSave = { value ->
                vm.saveMerchantProfile(value)
                creatingMerchant = false
                editingMerchant = null
            }
        )
    }

    if (creatingRule || editingRule != null) {
        SmartRuleEditorDialog(
            data = data,
            existing = editingRule,
            onDismiss = {
                creatingRule = false
                editingRule = null
            },
            onSave = { value ->
                vm.saveSmartTransactionRule(value)
                creatingRule = false
                editingRule = null
            }
        )
    }
}

@Composable
private fun SectionHeading(title: String, count: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun EmptyManagerCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MerchantProfileCard(
    profile: MerchantProfile,
    data: AppData,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(profile.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (profile.aliases.isNotEmpty()) {
                Text("Aliases: ${profile.aliases.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            profile.preferredCategory?.takeIf { it.isNotBlank() }?.let { Text("Preferred category: $it", style = MaterialTheme.typography.bodySmall) }
            profile.defaultClassification?.let { Text("Default: ${it.name.lowercase().replaceFirstChar { ch -> ch.uppercase() }}", style = MaterialTheme.typography.bodySmall) }
            profile.linkedBillId?.let { id ->
                data.bills.firstOrNull { it.id == id }?.let { Text("Linked bill: ${it.name}", style = MaterialTheme.typography.bodySmall) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun SmartRuleCard(
    rule: SmartTransactionRule,
    data: AppData,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(rule.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (rule.enabled) "Enabled" else "Disabled", color = if (rule.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            }
            Text("Priority ${rule.priority}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("When: ${ruleMatchSummary(rule, data)}", style = MaterialTheme.typography.bodySmall)
            Text("Then: ${ruleActionSummary(rule)}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun ruleMatchSummary(rule: SmartTransactionRule, data: AppData): String = buildList {
    rule.match.rawNameContains?.let { add("raw name contains “$it”") }
    rule.match.merchantProfileId?.let { id -> add("merchant ${data.merchantProfiles.firstOrNull { it.id == id }?.displayName ?: id}") }
    rule.match.accountId?.let { id -> add("account ${data.accounts.firstOrNull { it.id == id }?.name ?: id}") }
    rule.match.minAmount?.let { add("min $$it") }
    rule.match.maxAmount?.let { add("max $$it") }
    rule.match.category?.let { add("category $it") }
    rule.match.classification?.let { add(it.name.lowercase()) }
    rule.match.direction?.let { add(it.name.lowercase()) }
}.ifEmpty { listOf("no conditions") }.joinToString(" • ")

private fun ruleActionSummary(rule: SmartTransactionRule): String = buildList {
    rule.action.displayName?.let { add("display name → $it") }
    rule.action.category?.let { add("category → $it") }
    rule.action.classification?.let { add("classification → ${it.name.lowercase()}") }
    rule.action.excludeFromSpending?.let { add(if (it) "exclude from spending" else "include in spending") }
    rule.action.recurringStatus?.let { add(if (it.name == "CONFIRMED") "track recurring" else "ignore recurring") }
}.ifEmpty { listOf("no actions") }.joinToString(" • ")

@Composable
fun MerchantEditorDialog(
    data: AppData,
    existing: MerchantProfile? = null,
    sourceTransaction: FinanceTransaction? = null,
    onDismiss: () -> Unit,
    onSave: (MerchantProfile) -> Unit
) {
    val stableId = remember(existing?.id, sourceTransaction?.id) { existing?.id ?: UUID.randomUUID().toString() }
    var displayName by remember(existing?.id, sourceTransaction?.id) {
        mutableStateOf(existing?.displayName ?: sourceTransaction?.effectiveDisplayName().orEmpty())
    }
    var aliasesText by remember(existing?.id, sourceTransaction?.id) {
        mutableStateOf(
            existing?.aliases?.joinToString(", ")
                ?: sourceTransaction?.name.orEmpty()
        )
    }
    var preferredCategory by remember(existing?.id) { mutableStateOf(existing?.preferredCategory.orEmpty()) }
    var classification by remember(existing?.id) { mutableStateOf(existing?.defaultClassification) }
    var linkedBillId by remember(existing?.id) { mutableStateOf(existing?.linkedBillId) }
    var subscriptionKey by remember(existing?.id) { mutableStateOf(existing?.subscriptionMerchantKey.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Merchant profile" else "Edit merchant") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 590.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(displayName, { displayName = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    aliasesText,
                    { aliasesText = it },
                    label = { Text("Aliases") },
                    supportingText = { Text("Separate bank descriptions with commas or new lines.") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                CategoryPickerField(
                    label = "Preferred category (optional)",
                    value = preferredCategory,
                    data = data,
                    onValueChange = { preferredCategory = it },
                    modifier = Modifier.fillMaxWidth()
                )
                ChoiceField(
                    label = "Default classification",
                    valueLabel = classification?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Do not change",
                    choices = listOf("Do not change" to { classification = null }) + TransactionClassification.entries.map { value ->
                        value.name.lowercase().replaceFirstChar { it.uppercase() } to { classification = value }
                    }
                )
                ChoiceField(
                    label = "Linked bill",
                    valueLabel = data.bills.firstOrNull { it.id == linkedBillId }?.name ?: "No linked bill",
                    choices = listOf("No linked bill" to { linkedBillId = null }) + data.bills.sortedBy { it.name.lowercase() }.map { bill ->
                        bill.name to { linkedBillId = bill.id }
                    }
                )
                OutlinedTextField(
                    subscriptionKey,
                    { subscriptionKey = it },
                    label = { Text("Subscription identity (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val aliases = aliasesText
                        .split(',', '\n')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinctBy { it.lowercase() }
                    onSave(
                        MerchantProfile(
                            id = stableId,
                            displayName = displayName.trim(),
                            aliases = aliases,
                            preferredCategory = preferredCategory.trim().takeIf { it.isNotBlank() },
                            defaultClassification = classification,
                            linkedBillId = linkedBillId,
                            subscriptionMerchantKey = subscriptionKey.trim().takeIf { it.isNotBlank() },
                            confirmation = MerchantConfirmation.USER_CONFIRMED,
                            lastSeenEpochMs = existing?.lastSeenEpochMs ?: System.currentTimeMillis(),
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                },
                enabled = displayName.isNotBlank() && aliasesText.split(',', '\n').any { it.isNotBlank() }
            ) { Text("Save merchant") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
