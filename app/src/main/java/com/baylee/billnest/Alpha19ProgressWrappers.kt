package com.baylee.billnest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel
import com.baylee.billnest.ui.theme.BillNestColors
import java.text.NumberFormat
import java.time.YearMonth
import kotlin.math.abs

private val progressMoney = NumberFormat.getCurrencyInstance()
private fun progressMoney(value: Double) = progressMoney.format(value)

@Composable
private fun ProgressStrip(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = BillNestColors.cardRaised),
        border = BorderStroke(1.dp, BillNestColors.border)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** Adds the APR-based payment estimate above the existing full Debt experience. */
@Composable
fun DebtPageV4(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    val payments = remember(data) { detectDebtPayments(data) }
    val splits = remember(data, payments) {
        data.debts.mapNotNull { debt ->
            val paid = payments.filter { it.debtId == debt.id }.sumOf { it.amount }
            if (paid <= 0.0) null else debt to estimateDebtPaymentBreakdown(debt, paid)
        }
    }
    Column(modifier.fillMaxSize()) {
        if (splits.isNotEmpty()) {
            val totalPayments = splits.sumOf { it.second.paymentAmount }
            val totalPrincipal = splits.sumOf { it.second.estimatedPrincipal }
            val totalInterest = splits.sumOf { it.second.estimatedInterest }
            ProgressStrip {
                Text("Detected payment estimate", style = MaterialTheme.typography.titleMedium)
                Text("${progressMoney(totalPayments)} paid • ~${progressMoney(totalPrincipal)} principal • ~${progressMoney(totalInterest)} interest",
                    color = BillNestColors.positive)
                Text("Estimated from current balance and APR; your lender statement is authoritative.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                splits.take(3).forEach { (debt, split) ->
                    Text("${debt.name}: ~${progressMoney(split.estimatedPrincipal)} principal / ~${progressMoney(split.estimatedInterest)} interest",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        DebtPageV3(data, vm, Modifier.weight(1f))
    }
}

/** Adds progress deltas and biggest spending increase above the full Insights page. */
@Composable
fun InsightsPageV4(data: AppData, modifier: Modifier = Modifier) {
    val recap = remember(data) { monthlyRecap(data, YearMonth.now()) }
    val hasProgress = recap.largestIncreaseCategory != null || recap.assetChange != null || recap.debtReduction != null || recap.netWorthChange != null
    Column(modifier.fillMaxSize()) {
        if (hasProgress) {
            ProgressStrip {
                Text("Monthly progress", style = MaterialTheme.typography.titleMedium)
                recap.largestIncreaseCategory?.let { category ->
                    Text("Biggest spending increase: $category +${progressMoney(recap.largestIncreaseAmount)}", color = BillNestColors.warning)
                }
                recap.assetChange?.let { change ->
                    Text("Assets ${signedMoney(change)}", color = if (change >= 0) BillNestColors.positive else BillNestColors.warning)
                }
                recap.debtReduction?.let { change ->
                    Text("Debt ${if (change >= 0) "down" else "up"} ${progressMoney(abs(change))}", color = if (change >= 0) BillNestColors.positive else BillNestColors.danger)
                }
                recap.netWorthChange?.let { change ->
                    Text("Net worth ${signedMoney(change)}", color = if (change >= 0) BillNestColors.positive else BillNestColors.danger)
                }
                if (recap.assetChange == null) {
                    Text("Asset, debt, and net-worth month-over-month progress appears after BillNest has snapshots in two months.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        InsightsPageV3(data, Modifier.weight(1f))
    }
}

private fun signedMoney(value: Double): String = when {
    value > 0.005 -> "+${progressMoney(value)}"
    value < -0.005 -> "-${progressMoney(abs(value))}"
    else -> progressMoney(0.0)
}
