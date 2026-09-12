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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel
import com.baylee.billnest.ui.theme.BillNestColors
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val alphaMoney = NumberFormat.getCurrencyInstance()
private fun aMoney(value: Double): String = alphaMoney.format(value)
private fun aDate(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
}.getOrDefault(value)

@Composable
private fun AlphaCard(content: @Composable ColumnScope.() -> Unit) {
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
private fun AlphaTag(text: String, color: Color = BillNestColors.accent) {
    Surface(
        color = color.copy(alpha = .13f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, color.copy(alpha = .28f))
    ) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AlphaProgress(value: Double, color: Color = BillNestColors.accent) {
    LinearProgressIndicator(
        progress = { value.coerceIn(0.0, 1.0).toFloat() },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        color = color,
        trackColor = BillNestColors.cardStrong
    )
}

@Composable
fun DashboardV3(data: AppData, modifier: Modifier = Modifier) {
    val money = remember(data) { calculateMoneySummary(data) }
    val variable = remember(data) { runCatching { calculateVariableSpendingSummary(data) }.getOrNull() }
    val alerts = remember(data) { runCatching { budgetAlerts(data) }.getOrDefault(emptyList()) }
    val plan = remember(data) { runCatching { calculateNextPaycheckPlan(data) }.getOrNull() }
    val recap = remember(data) { monthlyRecap(data, YearMonth.now()) }
    val netWorth = remember(data) { netWorthHistory(data).lastOrNull()?.netWorth ?: (money.totalMoney - data.debts.sumOf { it.balance.coerceAtLeast(0.0) }) }
    val assetCount = remember(data.accounts) { visibleAssetAccounts(data.accounts).size }

    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Your money", style = MaterialTheme.typography.headlineSmall)
                Text("A live household view of what is spendable, protected, owed, and planned.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            AlphaCard {
                Text("Available after obligations", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                Text(aMoney(money.availableAfterUpcomingBills), style = MaterialTheme.typography.headlineMedium,
                    color = if (money.availableAfterUpcomingBills >= 0) BillNestColors.positive else BillNestColors.danger)
                Text("Spending ${aMoney(money.spendingMoney)} • Reserved ${aMoney(money.reservedFromSpending)} • Upcoming bills ${aMoney(money.upcomingBills)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlphaMetricCard("Savings", aMoney(money.savings), BillNestColors.positive, Modifier.weight(1f))
                AlphaMetricCard("Retirement", aMoney(money.retirement), BillNestColors.info, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlphaMetricCard("Debt", aMoney(data.debts.sumOf { it.balance.coerceAtLeast(0.0) }), BillNestColors.danger, Modifier.weight(1f))
                AlphaMetricCard("Net worth", aMoney(netWorth), if (netWorth >= 0) BillNestColors.positive else BillNestColors.danger, Modifier.weight(1f))
            }
        }
        item {
            Text("$assetCount asset account${if (assetCount == 1) "" else "s"} • Credit cards live under Debt", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        variable?.let { summary ->
            item {
                AlphaCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Variable budgets", style = MaterialTheme.typography.titleMedium)
                            Text("${aMoney(summary.budgetedSpent)} spent of ${aMoney(summary.planned)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AlphaTag(summary.pace.name.replace('_', ' '), when (summary.pace) {
                            BudgetPace.OVER -> BillNestColors.danger
                            BudgetPace.WARNING -> BillNestColors.warning
                            BudgetPace.UNDER -> BillNestColors.positive
                            else -> BillNestColors.accent
                        })
                    }
                    if (summary.planned > 0) AlphaProgress(summary.budgetedSpent / summary.planned,
                        if (summary.pace == BudgetPace.OVER) BillNestColors.danger else if (summary.pace == BudgetPace.WARNING) BillNestColors.warning else BillNestColors.accent)
                    Text("${aMoney(summary.remaining)} remaining • ${aMoney(summary.unbudgetedSpent)} unbudgeted", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        plan?.let { p ->
            item {
                AlphaCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Next paycheck plan", style = MaterialTheme.typography.titleMedium)
                            Text("${p.paydayLabel} • ${aDate(p.dateIso)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(aMoney(p.unassigned), style = MaterialTheme.typography.titleLarge,
                            color = if (p.unassigned >= 0) BillNestColors.positive else BillNestColors.danger)
                    }
                    Text("Unassigned after bills, reserves, savings, debt minimums, and variable budgets.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            AlphaCard {
                Text("This month", style = MaterialTheme.typography.titleMedium)
                Text("Variable spending ${aMoney(recap.spending)} • Income ${aMoney(recap.income)}")
                val deltaText = if (recap.spendingDelta >= 0) "+${aMoney(recap.spendingDelta)}" else "-${aMoney(abs(recap.spendingDelta))}"
                Text("$deltaText vs last month", color = if (recap.spendingDelta <= 0) BillNestColors.positive else BillNestColors.warning)
                recap.topCategory?.let { Text("Biggest category: $it (${aMoney(recap.topCategoryAmount)})", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (alerts.isNotEmpty()) {
            item { Text("Needs attention", style = MaterialTheme.typography.titleLarge) }
            items(alerts.take(3), key = { it.budgetId }) { alert ->
                AlphaCard {
                    Text(alert.budgetName, style = MaterialTheme.typography.titleMedium)
                    Text(alert.message, color = if (alert.pace == BudgetPace.OVER) BillNestColors.danger else BillNestColors.warning)
                }
            }
        }
        val upcoming = data.bills.filterNot { it.isPaidFor() }.sortedBy { it.dueDate() }.take(4)
        if (upcoming.isNotEmpty()) {
            item { Text("Upcoming bills", style = MaterialTheme.typography.titleLarge) }
            items(upcoming, key = { it.id }) { bill ->
                AlphaCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(bill.name, style = MaterialTheme.typography.titleMedium)
                            Text("Due ${aDate(bill.dueDateIso)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(aMoney(bill.amount), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlphaMetricCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = BillNestColors.card), border = BorderStroke(1.dp, BillNestColors.border)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleLarge, color = accent)
        }
    }
}

@Composable
fun TransactionsPageV3(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FinanceTransaction?>(null) }
    var ruleFor by remember { mutableStateOf<FinanceTransaction?>(null) }
    var newRule by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("All") }
    var expanded by remember { mutableStateOf(false) }

    val rows = data.transactions.filter { row ->
        val inferredIncome = row.income || row.category.equals("Income", true) ||
            (row.source == TransactionSource.PLAID && row.amount < 0.0 && !row.userClassificationOverride)
        (query.isBlank() || row.name.contains(query, true) || row.category.contains(query, true)) && when (typeFilter) {
            "Income" -> inferredIncome && !row.transfer
            "Transfers" -> row.transfer
            "Spending" -> !inferredIncome && !row.transfer
            "Excluded" -> row.excludedFromSpending
            else -> true
        }
    }.sortedByDescending { it.dateIso }

    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Transactions", style = MaterialTheme.typography.headlineSmall)
                    Text("Classify once, then let rules keep future syncs clean.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button({ adding = true }) { Text("+ Add") }
            }
        }
        item {
            AlphaCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Smart rules", style = MaterialTheme.typography.titleMedium)
                        Text("${data.transactionRules.size} saved rule${if (data.transactionRules.size == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton({ newRule = true }) { Text("+ Rule") }
                }
                data.transactionRules.take(4).forEach { rule ->
                    HorizontalDivider(color = BillNestColors.border.copy(alpha = .5f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Contains “${rule.merchantContains}”", style = MaterialTheme.typography.bodyMedium)
                            val actions = buildList {
                                rule.renameTo?.takeIf { it.isNotBlank() }?.let { add("rename → $it") }
                                rule.category?.takeIf { it.isNotBlank() }?.let { add("category → $it") }
                                if (rule.excludeFromSpending) add("excluded from spending")
                            }.joinToString(" • ")
                            if (actions.isNotBlank()) Text(actions, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton({ vm.deleteTransactionRule(rule.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (data.transactionRules.size > 4) Text("+ ${data.transactionRules.size - 4} more rules", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            OutlinedTextField(query, { query = it }, label = { Text("Search transactions") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        item {
            Box {
                OutlinedButton({ expanded = true }) { Text("Show: $typeFilter") }
                DropdownMenu(expanded, { expanded = false }) {
                    listOf("All", "Spending", "Income", "Transfers", "Excluded").forEach { choice ->
                        DropdownMenuItem({ Text(choice) }, { typeFilter = choice; expanded = false })
                    }
                }
            }
        }
        if (rows.isEmpty()) item { AlphaCard { Text("No transactions match this view.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(rows, key = { it.id }) { row ->
            val inferredIncome = row.income || row.category.equals("Income", true) ||
                (row.source == TransactionSource.PLAID && row.amount < 0.0 && !row.userClassificationOverride)
            val from = row.transferFromAccountId?.let { id -> data.accounts.firstOrNull { it.id == id }?.name }
            val to = row.transferToAccountId?.let { id -> data.accounts.firstOrNull { it.id == id }?.name }
            val kind = when {
                row.transfer -> if (from != null || to != null) "Transfer • ${from ?: "Unknown"} → ${to ?: "Unknown"}" else "Transfer"
                inferredIncome -> "Income"
                else -> row.category
            }
            val amountText = when {
                inferredIncome && !row.transfer -> "+${aMoney(abs(row.amount))}"
                row.transfer -> aMoney(abs(row.amount))
                else -> aMoney(abs(row.amount))
            }
            AlphaCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(row.name, style = MaterialTheme.typography.titleMedium)
                        Text("$kind • ${aDate(row.dateIso)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (row.excludedFromSpending) AlphaTag("Excluded", BillNestColors.warning)
                            if (row.userClassificationOverride) AlphaTag("Manual", BillNestColors.info)
                            if (row.source == TransactionSource.PLAID) AlphaTag("Bank", BillNestColors.accent)
                        }
                    }
                    Text(amountText, style = MaterialTheme.typography.titleMedium,
                        color = if (inferredIncome && !row.transfer) BillNestColors.positive else if (row.transfer) BillNestColors.info else MaterialTheme.colorScheme.onSurface)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ editing = row }) { Text("Edit") }
                    TextButton({ ruleFor = row }) { Text("Make rule") }
                    TextButton({ vm.deleteTransaction(row.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (adding || editing != null) {
        TransactionEditorDialog(data, editing, { adding = false; editing = null }) {
            vm.saveTransaction(it)
            adding = false
            editing = null
        }
    }
    if (newRule || ruleFor != null) {
        TransactionRuleDialog(ruleFor, { newRule = false; ruleFor = null }) {
            vm.saveTransactionRule(it)
            newRule = false
            ruleFor = null
        }
    }
}

@Composable
private fun TransactionRuleDialog(source: FinanceTransaction?, onDismiss: () -> Unit, onSave: (TransactionRule) -> Unit) {
    var merchant by remember(source?.id) { mutableStateOf(source?.name.orEmpty()) }
    var rename by remember(source?.id) { mutableStateOf("") }
    var category by remember(source?.id) { mutableStateOf(source?.category?.takeUnless { it.equals("Other", true) }.orEmpty()) }
    var exclude by remember(source?.id) { mutableStateOf(source?.excludedFromSpending ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transaction rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Future bank transactions matching this merchant text will use this rule. Existing transactions are recalculated too.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant contains") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(rename, { rename = it }, label = { Text("Rename merchant to (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(category, { category = it }, label = { Text("Category (optional)") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Exclude from spending")
                        Text("Keeps it out of budgets and spending recaps.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(exclude, { exclude = it })
                }
            }
        },
        confirmButton = {
            Button({
                if (merchant.isNotBlank()) onSave(TransactionRule(
                    merchantContains = merchant.trim(),
                    renameTo = rename.trim().ifBlank { null },
                    category = category.trim().ifBlank { null },
                    excludeFromSpending = exclude
                ))
            }) { Text("Save rule") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun DebtPageV3(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Debt?>(null) }
    var extraPayment by remember { mutableStateOf("") }
    val extra = extraPayment.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val payments = remember(data) { detectDebtPayments(data) }
    val totalDebt = data.debts.sumOf { it.balance.coerceAtLeast(0.0) }
    val cards = data.debts.filter { it.type == DebtType.CREDIT_CARD && it.creditLimit > 0 }
    val totalLimit = cards.sumOf { it.creditLimit }
    val used = cards.sumOf { it.balance.coerceAtLeast(0.0) }
    val utilization = if (totalLimit > 0) used / totalLimit else 0.0
    val snowball = calculateDebtStrategy(data.debts, extra, DebtStrategy.SNOWBALL)
    val avalanche = calculateDebtStrategy(data.debts, extra, DebtStrategy.AVALANCHE)

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Debt", style = MaterialTheme.typography.headlineSmall)
                    Text("Cards and loans live here, separate from your asset accounts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button({ adding = true }) { Text("+ Debt") }
            }
        }
        item {
            AlphaCard {
                Text("Total debt", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(aMoney(totalDebt), style = MaterialTheme.typography.headlineMedium, color = BillNestColors.danger)
                if (cards.isNotEmpty()) {
                    Text("Credit utilization ${(utilization * 100).toInt()}% • ${aMoney(used)} of ${aMoney(totalLimit)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AlphaProgress(utilization, if (utilization >= .5) BillNestColors.warning else BillNestColors.accent)
                }
            }
        }
        if (data.debts.isNotEmpty()) {
            item {
                AlphaCard {
                    Text("Payoff comparison", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(extraPayment, { extraPayment = it }, label = { Text("Extra monthly payment") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DebtStrategyMini("Snowball", snowball, Modifier.weight(1f))
                        DebtStrategyMini("Avalanche", avalanche, Modifier.weight(1f))
                    }
                    val savings = snowball.totalInterest - avalanche.totalInterest
                    if (savings > .01) Text("Avalanche saves about ${aMoney(savings)} in projected interest.", color = BillNestColors.positive)
                }
            }
        }
        if (payments.isNotEmpty()) {
            item {
                AlphaCard {
                    Text("Detected payments this month", style = MaterialTheme.typography.titleMedium)
                    Text(aMoney(payments.sumOf { it.amount }), style = MaterialTheme.typography.titleLarge, color = BillNestColors.positive)
                    Text("Matched from transfers and payment transactions. Review matches before relying on them for payoff decisions.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (data.debts.isEmpty()) item { AlphaCard { Text("No debts yet. Add a credit card, loan, mortgage, or other debt.") } }
        items(data.debts, key = { it.id }) { debt ->
            val debtPayments = payments.filter { it.debtId == debt.id }
            val linked = !debt.plaidAccountId.isNullOrBlank()
            AlphaCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(debt.name, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AlphaTag(debt.type.name.replace('_', ' '), BillNestColors.danger)
                            if (linked) AlphaTag("Linked balance", BillNestColors.info) else AlphaTag("Manual", BillNestColors.accent)
                        }
                    }
                    Text(aMoney(debt.balance), style = MaterialTheme.typography.titleLarge)
                }
                Text("${debt.apr}% APR • minimum ${aMoney(debt.minimumPayment)} • due ${nextDebtDueDate(debt).format(DateTimeFormatter.ofPattern("MMM d"))}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (debt.type == DebtType.CREDIT_CARD && debt.creditLimit > 0) {
                    val u = (debt.balance / debt.creditLimit).coerceIn(0.0, 1.0)
                    AlphaProgress(u, if (u >= .5) BillNestColors.warning else BillNestColors.accent)
                    Text("${(u * 100).toInt()}% of ${aMoney(debt.creditLimit)} limit", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (debtPayments.isNotEmpty()) {
                    Text("Detected this month: ${aMoney(debtPayments.sumOf { it.amount })}", color = BillNestColors.positive)
                    debtPayments.take(2).forEach { payment ->
                        val tx = data.transactions.firstOrNull { it.id == payment.transactionId }
                        Text("${tx?.name ?: "Payment"} • ${aDate(payment.dateIso)} • ${aMoney(payment.amount)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ editing = debt }) { Text("Edit") }
                    TextButton({ vm.deleteDebt(debt.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (adding || editing != null) {
        DebtEditorV3(data, editing, { adding = false; editing = null }) {
            vm.saveDebt(it)
            adding = false
            editing = null
        }
    }
}

@Composable
private fun DebtStrategyMini(label: String, projection: DebtStrategyProjection, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = BillNestColors.cardRaised)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (projection.payoffPossible) {
                Text("${projection.months} mo", style = MaterialTheme.typography.titleMedium)
                Text("${aMoney(projection.totalInterest)} interest", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else Text("Payment too low", color = BillNestColors.warning)
        }
    }
}

@Composable
private fun DebtEditorV3(data: AppData, existing: Debt?, onDismiss: () -> Unit, onSave: (Debt) -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var balance by remember(existing?.id) { mutableStateOf(existing?.balance?.toString().orEmpty()) }
    var apr by remember(existing?.id) { mutableStateOf(existing?.apr?.toString().orEmpty()) }
    var minimum by remember(existing?.id) { mutableStateOf(existing?.minimumPayment?.toString().orEmpty()) }
    var creditLimit by remember(existing?.id) { mutableStateOf(existing?.creditLimit?.takeIf { it > 0 }?.toString().orEmpty()) }
    var dueDay by remember(existing?.id) { mutableStateOf((existing?.dueDay ?: 1).toString()) }
    var type by remember(existing?.id) { mutableStateOf(existing?.type ?: DebtType.CREDIT_CARD) }
    var plaidAccountId by remember(existing?.id) { mutableStateOf(existing?.plaidAccountId.orEmpty()) }
    var typeExpanded by remember { mutableStateOf(false) }
    var cardExpanded by remember { mutableStateOf(false) }
    val linkedCards = data.accounts.filter { it.source == AccountSource.PLAID && it.type == AccountType.CREDIT && !it.plaidAccountId.isNullOrBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add debt" else "Edit debt") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Debt name") }, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedButton({ typeExpanded = true }) { Text("Type: ${type.name.replace('_', ' ')}") }
                    DropdownMenu(typeExpanded, { typeExpanded = false }) {
                        DebtType.entries.forEach { choice -> DropdownMenuItem({ Text(choice.name.replace('_', ' ')) }, { type = choice; typeExpanded = false }) }
                    }
                }
                OutlinedTextField(balance, { balance = it }, label = { Text("Current balance") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apr, { apr = it }, label = { Text("APR percent") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(minimum, { minimum = it }, label = { Text("Minimum monthly payment") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dueDay, { dueDay = it.filter(Char::isDigit).take(2) }, label = { Text("Due day of month") }, modifier = Modifier.fillMaxWidth())
                if (type == DebtType.CREDIT_CARD) {
                    OutlinedTextField(creditLimit, { creditLimit = it }, label = { Text("Credit limit") }, modifier = Modifier.fillMaxWidth())
                    if (linkedCards.isNotEmpty()) Box {
                        OutlinedButton({ cardExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Linked bank card: ${linkedCards.firstOrNull { it.plaidAccountId == plaidAccountId }?.name ?: "None"}")
                        }
                        DropdownMenu(cardExpanded, { cardExpanded = false }) {
                            DropdownMenuItem({ Text("None") }, { plaidAccountId = ""; cardExpanded = false })
                            linkedCards.forEach { account -> DropdownMenuItem({ Text(account.name) }, { plaidAccountId = account.plaidAccountId.orEmpty(); if (name.isBlank()) name = account.name; cardExpanded = false }) }
                        }
                    }
                    Text("For linked cards, bank refreshes update the live balance. Your APR, minimum, due day, and edited credit limit stay under BillNest control.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button({
                val parsed = balance.toDoubleOrNull() ?: return@Button
                onSave(Debt(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name.ifBlank { "Debt" },
                    type = type,
                    balance = parsed.coerceAtLeast(0.0),
                    apr = apr.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                    minimumPayment = minimum.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                    dueDay = dueDay.toIntOrNull()?.coerceIn(1, 31) ?: 1,
                    creditLimit = if (type == DebtType.CREDIT_CARD) creditLimit.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0 else 0.0,
                    plaidAccountId = plaidAccountId.ifBlank { null }
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun InsightsPageV3(data: AppData, modifier: Modifier = Modifier) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val recap = remember(data, month) { monthlyRecap(data, month) }
    val alerts = remember(data) { runCatching { budgetAlerts(data) }.getOrDefault(emptyList()) }
    val netWorth = remember(data) { netWorthHistory(data) }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text("Insights", style = MaterialTheme.typography.headlineSmall)
            Text("Recaps and trends from your real household data.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            AlphaCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ month = month.minusMonths(1) }) { Text("‹") }
                    Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleLarge)
                    TextButton({ if (month < YearMonth.now()) month = month.plusMonths(1) }, enabled = month < YearMonth.now()) { Text("›") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Variable spending")
                    Text(aMoney(recap.spending), style = MaterialTheme.typography.titleMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Income")
                    Text(aMoney(recap.income), style = MaterialTheme.typography.titleMedium, color = BillNestColors.positive)
                }
                val delta = recap.spendingDelta
                Text(
                    if (delta <= 0) "You spent ${aMoney(abs(delta))} less than the prior month." else "You spent ${aMoney(delta)} more than the prior month.",
                    color = if (delta <= 0) BillNestColors.positive else BillNestColors.warning
                )
                recap.topCategory?.let { Text("Top category: $it • ${aMoney(recap.topCategoryAmount)}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (recap.categoryTotals.isNotEmpty()) {
            item { Text("Spending by category", style = MaterialTheme.typography.titleLarge) }
            items(recap.categoryTotals.toList(), key = { it.first }) { (category, amount) ->
                AlphaCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(category)
                        Text(aMoney(amount), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        item { Text("Budget alerts", style = MaterialTheme.typography.titleLarge) }
        if (alerts.isEmpty()) item { AlphaCard { Text("No current budget alerts. Your active budgets are within their warning rules.", color = BillNestColors.positive) } }
        items(alerts, key = { it.budgetId }) { alert ->
            AlphaCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(alert.budgetName, style = MaterialTheme.typography.titleMedium)
                    AlphaTag(alert.pace.name.replace('_', ' '), if (alert.pace == BudgetPace.OVER) BillNestColors.danger else BillNestColors.warning)
                }
                Text(alert.message)
                AlphaProgress(alert.percentUsed, if (alert.pace == BudgetPace.OVER) BillNestColors.danger else BillNestColors.warning)
            }
        }
        if (data.budgets.isNotEmpty()) {
            item { Text("Budget history", style = MaterialTheme.typography.titleLarge) }
            items(data.budgets, key = { "history-${it.id}" }) { budget ->
                val history = remember(data, budget) { runCatching { budgetHistory(data, budget, 4) }.getOrDefault(emptyList()) }
                AlphaCard {
                    Text(budget.name, style = MaterialTheme.typography.titleMedium)
                    history.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(color = BillNestColors.border.copy(alpha = .5f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("${entry.window.start.format(DateTimeFormatter.ofPattern("MMM d"))} – ${entry.window.end.format(DateTimeFormatter.ofPattern("MMM d"))}")
                                Text("${aMoney(entry.spent)} of ${aMoney(entry.planned)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(if (entry.remaining >= 0) "+${aMoney(entry.remaining)}" else "-${aMoney(abs(entry.remaining))}", color = if (entry.remaining >= 0) BillNestColors.positive else BillNestColors.danger)
                        }
                    }
                }
            }
        }
        item { Text("Net worth history", style = MaterialTheme.typography.titleLarge) }
        if (netWorth.isEmpty()) item { AlphaCard { Text("Net worth history starts building as BillNest captures account and debt snapshots.") } }
        items(netWorth.reversed(), key = { it.month.toString() }) { point ->
            AlphaCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(point.month.format(DateTimeFormatter.ofPattern("MMM yyyy")), style = MaterialTheme.typography.titleMedium)
                        Text("Assets ${aMoney(point.assets)} • Debt ${aMoney(point.debts)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(aMoney(point.netWorth), style = MaterialTheme.typography.titleLarge, color = if (point.netWorth >= 0) BillNestColors.positive else BillNestColors.danger)
                }
            }
        }
    }
}

@Composable
fun PaycheckPlanPageV3(data: AppData, modifier: Modifier = Modifier) {
    val plan = remember(data) { runCatching { calculateNextPaycheckPlan(data) }.getOrNull() }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text("Paycheck Plan", style = MaterialTheme.typography.headlineSmall)
            Text("See what your next paycheck needs to cover before you spend what is left.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (plan == null) {
            item { AlphaCard { Text("Add a future payday under Income to build your next-paycheck plan.") } }
        } else {
            item {
                AlphaCard {
                    Text(plan.paydayLabel, style = MaterialTheme.typography.titleLarge)
                    Text(aDate(plan.dateIso), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(aMoney(plan.income), style = MaterialTheme.typography.headlineMedium, color = BillNestColors.positive)
                }
            }
            item {
                AlphaCard {
                    Text("Planned allocations", style = MaterialTheme.typography.titleMedium)
                    PlanLine("Bills before next paycheck", plan.bills)
                    PlanLine("Reserve contributions", plan.reserves)
                    PlanLine("Savings contributions", plan.savings)
                    PlanLine("Debt minimums", plan.debtMinimums)
                    PlanLine("Variable budgets", plan.variableBudgets)
                    HorizontalDivider(color = BillNestColors.border)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Unassigned", style = MaterialTheme.typography.titleMedium)
                        Text(aMoney(plan.unassigned), style = MaterialTheme.typography.titleLarge, color = if (plan.unassigned >= 0) BillNestColors.positive else BillNestColors.danger)
                    }
                    Text(
                        if (plan.unassigned >= 0) "This is what remains after the plan above." else "Your planned obligations exceed this paycheck by ${aMoney(abs(plan.unassigned))}.",
                        color = if (plan.unassigned >= 0) MaterialTheme.colorScheme.onSurfaceVariant else BillNestColors.danger
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanLine(label: String, amount: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(aMoney(amount))
    }
}

@Composable
fun SettingsPageV3(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var backendUrl by remember(data.backendUrl) { mutableStateOf(data.backendUrl) }
    var backendApiKey by remember(data.backendApiKey) { mutableStateOf(data.backendApiKey) }
    val options = listOf(7, 3, 1, 0)
    val linkedBanks = data.accounts.count { it.source == AccountSource.PLAID }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
        item {
            AlphaCard {
                Text("Bank connection", style = MaterialTheme.typography.titleMedium)
                Text("$linkedBanks linked bank account${if (linkedBanks == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(backendUrl, { backendUrl = it }, label = { Text("Backend address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(backendApiKey, { backendApiKey = it }, label = { Text("Bank server key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button({ vm.backendUrl(backendUrl); vm.backendApiKey(backendApiKey) }) { Text("Save bank connection") }
                Text("Plaid credentials remain on the Cloudflare backend and are not embedded in the APK.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            AlphaCard {
                Text("Bill reminders", style = MaterialTheme.typography.titleMedium)
                options.forEach { day ->
                    val checked = day in data.reminderDays
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (day == 0) "On due date" else "$day day${if (day == 1) "" else "s"} before")
                        Switch(checked, { enabled -> vm.reminderDays(if (enabled) data.reminderDays + day else data.reminderDays - day) })
                    }
                }
            }
        }
        item {
            AlphaCard {
                Text("Data & household sync", style = MaterialTheme.typography.titleMedium)
                Text("Local finance data is encrypted with Android Keystore. Household finance records, transaction rules, deletion tombstones, and financial snapshots use the same authoritative household sync stream.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Text("BillNest ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
