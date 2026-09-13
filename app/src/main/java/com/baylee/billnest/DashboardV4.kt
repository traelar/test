package com.baylee.billnest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.theme.BillNestColors
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val dashboardMoney = NumberFormat.getCurrencyInstance()
private fun dashboardMoney(value: Double): String = dashboardMoney.format(value)
private fun dashboardDate(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
}.getOrDefault(value)

@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
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
private fun DashboardTag(text: String, color: Color = BillNestColors.accent) {
    Surface(
        color = color.copy(alpha = .13f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, color.copy(alpha = .28f))
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun DashboardProgress(value: Double, color: Color = BillNestColors.accent) {
    LinearProgressIndicator(
        progress = { value.coerceIn(0.0, 1.0).toFloat() },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        color = color,
        trackColor = BillNestColors.cardStrong
    )
}

@Composable
private fun DashboardMetric(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = BillNestColors.card),
        border = BorderStroke(1.dp, BillNestColors.border)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleLarge, color = accent)
        }
    }
}

/** Dashboard revision that keeps current cash separate from future-paycheck planning. */
@Composable
fun DashboardV4(data: AppData, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val money = remember(data, today) { calculateMoneySummary(data, today) }
    val cashProjection = remember(data, today) { calculateMonthlyCashProjection(data, today) }
    val variable = remember(data) { runCatching { calculateVariableSpendingSummary(data) }.getOrNull() }
    val alerts = remember(data) { runCatching { budgetAlerts(data) }.getOrDefault(emptyList()) }
    val plan = remember(data) { runCatching { calculateNextPaycheckPlan(data) }.getOrNull() }
    val recap = remember(data) { monthlyRecap(data, YearMonth.now()) }
    val netWorth = remember(data) {
        netWorthHistory(data).lastOrNull()?.netWorth
            ?: (money.totalMoney - data.debts.sumOf { it.balance.coerceAtLeast(0.0) })
    }
    val assetCount = remember(data.accounts) { visibleAssetAccounts(data.accounts).size }
    val reconciledBills = remember(data.bills, data.transactions) {
        data.bills.map { reconcileBillPaymentState(it, data.transactions) }
    }

    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Your money", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "A live household view of what is spendable, protected, owed, and planned.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            DashboardCard {
                Text(
                    "Spending money now",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    dashboardMoney(cashProjection.currentSpendable),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (cashProjection.currentSpendable >= 0) BillNestColors.positive else BillNestColors.danger,
                    maxLines = 1,
                    softWrap = false
                )
                if (money.reservedFromSpending > 0.0) {
                    Text(
                        "${dashboardMoney(money.reservedFromSpending)} is protected in reserved funds.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                HorizontalDivider(color = BillNestColors.border.copy(alpha = .65f))
                Text("Projected through month end", style = MaterialTheme.typography.titleMedium)
                if (cashProjection.futurePaychecks > 0.0) {
                    Text(
                        "Expected paychecks still coming  +${dashboardMoney(cashProjection.futurePaychecks)}",
                        color = BillNestColors.positive
                    )
                } else {
                    Text(
                        "No more scheduled paychecks this month",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "Remaining unpaid bills  -${dashboardMoney(cashProjection.remainingBills)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Projected month-end available",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    dashboardMoney(cashProjection.projectedMonthEndAvailable),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (cashProjection.projectedMonthEndAvailable >= 0) BillNestColors.positive else BillNestColors.danger,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardMetric("Savings", dashboardMoney(money.savings), BillNestColors.positive, Modifier.weight(1f))
                DashboardMetric("Retirement", dashboardMoney(money.retirement), BillNestColors.info, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardMetric(
                    "Debt",
                    dashboardMoney(data.debts.sumOf { it.balance.coerceAtLeast(0.0) }),
                    BillNestColors.danger,
                    Modifier.weight(1f)
                )
                DashboardMetric(
                    "Net worth",
                    dashboardMoney(netWorth),
                    if (netWorth >= 0) BillNestColors.positive else BillNestColors.danger,
                    Modifier.weight(1f)
                )
            }
        }
        item {
            Text(
                "$assetCount asset account${if (assetCount == 1) "" else "s"} • Credit cards live under Debt",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        variable?.let { summary ->
            item {
                DashboardCard {
                    if (data.budgets.isEmpty()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Variable budgets", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${dashboardMoney(summary.unbudgetedSpent)} variable spending this month",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DashboardTag("NOT SET UP", BillNestColors.info)
                        }
                        Text(
                            "Set up variable budgets when you're ready to track spending limits by category, merchant, or paycheck.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Variable budgets", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${dashboardMoney(summary.budgetedSpent)} spent of ${dashboardMoney(summary.planned)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DashboardTag(
                                summary.pace.name.replace('_', ' '),
                                when (summary.pace) {
                                    BudgetPace.OVER -> BillNestColors.danger
                                    BudgetPace.WARNING -> BillNestColors.warning
                                    BudgetPace.UNDER -> BillNestColors.positive
                                    else -> BillNestColors.accent
                                }
                            )
                        }
                        if (summary.planned > 0) {
                            DashboardProgress(
                                summary.budgetedSpent / summary.planned,
                                if (summary.pace == BudgetPace.OVER) BillNestColors.danger
                                else if (summary.pace == BudgetPace.WARNING) BillNestColors.warning
                                else BillNestColors.accent
                            )
                        }
                        Text(
                            "${dashboardMoney(summary.remaining)} remaining • ${dashboardMoney(summary.unbudgetedSpent)} unbudgeted",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        plan?.let { p ->
            item {
                DashboardCard {
                    Text("Next paycheck plan", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${p.paydayLabel} • ${dashboardDate(p.dateIso)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Unassigned",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        dashboardMoney(p.unassigned),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (p.unassigned >= 0) BillNestColors.positive else BillNestColors.danger,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        "After bills, reserves, savings, debt minimums, and variable budgets.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            DashboardCard {
                Text("This month", style = MaterialTheme.typography.titleMedium)
                Text("Variable spending ${dashboardMoney(recap.spending)} • Income ${dashboardMoney(recap.income)}")
                val deltaText = if (recap.spendingDelta >= 0) {
                    "+${dashboardMoney(recap.spendingDelta)}"
                } else {
                    "-${dashboardMoney(abs(recap.spendingDelta))}"
                }
                Text(
                    "$deltaText vs last month",
                    color = if (recap.spendingDelta <= 0) BillNestColors.positive else BillNestColors.warning
                )
                recap.topCategory?.let {
                    Text(
                        "Biggest category: $it (${dashboardMoney(recap.topCategoryAmount)})",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (alerts.isNotEmpty()) {
            item { Text("Needs attention", style = MaterialTheme.typography.titleLarge) }
            items(alerts.take(3), key = { it.budgetId }) { alert ->
                DashboardCard {
                    Text(alert.budgetName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        alert.message,
                        color = if (alert.pace == BudgetPace.OVER) BillNestColors.danger else BillNestColors.warning
                    )
                }
            }
        }

        val upcoming = reconciledBills.filterNot { it.isPaidFor() }.sortedBy { it.dueDate() }.take(4)
        if (upcoming.isNotEmpty()) {
            item { Text("Upcoming bills", style = MaterialTheme.typography.titleLarge) }
            items(upcoming, key = { it.id }) { bill ->
                DashboardCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(bill.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Due ${dashboardDate(bill.dueDateIso)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            dashboardMoney(bill.amount),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
