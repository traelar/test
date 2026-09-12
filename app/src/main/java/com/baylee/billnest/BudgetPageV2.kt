package com.baylee.billnest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel
import com.baylee.billnest.ui.theme.BillNestColors
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsPageV2(
    data: AppData,
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    var editingBudget by remember { mutableStateOf<Budget?>(null) }
    var creatingBudget by remember { mutableStateOf(false) }
    var detailBudgetId by remember { mutableStateOf<String?>(null) }
    var showRebalance by remember { mutableStateOf(false) }
    var movingTransactionId by remember { mutableStateOf<String?>(null) }

    val overview = remember(data) {
        runCatching { calculateVariableSpendingSummary(data) }.getOrNull()
    }
    val suggestions = remember(data) {
        runCatching { suggestBudgets(data) }.getOrDefault(emptyList())
    }
    val assignments = remember(data) {
        runCatching { resolveBudgetAssignments(data) }.getOrDefault(emptyList())
    }
    val assignedIds = remember(assignments) { assignments.mapTo(mutableSetOf()) { it.transactionId } }
    val unbudgetedRows = remember(data, assignedIds) {
        val today = LocalDate.now()
        val windows = data.budgets.mapNotNull { budget -> runCatching { budgetPeriodWindow(budget, data.paydays, today) }.getOrNull() }
        val start = windows.minOfOrNull { it.start } ?: today.withDayOfMonth(1)
        val end = windows.maxOfOrNull { it.end } ?: today.withDayOfMonth(1).plusMonths(1).minusDays(1)
        eligibleVariableSpendingTransactions(data, start, end)
            .filterNot { it.id in assignedIds }
            .sortedByDescending { it.dateIso }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Variable spending", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Flexible spending only. Bills, income, transfers, savings moves, and debt payments stay out of your budgets.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            VariableSpendingSummaryCard(overview)
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { creatingBudget = true },
                    modifier = Modifier.weight(1f)
                ) { Text("+ Add budget") }
                OutlinedButton(
                    onClick = { showRebalance = true },
                    enabled = data.budgets.size >= 2,
                    modifier = Modifier.weight(1f)
                ) { Text("Move money") }
            }
        }

        if (suggestions.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Start from my spending", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Suggestions use recent eligible spending. You can change every amount and rule before saving.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            items(suggestions.take(4), key = { "suggest-${it.category}" }) { suggestion ->
                SmartSuggestionCard(suggestion) {
                    editingBudget = Budget(
                        name = suggestion.category,
                        amount = suggestion.recommendedAmount,
                        category = suggestion.category,
                        includedCategories = listOf(suggestion.category)
                    )
                    creatingBudget = true
                }
            }
        }

        item { Text("My budgets", style = MaterialTheme.typography.titleLarge) }

        if (data.budgets.isEmpty()) {
            item {
                BudgetSurface {
                    Text("No variable-spending budgets yet.", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Use Add budget or a spending suggestion above to build your first one.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(data.budgets, key = { it.id }) { budget ->
            val summary = remember(data, budget) {
                runCatching { calculateBudgetSummary(data, budget) }.getOrNull()
            }
            BudgetCard(
                budget = budget,
                summary = summary,
                onOpen = { detailBudgetId = budget.id },
                onEdit = {
                    editingBudget = budget
                    creatingBudget = true
                },
                onDelete = { vm.deleteBudget(budget.id) }
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Unbudgeted spending", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Variable purchases that do not match one of your current budget rules.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            BudgetSurface {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Current period", style = MaterialTheme.typography.titleMedium)
                    Text(
                        budgetCurrency(unbudgetedRows.sumOf { it.amount.coerceAtLeast(0.0) }),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (unbudgetedRows.isEmpty()) BillNestColors.positive else BillNestColors.warning
                    )
                }
                if (unbudgetedRows.isEmpty()) {
                    Text("Everything eligible is assigned to a budget.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    unbudgetedRows.take(5).forEach { row ->
                        HorizontalDivider(color = BillNestColors.border.copy(alpha = .55f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(row.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${row.category} • ${budgetDate(row.dateIso)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(budgetCurrency(row.amount), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (unbudgetedRows.size > 5) {
                        Text(
                            "+ ${unbudgetedRows.size - 5} more unbudgeted transactions",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    if (creatingBudget) {
        BudgetEditorSheet(
            data = data,
            existing = editingBudget,
            suggestions = suggestions,
            onDismiss = {
                creatingBudget = false
                editingBudget = null
            },
            onSave = { budget ->
                vm.saveBudget(budget)
                creatingBudget = false
                editingBudget = null
            }
        )
    }

    detailBudgetId?.let { id ->
        data.budgets.firstOrNull { it.id == id }?.let { budget ->
            BudgetDetailSheet(
                data = data,
                budget = budget,
                onDismiss = { detailBudgetId = null },
                onEdit = {
                    detailBudgetId = null
                    editingBudget = budget
                    creatingBudget = true
                },
                onExclude = { transactionId -> vm.setTransactionBudget(transactionId, null) },
                onMove = { transactionId -> movingTransactionId = transactionId },
                onMoveMoney = { showRebalance = true }
            )
        }
    }

    movingTransactionId?.let { transactionId ->
        MoveTransactionDialog(
            transaction = data.transactions.firstOrNull { it.id == transactionId },
            budgets = data.budgets,
            currentBudgetId = assignments.firstOrNull { it.transactionId == transactionId }?.budgetId,
            onDismiss = { movingTransactionId = null },
            onAssign = { budgetId ->
                vm.setTransactionBudget(transactionId, budgetId)
                movingTransactionId = null
            }
        )
    }

    if (showRebalance) {
        RebalanceBudgetDialog(
            data = data,
            onDismiss = { showRebalance = false },
            onMove = { source, destination, amount ->
                vm.moveBudgetMoney(source, destination, amount)
            }
        )
    }
}

@Composable
private fun VariableSpendingSummaryCard(summary: VariableSpendingSummary?) {
    BudgetSurface(strong = true) {
        Text("Available for variable spending", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            budgetCurrency(summary?.remaining ?: 0.0),
            style = MaterialTheme.typography.headlineMedium,
            color = when {
                summary == null -> MaterialTheme.colorScheme.onSurface
                summary.remaining < 0 -> BillNestColors.danger
                else -> BillNestColors.positive
            }
        )
        if (summary == null) {
            Text("Add a valid budget to start tracking.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BudgetMiniStat("Planned", budgetCurrency(summary.planned), Modifier.weight(1f))
                BudgetMiniStat("Spent", budgetCurrency(summary.budgetedSpent), Modifier.weight(1f))
                BudgetMiniStat("Unbudgeted", budgetCurrency(summary.unbudgetedSpent), Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Projected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(budgetCurrency(summary.projectedSpend), style = MaterialTheme.typography.titleMedium)
                }
                PacePill(summary.pace)
            }
        }
    }
}

@Composable
private fun BudgetMiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SmartSuggestionCard(suggestion: BudgetSuggestion, onUse: () -> Unit) {
    BudgetSurface {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(suggestion.category, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Last 30 days ${budgetCurrency(suggestion.last30Days)} • recent avg ${budgetCurrency(suggestion.recentMonthlyAverage)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                if (suggestion.merchants.isNotEmpty()) {
                    Text(
                        suggestion.merchants.take(3).joinToString(" • "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Suggested", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(budgetCurrency(suggestion.recommendedAmount), color = BillNestColors.accent, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onUse) { Text("Use") }
            }
        }
    }
}

@Composable
private fun BudgetCard(
    budget: Budget,
    summary: BudgetSummary?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    BudgetSurface {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(budget.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    budgetPeriodLabel(budget.period),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            if (summary != null) PacePill(summary.pace)
        }

        if (summary == null) {
            Text("This budget needs attention before BillNest can calculate it.", color = BillNestColors.warning)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Remaining", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Text(
                        budgetCurrency(summary.remaining),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (summary.remaining >= 0) BillNestColors.positive else BillNestColors.danger
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Spent", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Text(
                        "${budgetCurrency(summary.spent)} / ${budgetCurrency(summary.effectiveAmount)}",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            LinearProgressIndicator(
                progress = summary.percentUsed.coerceIn(0.0, 1.0).toFloat(),
                modifier = Modifier.fillMaxWidth().height(7.dp),
                color = paceColor(summary.pace),
                trackColor = BillNestColors.border.copy(alpha = .45f)
            )
            Text(
                "${summary.daysRemaining} days left • ${budgetCurrency(summary.dailyAllowance)}/day available • projected ${budgetCurrency(summary.projectedSpend)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            if (summary.adjustmentAmount != 0.0 || summary.rolloverAmount != 0.0) {
                Text(
                    buildString {
                        if (summary.adjustmentAmount != 0.0) append("Current-period adjustment ${signedCurrency(summary.adjustmentAmount)}")
                        if (summary.rolloverAmount != 0.0) {
                            if (isNotEmpty()) append(" • ")
                            append("Rollover ${signedCurrency(summary.rolloverAmount)}")
                        }
                    },
                    color = BillNestColors.info,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpen) { Text("Details") }
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetEditorSheet(
    data: AppData,
    existing: Budget?,
    suggestions: List<BudgetSuggestion>,
    onDismiss: () -> Unit,
    onSave: (Budget) -> Unit
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var amount by remember(existing?.id) { mutableStateOf(existing?.amount?.toString().orEmpty()) }
    var period by remember(existing?.id) { mutableStateOf(existing?.period ?: BudgetPeriod.MONTHLY) }
    var categories by remember(existing?.id) {
        mutableStateOf(
            (existing?.includedCategories?.takeIf { it.isNotEmpty() }
                ?: existing?.category?.takeIf { it != "Other" }?.let(::listOf)
                ?: emptyList()).joinToString(", ")
        )
    }
    var excludedCategories by remember(existing?.id) { mutableStateOf(existing?.excludedCategories?.joinToString(", ").orEmpty()) }
    var merchants by remember(existing?.id) { mutableStateOf(existing?.includedMerchants?.joinToString(", ").orEmpty()) }
    var excludedMerchants by remember(existing?.id) { mutableStateOf(existing?.excludedMerchants?.joinToString(", ").orEmpty()) }
    var selectedAccounts by remember(existing?.id) { mutableStateOf(existing?.includedAccountIds?.toSet() ?: emptySet()) }
    var excludedAccounts by remember(existing?.id) { mutableStateOf(existing?.excludedAccountIds?.toSet() ?: emptySet()) }
    var paydayId by remember(existing?.id) { mutableStateOf(existing?.paydayId) }
    var customStart by remember(existing?.id) { mutableStateOf(existing?.startDateIso.orEmpty()) }
    var customEnd by remember(existing?.id) { mutableStateOf(existing?.endDateIso.orEmpty()) }
    var rolloverMode by remember(existing?.id) {
        mutableStateOf(
            existing?.rolloverMode?.takeUnless { it == BudgetRolloverMode.RESET && existing.rollover }
                ?: if (existing?.rollover == true) BudgetRolloverMode.CARRY_UNUSED else BudgetRolloverMode.RESET
        )
    }
    var warningPercent by remember(existing?.id) { mutableStateOf((existing?.warningPercent ?: 90).toString()) }
    var paceTracking by remember(existing?.id) { mutableStateOf(existing?.paceTracking ?: true) }
    var periodExpanded by remember { mutableStateOf(false) }
    var paydayExpanded by remember { mutableStateOf(false) }
    var rolloverExpanded by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .fillMaxHeight(.92f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(if (existing == null) "Create variable-spending budget" else "Edit ${existing.name}", style = MaterialTheme.typography.headlineSmall)
            Text(
                "BillNest will automatically match eligible purchases. You can always move or exclude individual transactions later.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            EditorSection("1", "What are you budgeting?")
            if (suggestions.isNotEmpty()) {
                Text("Start from my spending", style = MaterialTheme.typography.labelLarge)
                suggestions.take(5).forEach { suggestion ->
                    OutlinedButton(
                        onClick = {
                            name = suggestion.category
                            amount = suggestion.recommendedAmount.toString()
                            categories = suggestion.category
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${suggestion.category}: ${budgetCurrency(suggestion.recommendedAmount)} suggested")
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Budget name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = categories,
                onValueChange = { categories = it },
                label = { Text("Categories included") },
                supportingText = { Text("One or several, separated by commas. Example: Groceries, Household") },
                modifier = Modifier.fillMaxWidth()
            )

            EditorSection("2", "Amount & period")
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("Budget amount") },
                prefix = { Text("$") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { periodExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Period: ${budgetPeriodLabel(period)}")
                }
                DropdownMenu(expanded = periodExpanded, onDismissRequest = { periodExpanded = false }) {
                    BudgetPeriod.values().forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(budgetPeriodLabel(choice)) },
                            onClick = {
                                period = choice
                                periodExpanded = false
                            }
                        )
                    }
                }
            }

            if (period == BudgetPeriod.PAYCHECK) {
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { paydayExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Payday: ${data.paydays.firstOrNull { it.id == paydayId }?.label ?: "Choose schedule"}")
                    }
                    DropdownMenu(expanded = paydayExpanded, onDismissRequest = { paydayExpanded = false }) {
                        if (data.paydays.isEmpty()) {
                            DropdownMenuItem(text = { Text("No payday schedules available") }, onClick = { paydayExpanded = false })
                        } else {
                            data.paydays.forEach { payday ->
                                DropdownMenuItem(
                                    text = { Text("${payday.label} • ${payday.frequency.name.replace('_', ' ')}") },
                                    onClick = {
                                        paydayId = payday.id
                                        paydayExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (period == BudgetPeriod.CUSTOM) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customStart,
                        onValueChange = { customStart = it },
                        label = { Text("Start YYYY-MM-DD") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = customEnd,
                        onValueChange = { customEnd = it },
                        label = { Text("End YYYY-MM-DD") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }

            EditorSection("3", "What counts?")
            OutlinedTextField(
                value = merchants,
                onValueChange = { merchants = it },
                label = { Text("Include merchants") },
                supportingText = { Text("Optional. Separate names with commas, like Aldi, Walmart, Festival Foods") },
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide advanced rules" else "Advanced include / exclude rules")
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = excludedCategories,
                    onValueChange = { excludedCategories = it },
                    label = { Text("Exclude categories") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = excludedMerchants,
                    onValueChange = { excludedMerchants = it },
                    label = { Text("Exclude merchants") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (data.accounts.isNotEmpty()) {
                    Text("Included accounts", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Leave every account unchecked to allow all accounts.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    data.accounts.filter { it.role != AccountRole.CREDIT }.forEach { account ->
                        AccountRuleRow(
                            label = account.name,
                            checked = account.id in selectedAccounts,
                            excluded = account.id in excludedAccounts,
                            onChecked = { checked ->
                                selectedAccounts = if (checked) selectedAccounts + account.id else selectedAccounts - account.id
                                if (checked) excludedAccounts = excludedAccounts - account.id
                            },
                            onExcluded = { excluded ->
                                excludedAccounts = if (excluded) excludedAccounts + account.id else excludedAccounts - account.id
                                if (excluded) selectedAccounts = selectedAccounts - account.id
                            }
                        )
                    }
                }
            }

            EditorSection("4", "Budget behavior")
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { rolloverExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Rollover: ${rolloverLabel(rolloverMode)}")
                }
                DropdownMenu(expanded = rolloverExpanded, onDismissRequest = { rolloverExpanded = false }) {
                    BudgetRolloverMode.values().forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(rolloverLabel(choice)) },
                            onClick = {
                                rolloverMode = choice
                                rolloverExpanded = false
                            }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = warningPercent,
                onValueChange = { warningPercent = it.filter(Char::isDigit).take(3) },
                label = { Text("Warn me at percent used") },
                suffix = { Text("%") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Pace tracking", style = MaterialTheme.typography.titleMedium)
                    Text("Warn me when spending is moving too fast for the period.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = paceTracking, onCheckedChange = { paceTracking = it })
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        val parsedAmount = amount.toDoubleOrNull()
                        val parsedWarning = warningPercent.toIntOrNull()?.coerceIn(1, 100) ?: 90
                        val includeCategories = parseRules(categories)
                        val candidate = Budget(
                            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            amount = parsedAmount ?: 0.0,
                            category = includeCategories.firstOrNull() ?: existing?.category ?: "Other",
                            period = period,
                            rollover = rolloverMode != BudgetRolloverMode.RESET,
                            startDateIso = customStart.trim().takeIf { period == BudgetPeriod.CUSTOM && it.isNotBlank() },
                            endDateIso = customEnd.trim().takeIf { period == BudgetPeriod.CUSTOM && it.isNotBlank() },
                            includedCategories = includeCategories,
                            excludedCategories = parseRules(excludedCategories),
                            includedMerchants = parseRules(merchants),
                            excludedMerchants = parseRules(excludedMerchants),
                            includedAccountIds = selectedAccounts.toList(),
                            excludedAccountIds = excludedAccounts.toList(),
                            paydayId = paydayId.takeIf { period == BudgetPeriod.PAYCHECK },
                            rolloverMode = rolloverMode,
                            warningPercent = parsedWarning,
                            paceTracking = paceTracking
                        )
                        error = when {
                            candidate.name.isBlank() -> "Give this budget a name."
                            parsedAmount == null || parsedAmount <= 0.0 -> "Enter a budget amount greater than zero."
                            includeCategories.isEmpty() && candidate.includedMerchants.isEmpty() && candidate.includedAccountIds.isEmpty() -> "Choose at least one category, merchant, or account rule."
                            period == BudgetPeriod.PAYCHECK && candidate.paydayId == null -> "Choose a payday schedule for a paycheck budget."
                            else -> runCatching { budgetPeriodWindow(candidate, data.paydays); null }
                                .getOrElse { it.message ?: "Check this budget's period settings." }
                        }
                        if (error == null) onSave(candidate)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save budget") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EditorSection(number: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = BillNestColors.accent.copy(alpha = .14f),
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, BillNestColors.accent.copy(alpha = .35f))
        ) {
            Text(number, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = BillNestColors.accent, fontWeight = FontWeight.Bold)
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun AccountRuleRow(
    label: String,
    checked: Boolean,
    excluded: Boolean,
    onChecked: (Boolean) -> Unit,
    onExcluded: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("Include", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text("Exclude", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Checkbox(checked = excluded, onCheckedChange = onExcluded)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetDetailSheet(
    data: AppData,
    budget: Budget,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onExclude: (String) -> Unit,
    onMove: (String) -> Unit,
    onMoveMoney: () -> Unit
) {
    val summary = remember(data, budget) { runCatching { calculateBudgetSummary(data, budget) }.getOrNull() }
    val transactionById = remember(data.transactions) { data.transactions.associateBy { it.id } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(.9f),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(budget.name, style = MaterialTheme.typography.headlineSmall)
                        Text(budgetPeriodLabel(budget.period), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    summary?.let { PacePill(it.pace) }
                }
            }

            if (summary == null) {
                item { Text("This budget cannot be calculated until its period settings are fixed.", color = BillNestColors.warning) }
            } else {
                item {
                    BudgetSurface(strong = true) {
                        Text("Remaining", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            budgetCurrency(summary.remaining),
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (summary.remaining >= 0) BillNestColors.positive else BillNestColors.danger
                        )
                        Text("${budgetCurrency(summary.spent)} spent of ${budgetCurrency(summary.effectiveAmount)}")
                        Text(
                            "${summary.daysRemaining} days left • ${budgetCurrency(summary.dailyAllowance)}/day • projected ${budgetCurrency(summary.projectedSpend)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Edit rules") }
                        OutlinedButton(onClick = onMoveMoney, enabled = data.budgets.size >= 2, modifier = Modifier.weight(1f)) { Text("Move money") }
                    }
                }
                item {
                    Text("Transactions in this budget", style = MaterialTheme.typography.titleLarge)
                    Text("Manual changes always override automatic matching.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (summary.matches.isEmpty()) {
                    item { Text("No eligible transactions matched this period yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(summary.matches, key = { "budget-match-${it.transactionId}" }) { match ->
                    transactionById[match.transactionId]?.let { row ->
                        BudgetSurface {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${budgetDate(row.dateIso)} • ${row.category}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text("Matched by: ${match.reason}", color = BillNestColors.info, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(budgetCurrency(row.amount), style = MaterialTheme.typography.titleMedium)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onMove(row.id) }) { Text("Move") }
                                TextButton(onClick = { onExclude(row.id) }) { Text("Exclude") }
                            }
                        }
                    }
                }
                val periodAdjustments = data.budgetAdjustments.filter { it.periodStartIso == summary.window.start.toString() && (it.sourceBudgetId == budget.id || it.destinationBudgetId == budget.id) }
                if (periodAdjustments.isNotEmpty()) {
                    item { Text("Current-period adjustments", style = MaterialTheme.typography.titleLarge) }
                    items(periodAdjustments, key = { "adjust-${it.id}" }) { adjustment ->
                        val inbound = adjustment.destinationBudgetId == budget.id
                        val otherId = if (inbound) adjustment.sourceBudgetId else adjustment.destinationBudgetId
                        val other = data.budgets.firstOrNull { it.id == otherId }?.name ?: "another budget"
                        Text(
                            if (inbound) "+${budgetCurrency(adjustment.amount)} from $other" else "-${budgetCurrency(adjustment.amount)} to $other",
                            color = if (inbound) BillNestColors.positive else BillNestColors.warning
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun MoveTransactionDialog(
    transaction: FinanceTransaction?,
    budgets: List<Budget>,
    currentBudgetId: String?,
    onDismiss: () -> Unit,
    onAssign: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(transaction?.name ?: "Transaction", style = MaterialTheme.typography.titleMedium)
                Text("Choose the budget that should own this transaction for the current period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                budgets.filterNot { it.id == currentBudgetId }.forEach { budget ->
                    OutlinedButton(onClick = { onAssign(budget.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(budget.name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RebalanceBudgetDialog(
    data: AppData,
    onDismiss: () -> Unit,
    onMove: (String, String, Double) -> Unit
) {
    var sourceId by remember { mutableStateOf(data.budgets.firstOrNull()?.id) }
    var destinationId by remember { mutableStateOf(data.budgets.drop(1).firstOrNull()?.id) }
    var amount by remember { mutableStateOf("") }
    var sourceExpanded by remember { mutableStateOf(false) }
    var destinationExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move money between budgets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This changes only the active period. Your recurring base amounts stay the same.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { sourceExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("From: ${data.budgets.firstOrNull { it.id == sourceId }?.name ?: "Choose"}")
                    }
                    DropdownMenu(expanded = sourceExpanded, onDismissRequest = { sourceExpanded = false }) {
                        data.budgets.forEach { budget ->
                            DropdownMenuItem(text = { Text(budget.name) }, onClick = {
                                sourceId = budget.id
                                if (destinationId == sourceId) destinationId = data.budgets.firstOrNull { it.id != sourceId }?.id
                                sourceExpanded = false
                            })
                        }
                    }
                }
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { destinationExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("To: ${data.budgets.firstOrNull { it.id == destinationId }?.name ?: "Choose"}")
                    }
                    DropdownMenu(expanded = destinationExpanded, onDismissRequest = { destinationExpanded = false }) {
                        data.budgets.filterNot { it.id == sourceId }.forEach { budget ->
                            DropdownMenuItem(text = { Text(budget.name) }, onClick = {
                                destinationId = budget.id
                                destinationExpanded = false
                            })
                        }
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Amount") },
                    prefix = { Text("$") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                sourceId?.let { id ->
                    data.budgets.firstOrNull { it.id == id }?.let { source ->
                        runCatching { calculateBudgetSummary(data, source) }.getOrNull()?.let { summary ->
                            Text("Current allocation: ${budgetCurrency(summary.effectiveAmount)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val source = sourceId
                val destination = destinationId
                val parsed = amount.toDoubleOrNull()
                error = when {
                    source == null || destination == null -> "Choose both budgets."
                    source == destination -> "Choose two different budgets."
                    parsed == null || parsed <= 0.0 -> "Enter an amount greater than zero."
                    else -> runCatching {
                        onMove(source, destination, parsed)
                        null
                    }.getOrElse { it.message ?: "Could not move that amount." }
                }
                if (error == null) onDismiss()
            }) { Text("Move money") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PacePill(pace: BudgetPace) {
    val label = when (pace) {
        BudgetPace.UNDER -> "Under budget"
        BudgetPace.ON_TRACK -> "On track"
        BudgetPace.WARNING -> "Watch pace"
        BudgetPace.OVER -> "Over budget"
    }
    val color = paceColor(pace)
    Surface(
        color = color.copy(alpha = .13f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, color.copy(alpha = .35f))
    ) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun BudgetSurface(strong: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (strong) BillNestColors.cardStrong else BillNestColors.card),
        border = BorderStroke(1.dp, BillNestColors.border)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

private fun paceColor(pace: BudgetPace): Color = when (pace) {
    BudgetPace.UNDER -> BillNestColors.positive
    BudgetPace.ON_TRACK -> BillNestColors.accent
    BudgetPace.WARNING -> BillNestColors.warning
    BudgetPace.OVER -> BillNestColors.danger
}

private fun parseRules(value: String): List<String> = value.split(',')
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .distinctBy { it.lowercase() }

private fun budgetPeriodLabel(period: BudgetPeriod): String = when (period) {
    BudgetPeriod.WEEKLY -> "Weekly"
    BudgetPeriod.BIWEEKLY -> "Every 2 weeks"
    BudgetPeriod.PAYCHECK -> "Every paycheck"
    BudgetPeriod.MONTHLY -> "Monthly"
    BudgetPeriod.YEARLY -> "Yearly"
    BudgetPeriod.CUSTOM -> "Custom dates"
}

private fun rolloverLabel(mode: BudgetRolloverMode): String = when (mode) {
    BudgetRolloverMode.RESET -> "Reset each period"
    BudgetRolloverMode.CARRY_UNUSED -> "Carry unused money"
    BudgetRolloverMode.CARRY_BALANCE -> "Carry unused and overspending"
}

private fun budgetCurrency(value: Double): String = NumberFormat.getCurrencyInstance().format(abs(value))
private fun signedCurrency(value: Double): String = (if (value >= 0) "+" else "-") + budgetCurrency(value)
private fun budgetDate(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("MMM d"))
}.getOrDefault(value)
