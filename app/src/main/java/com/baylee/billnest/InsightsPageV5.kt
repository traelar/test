package com.baylee.billnest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel
import com.baylee.billnest.ui.insightsBudgetEmptyState
import com.baylee.billnest.ui.theme.BillNestColors
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val insightsMoney = NumberFormat.getCurrencyInstance()
private fun iMoney(value: Double): String = insightsMoney.format(value)

private enum class InsightTransactionAction { EDIT, SUBSCRIPTION, RULE, DELETE }

private fun insightTransactionActionRows(includeSubscription: Boolean): List<List<InsightTransactionAction>> =
    if (includeSubscription) {
        listOf(
            listOf(InsightTransactionAction.EDIT, InsightTransactionAction.SUBSCRIPTION),
            listOf(InsightTransactionAction.RULE, InsightTransactionAction.DELETE)
        )
    } else {
        listOf(
            listOf(InsightTransactionAction.EDIT, InsightTransactionAction.RULE),
            listOf(InsightTransactionAction.DELETE)
        )
    }

@Composable
private fun InsightsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BillNestColors.card),
        border = BorderStroke(1.dp, BillNestColors.border)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun InsightsProgressCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BillNestColors.cardRaised),
        border = BorderStroke(1.dp, BillNestColors.border)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

/** Single-scroll Insights page. Avoids the old nested LazyColumn clipping and protects money values from narrow wrapping. */
@Composable
fun InsightsPageV5(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var editingTransaction by remember { mutableStateOf<FinanceTransaction?>(null) }
    var ruleFor by remember { mutableStateOf<FinanceTransaction?>(null) }
    var reopenCategory by remember { mutableStateOf<String?>(null) }
    val recap = remember(data, month) { monthlyRecap(data, month) }
    val currentRecap = remember(data) { monthlyRecap(data, YearMonth.now()) }
    val alerts = remember(data) { runCatching { budgetAlerts(data) }.getOrDefault(emptyList()) }
    val netWorth = remember(data) { netWorthHistory(data) }
    val hasProgress = currentRecap.largestIncreaseCategory != null ||
        currentRecap.assetChange != null ||
        currentRecap.debtReduction != null ||
        currentRecap.netWorthChange != null

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        if (hasProgress) {
            item {
                InsightsProgressCard {
                    Text("Monthly progress", style = MaterialTheme.typography.titleMedium)
                    currentRecap.largestIncreaseCategory?.let { category ->
                        Text(
                            "Biggest spending increase: $category +${iMoney(currentRecap.largestIncreaseAmount)}",
                            color = BillNestColors.warning
                        )
                    }
                    currentRecap.assetChange?.let { change ->
                        Text(
                            "Assets ${signedInsightsMoney(change)}",
                            color = if (change >= 0) BillNestColors.positive else BillNestColors.warning
                        )
                    }
                    currentRecap.debtReduction?.let { change ->
                        Text(
                            "Debt ${if (change >= 0) "down" else "up"} ${iMoney(abs(change))}",
                            color = if (change >= 0) BillNestColors.positive else BillNestColors.danger
                        )
                    }
                    currentRecap.netWorthChange?.let { change ->
                        Text(
                            "Net worth ${signedInsightsMoney(change)}",
                            color = if (change >= 0) BillNestColors.positive else BillNestColors.danger
                        )
                    }
                    if (currentRecap.assetChange == null) {
                        Text(
                            "Asset, debt, and net-worth month-over-month progress appears after BillNest has snapshots in two months.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            InsightsCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton({ month = month.minusMonths(1) }) { Text("‹") }
                    Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleLarge)
                    TextButton(
                        { if (month < YearMonth.now()) month = month.plusMonths(1) },
                        enabled = month < YearMonth.now()
                    ) { Text("›") }
                }
                InsightAmountLine("Variable spending", recap.spending)
                InsightAmountLine("Income", recap.income, BillNestColors.positive)
                val delta = recap.spendingDelta
                Text(
                    if (delta <= 0) "You spent ${iMoney(abs(delta))} less than the prior month."
                    else "You spent ${iMoney(delta)} more than the prior month.",
                    color = if (delta <= 0) BillNestColors.positive else BillNestColors.warning
                )
                recap.topCategory?.let {
                    Text(
                        "Top category: $it • ${iMoney(recap.topCategoryAmount)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (recap.categoryTotals.isNotEmpty()) {
            item { Text("Spending by category", style = MaterialTheme.typography.titleLarge) }
            items(recap.categoryTotals.toList(), key = { it.first }) { (category, amount) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedCategory = category },
                    colors = CardDefaults.cardColors(containerColor = BillNestColors.card),
                    border = BorderStroke(1.dp, BillNestColors.border)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(category, style = MaterialTheme.typography.titleMedium)
                            Text("View", color = BillNestColors.info, style = MaterialTheme.typography.labelLarge)
                        }
                        Text(
                            iMoney(amount),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        item { Text("Budget alerts", style = MaterialTheme.typography.titleLarge) }
        if (alerts.isEmpty()) {
            item {
                InsightsCard {
                    Text(
                        insightsBudgetEmptyState(data.budgets.isNotEmpty()),
                        color = if (data.budgets.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else BillNestColors.positive
                    )
                }
            }
        }
        items(alerts, key = { it.budgetId }) { alert ->
            InsightsCard {
                Text(alert.budgetName, style = MaterialTheme.typography.titleMedium)
                Text(
                    alert.message,
                    color = if (alert.pace == BudgetPace.OVER) BillNestColors.danger else BillNestColors.warning
                )
                LinearProgressIndicator(
                    progress = { alert.percentUsed.coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = if (alert.pace == BudgetPace.OVER) BillNestColors.danger else BillNestColors.warning,
                    trackColor = BillNestColors.cardStrong
                )
            }
        }

        if (data.budgets.isNotEmpty()) {
            item { Text("Budget history", style = MaterialTheme.typography.titleLarge) }
            items(data.budgets, key = { "history-${it.id}" }) { budget ->
                val history = remember(data, budget) {
                    runCatching { budgetHistory(data, budget, 4) }.getOrDefault(emptyList())
                }
                InsightsCard {
                    Text(budget.name, style = MaterialTheme.typography.titleMedium)
                    history.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(color = BillNestColors.border.copy(alpha = .5f))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${entry.window.start.format(DateTimeFormatter.ofPattern("MMM d"))} – ${entry.window.end.format(DateTimeFormatter.ofPattern("MMM d"))}"
                            )
                            Text(
                                "${iMoney(entry.spent)} of ${iMoney(entry.planned)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                if (entry.remaining >= 0) "+${iMoney(entry.remaining)}" else "-${iMoney(abs(entry.remaining))}",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End,
                                color = if (entry.remaining >= 0) BillNestColors.positive else BillNestColors.danger,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        item { Text("Net worth history", style = MaterialTheme.typography.titleLarge) }
        if (netWorth.isEmpty()) {
            item {
                InsightsCard {
                    Text("Net worth history starts building as BillNest captures account and debt snapshots.")
                }
            }
        }
        items(netWorth.reversed(), key = { it.month.toString() }) { point ->
            InsightsCard {
                Text(point.month.format(DateTimeFormatter.ofPattern("MMM yyyy")), style = MaterialTheme.typography.titleMedium)
                Text(
                    "Assets ${iMoney(point.assets)} • Debt ${iMoney(point.debts)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = BillNestColors.border.copy(alpha = .6f))
                Text("Net worth", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                Text(
                    iMoney(point.netWorth),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (point.netWorth >= 0) BillNestColors.positive else BillNestColors.danger,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }

    selectedCategory?.let { category ->
        val rows = remember(data, month, category) { categorySpendingBreakdown(data, month, category) }
        val total = rows.sumOf { it.amount }
        AlertDialog(
            onDismissRequest = { selectedCategory = null },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(category)
                    Text(
                        month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Category spending", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(iMoney(total), style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider(color = BillNestColors.border)
                    if (rows.isEmpty()) {
                        Text("No matching transactions found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 460.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(rows, key = { item -> item.transaction.id + "-" + category }) { item ->
                                val tx = item.transaction
                                val displayName = tx.effectiveDisplayName()
                                val account = tx.accountId?.let { id -> data.accounts.firstOrNull { it.id == id } }
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = BillNestColors.cardRaised),
                                    border = BorderStroke(1.dp, BillNestColors.border.copy(alpha = .7f))
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(displayName, style = MaterialTheme.typography.titleSmall)
                                                if (displayName != tx.name) {
                                                    Text(
                                                        "Bank: ${tx.name}",
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                                Text(
                                                    buildString {
                                                        append(tx.dateIso)
                                                        account?.let {
                                                            append(" • ")
                                                            append(it.name)
                                                        }
                                                    },
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                            Text(
                                                iMoney(item.amount),
                                                style = MaterialTheme.typography.titleMedium,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                        if (item.fromSplit) {
                                            Text(
                                                "Split portion of " + iMoney(kotlin.math.abs(tx.amount)),
                                                color = BillNestColors.info,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        val merchantKey = subscriptionKey(displayName)
                                        val trackedSubscription = data.subscriptionPreferences.any {
                                            it.merchantKey == merchantKey && it.status == SubscriptionStatus.CONFIRMED
                                        }
                                        insightTransactionActionRows(!tx.income && !tx.transfer).forEach { actionRow ->
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                actionRow.forEach { action ->
                                                    TextButton(
                                                        modifier = Modifier.weight(1f),
                                                        onClick = {
                                                            when (action) {
                                                                InsightTransactionAction.EDIT -> {
                                                                    reopenCategory = category
                                                                    editingTransaction = tx
                                                                    selectedCategory = null
                                                                }
                                                                InsightTransactionAction.SUBSCRIPTION -> {
                                                                    if (trackedSubscription) {
                                                                        vm.deleteSubscriptionPreference(merchantKey)
                                                                    } else {
                                                                        vm.saveSubscriptionPreference(
                                                                            SubscriptionPreference(merchantKey, displayName, SubscriptionStatus.CONFIRMED)
                                                                        )
                                                                    }
                                                                }
                                                                InsightTransactionAction.RULE -> {
                                                                    reopenCategory = category
                                                                    ruleFor = tx
                                                                    selectedCategory = null
                                                                }
                                                                InsightTransactionAction.DELETE -> vm.deleteTransaction(tx.id)
                                                            }
                                                        }
                                                    ) {
                                                        Text(
                                                            when (action) {
                                                                InsightTransactionAction.EDIT -> "Edit"
                                                                InsightTransactionAction.SUBSCRIPTION -> if (trackedSubscription) "Untrack sub" else "Track sub"
                                                                InsightTransactionAction.RULE -> "Make rule"
                                                                InsightTransactionAction.DELETE -> "Delete"
                                                            },
                                                            color = if (action == InsightTransactionAction.DELETE) MaterialTheme.colorScheme.error else LocalContentColor.current,
                                                            maxLines = 1,
                                                            softWrap = false
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedCategory = null }) { Text("Close") }
            }
        )
    }

    editingTransaction?.let { transaction ->
        TransactionEditorDialog(
            data = data,
            existing = transaction,
            onDismiss = {
                editingTransaction = null
                selectedCategory = reopenCategory
                reopenCategory = null
            },
            onSave = {
                vm.saveTransaction(it)
                editingTransaction = null
                selectedCategory = reopenCategory
                reopenCategory = null
            }
        )
    }

    ruleFor?.let { transaction ->
        SmartRuleEditorDialog(
            data = data,
            sourceTransaction = transaction,
            onDismiss = {
                ruleFor = null
                selectedCategory = reopenCategory
                reopenCategory = null
            },
            onSave = {
                vm.saveSmartTransactionRule(it)
                ruleFor = null
                selectedCategory = reopenCategory
                reopenCategory = null
            }
        )
    }
}

@Composable
private fun InsightAmountLine(label: String, amount: Double, amountColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            iMoney(amount),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.titleMedium,
            color = amountColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

private fun signedInsightsMoney(value: Double): String = when {
    value > 0.005 -> "+${iMoney(value)}"
    value < -0.005 -> "-${iMoney(abs(value))}"
    else -> iMoney(0.0)
}
