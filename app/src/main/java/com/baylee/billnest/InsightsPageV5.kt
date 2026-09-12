package com.baylee.billnest

import androidx.compose.foundation.BorderStroke
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
import com.baylee.billnest.ui.insightsBudgetEmptyState
import com.baylee.billnest.ui.theme.BillNestColors
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val insightsMoney = NumberFormat.getCurrencyInstance()
private fun iMoney(value: Double): String = insightsMoney.format(value)

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
fun InsightsPageV5(data: AppData, modifier: Modifier = Modifier) {
    var month by remember { mutableStateOf(YearMonth.now()) }
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
                InsightsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(category, style = MaterialTheme.typography.titleMedium)
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
