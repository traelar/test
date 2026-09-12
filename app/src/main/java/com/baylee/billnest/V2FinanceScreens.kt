package com.baylee.billnest

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel
import java.time.LocalDate

private enum class EditorKind { TRANSACTION, BUDGET, DEBT, GOAL, RESERVE }

@Composable
fun TransactionsPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var editor by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("All") }
    var filterExpanded by remember { mutableStateOf(false) }
    val rows = data.transactions.filter {
        (query.isBlank() || it.name.contains(query, true) || it.category.contains(query, true)) && when (typeFilter) {
            "Income" -> it.income
            "Transfers" -> it.transfer
            "Spending" -> !it.income && !it.transfer
            else -> true
        }
    }.sortedByDescending { it.dateIso }
    FinanceList(modifier, "Transactions", "Add transaction", { editor = true }) {
        item { OutlinedTextField(query, { query = it }, label = { Text("Search transactions") }, modifier = Modifier.fillMaxWidth()) }
        item { Box { OutlinedButton(onClick = { filterExpanded = true }) { Text("Show: $typeFilter") }; DropdownMenu(filterExpanded, { filterExpanded = false }) { listOf("All", "Spending", "Income", "Transfers").forEach { choice -> DropdownMenuItem({ Text(choice) }, { typeFilter = choice; filterExpanded = false }) } } } }
        if (rows.isEmpty()) item { Text("No transactions yet. Add one manually or refresh a connected bank after transaction sync is enabled on the backend.") }
        items(rows, key = { it.id }) { row -> FinanceRow(row.name, currencyV2(if (row.transfer) 0.0 else row.amount), row.category + when { row.transfer -> " • Transfer"; row.income -> " • Income"; else -> "" }) { vm.deleteTransaction(row.id) } }
    }
    if (editor) SimpleFinanceEditor(EditorKind.TRANSACTION, data, { editor = false }) { name, amount, option, income, _, transfer ->
        vm.saveTransaction(FinanceTransaction(name = name, amount = amount, dateIso = LocalDate.now().toString(), category = option.ifBlank { if (income) "Income" else "Other" }, income = income, transfer = transfer))
        editor = false
    }
}

@Composable
fun BudgetsPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var editor by remember { mutableStateOf(false) }
    FinanceList(modifier, "Budgets", "Add budget", { editor = true }) {
        if (data.budgets.isEmpty()) item { Text("No budgets yet. Create weekly, biweekly, monthly, yearly, or custom budgets.") }
        items(data.budgets, key = { it.id }) { row ->
            val spent = calculateBudgetSpent(row, data.transactions)
            FinanceRow(row.name, "${currencyV2(spent)} / ${currencyV2(row.amount)}", "${row.period.name.replace('_', ' ')} • ${currencyV2((row.amount - spent).coerceAtLeast(0.0))} remaining") { vm.deleteBudget(row.id) }
        }
    }
    if (editor) BudgetEditorDialog({ editor = false }) { vm.saveBudget(it); editor = false }
}

@Composable
fun DebtPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var editor by remember { mutableStateOf(false) }
    var extraPayment by remember { mutableStateOf("") }
    val extra = extraPayment.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val snowball = calculateDebtStrategy(data.debts, extra, DebtStrategy.SNOWBALL)
    val avalanche = calculateDebtStrategy(data.debts, extra, DebtStrategy.AVALANCHE)
    FinanceList(modifier, "Debt", "Add debt", { editor = true }) {
        item { Text("Total debt: ${currencyV2(data.debts.sumOf { it.balance })}", style = MaterialTheme.typography.titleLarge) }
        if (data.debts.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Payoff comparison", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(extraPayment, { extraPayment = it }, label = { Text("Extra payment each month") }, modifier = Modifier.fillMaxWidth())
                Text(strategyText(snowball))
                Text(strategyText(avalanche))
                val savings = snowball.totalInterest - avalanche.totalInterest
                if (savings > 0.01) Text("Avalanche saves about ${currencyV2(savings)} in interest.")
            } }
        }
        if (data.debts.isEmpty()) item { Text("No debts yet. Add a credit card, loan, mortgage, or other debt.") }
        items(data.debts, key = { it.id }) { row -> FinanceRow(row.name, currencyV2(row.balance), "${row.type.name.replace('_', ' ')} • ${row.apr}% APR • ${currencyV2(row.minimumPayment)} minimum • due day ${row.dueDay}") { vm.deleteDebt(row.id) } }
    }
    if (editor) DebtEditorDialog({ editor = false }) { vm.saveDebt(it); editor = false }
}

@Composable
fun SavingsGoalsPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var editor by remember { mutableStateOf(false) }
    FinanceList(modifier, "Savings / Goals", "Add goal", { editor = true }) {
        if (data.savingsGoals.isEmpty()) item { Text("No savings goals yet.") }
        items(data.savingsGoals, key = { it.id }) { row -> FinanceRow(row.name, "${currencyV2(row.savedAmount)} / ${currencyV2(row.targetAmount)}", (row.targetDateIso?.let { "Target $it" } ?: "No target date") + if (row.paydayContribution > 0) " • ${currencyV2(row.paydayContribution)} each payday" else "") { vm.deleteGoal(row.id) } }
    }
    if (editor) SimpleFinanceEditor(EditorKind.GOAL, data, { editor = false }) { name, amount, _, _, contribution, _ ->
        vm.saveGoal(SavingsGoal(name = name, targetAmount = amount, paydayContribution = contribution)); editor = false
    }
}

@Composable
fun ReservedFundsPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var editor by remember { mutableStateOf(false) }
    FinanceList(modifier, "Reserved Funds", "Add reserve", { editor = true }) {
        item { Text("Reserved: ${currencyV2(data.reservedFunds.sumOf { it.amount })}", style = MaterialTheme.typography.titleLarge) }
        if (data.reservedFunds.isEmpty()) item { Text("Earmark money for bills or future expenses without removing it from Total Money.") }
        items(data.reservedFunds, key = { it.id }) { row ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium); Text(currencyV2(row.amount), style = MaterialTheme.typography.titleLarge)
                Text((data.accounts.firstOrNull { it.id == row.accountId }?.name ?: "No account linked") + if (row.paydayContribution > 0) " • ${currencyV2(row.paydayContribution)} each payday" else "")
                Row { TextButton({ vm.fundReserved(row.id, 25.0) }) { Text("Add $25") }; TextButton({ vm.fundReserved(row.id, -25.0) }) { Text("Release $25") }; TextButton({ vm.deleteReservedFund(row.id) }) { Text("Delete") } }
            } }
        }
    }
    if (editor) SimpleFinanceEditor(EditorKind.RESERVE, data, { editor = false }) { name, amount, option, _, contribution, _ ->
        vm.saveReservedFund(ReservedFund(name = name, amount = amount, accountId = option.ifBlank { null }, paydayContribution = contribution)); editor = false
    }
}

@Composable
fun SubscriptionsPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    val detected = detectSubscriptions(data.transactions)
    val subscriptions = visibleSubscriptionSuggestions(detected, data.subscriptionPreferences)
    val confirmed = data.subscriptionPreferences.filter { it.status == SubscriptionStatus.CONFIRMED }
    FinanceList(modifier, "Subscriptions", "", {}) {
        item { Text("Recurring charges are detected from transaction timing and amount patterns. Nothing is confirmed or added without your approval.") }
        if (confirmed.isNotEmpty()) item { Text("Confirmed", style = MaterialTheme.typography.titleMedium) }
        items(confirmed, key = { "confirmed-${it.merchantKey}" }) { row ->
            FinanceRow(row.name, "Confirmed", "Included in your subscription list") { vm.deleteSubscriptionPreference(row.merchantKey) }
        }
        if (subscriptions.isNotEmpty()) item { Text("Needs review", style = MaterialTheme.typography.titleMedium) }
        if (subscriptions.isEmpty() && confirmed.isEmpty()) item { Text("No recurring subscriptions detected yet. Three matching charges are needed.") }
        items(subscriptions, key = { "${it.name}-${it.frequency}" }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium)
                    Text("${currencyV2(row.typicalAmount)} • ${row.frequency.name.lowercase().replaceFirstChar { it.uppercase() }}")
                    Text("Detected from ${row.sampleCount} charges • last ${row.lastDateIso}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { vm.saveSubscriptionPreference(SubscriptionPreference(subscriptionKey(row.name), row.name, SubscriptionStatus.CONFIRMED)) }) { Text("Confirm") }
                        TextButton(onClick = { vm.saveSubscriptionPreference(SubscriptionPreference(subscriptionKey(row.name), row.name, SubscriptionStatus.IGNORED)) }) { Text("Ignore") }
                    }
                }
            }
        }
    }
}

@Composable private fun FinanceList(modifier: Modifier, title: String, action: String, onAdd: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, style = MaterialTheme.typography.headlineSmall); if (action.isNotBlank()) Button(onAdd) { Text(action) } } }
        content()
    }
}

@Composable private fun FinanceRow(title: String, amount: String, subtitle: String, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle) }
        Column { Text(amount, style = MaterialTheme.typography.titleMedium); TextButton(onDelete) { Text("Delete") } }
    } }
}

@Composable private fun SimpleFinanceEditor(kind: EditorKind, data: AppData, onDismiss: () -> Unit, onSave: (String, Double, String, Boolean, Double, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }; var option by remember { mutableStateOf("") }; var expanded by remember { mutableStateOf(false) }; var income by remember { mutableStateOf(false) }; var paydayContribution by remember { mutableStateOf("") }; var transfer by remember { mutableStateOf(false) }
    val choices = when (kind) { EditorKind.DEBT -> DebtType.entries.map { it.name }; EditorKind.RESERVE -> data.accounts.map { it.id }; else -> emptyList() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add ${kind.name.lowercase().replaceFirstChar { it.uppercase() }}") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }); OutlinedTextField(amount, { amount = it }, label = { Text(if (kind == EditorKind.DEBT) "Current balance" else "Amount") })
        if (kind == EditorKind.TRANSACTION || kind == EditorKind.BUDGET) OutlinedTextField(option, { option = it }, label = { Text("Category") })
        if (kind == EditorKind.TRANSACTION) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(income, { income = it; if (it) transfer = false }); Text("This is income / a paycheck") }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(transfer, { transfer = it; if (it) income = false }); Text("This is a transfer between accounts") }
        }
        if (kind == EditorKind.GOAL || kind == EditorKind.RESERVE) OutlinedTextField(paydayContribution, { paydayContribution = it }, label = { Text("Add automatically from each payday") })
        if (choices.isNotEmpty()) Box { OutlinedButton({ expanded = true }) { Text(if (kind == EditorKind.DEBT) "Type: ${option.ifBlank { "OTHER" }}" else "Account: ${data.accounts.firstOrNull { it.id == option }?.name ?: "None"}") }; DropdownMenu(expanded, { expanded = false }) { choices.forEach { choice -> DropdownMenuItem({ Text(if (kind == EditorKind.RESERVE) data.accounts.firstOrNull { it.id == choice }?.name ?: choice else choice.replace('_', ' ')) }, { option = choice; expanded = false }) } } }
    } }, confirmButton = { Button({ amount.toDoubleOrNull()?.takeIf { it >= 0 }?.let { onSave(name.ifBlank { kind.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, it, option, income, paydayContribution.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0, transfer) } }) { Text("Save") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
private fun DebtEditorDialog(onDismiss: () -> Unit, onSave: (Debt) -> Unit) {
    var name by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }
    var apr by remember { mutableStateOf("") }
    var minimum by remember { mutableStateOf("") }
    var dueDay by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(DebtType.CREDIT_CARD) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add debt") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Debt name") })
            Box { OutlinedButton(onClick = { expanded = true }) { Text("Type: ${type.name.replace('_', ' ')}") }; DropdownMenu(expanded, { expanded = false }) { DebtType.entries.forEach { choice -> DropdownMenuItem({ Text(choice.name.replace('_', ' ')) }, { type = choice; expanded = false }) } } }
            OutlinedTextField(balance, { balance = it }, label = { Text("Current balance") })
            OutlinedTextField(apr, { apr = it }, label = { Text("APR percent") })
            OutlinedTextField(minimum, { minimum = it }, label = { Text("Minimum monthly payment") })
            OutlinedTextField(dueDay, { dueDay = it }, label = { Text("Due day of month") })
        } },
        confirmButton = { Button(onClick = {
            balance.toDoubleOrNull()?.let { parsedBalance ->
                onSave(Debt(name = name.ifBlank { type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } }, type = type, balance = parsedBalance.coerceAtLeast(0.0), apr = (apr.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0), minimumPayment = (minimum.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0), dueDay = (dueDay.toIntOrNull() ?: 1).coerceIn(1, 31)))
            }
        }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun BudgetEditorDialog(onDismiss: () -> Unit, onSave: (Budget) -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var period by remember { mutableStateOf(BudgetPeriod.MONTHLY) }
    var rollover by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add budget") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Budget name") })
            OutlinedTextField(amount, { amount = it }, label = { Text("Spending limit") })
            OutlinedTextField(category, { category = it }, label = { Text("Transaction category") })
            Box { OutlinedButton(onClick = { expanded = true }) { Text("Period: ${period.name.replace('_', ' ')}") }; DropdownMenu(expanded, { expanded = false }) { BudgetPeriod.entries.forEach { choice -> DropdownMenuItem({ Text(choice.name.replace('_', ' ')) }, { period = choice; expanded = false }) } } }
            if (period == BudgetPeriod.CUSTOM) {
                OutlinedTextField(startDate, { startDate = it }, label = { Text("Start date (YYYY-MM-DD)") })
                OutlinedTextField(endDate, { endDate = it }, label = { Text("End date (YYYY-MM-DD)") })
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(rollover, { rollover = it }); Text("Roll unused money forward") }
        } },
        confirmButton = { Button(onClick = { amount.toDoubleOrNull()?.let { onSave(Budget(name = name.ifBlank { category.ifBlank { "Budget" } }, amount = it.coerceAtLeast(0.0), category = category.ifBlank { "Other" }, period = period, rollover = rollover, startDateIso = startDate.takeIf { value -> value.isNotBlank() }, endDateIso = endDate.takeIf { value -> value.isNotBlank() })) } }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

private fun strategyText(value: DebtStrategyProjection): String {
    val label = value.strategy.name.lowercase().replaceFirstChar { it.uppercase() }
    return if (value.payoffPossible) "$label: ${value.months} months • ${currencyV2(value.totalInterest)} interest" else "$label: payment is too low to produce a payoff"
}

private fun currencyV2(value: Double): String = java.text.NumberFormat.getCurrencyInstance().format(value)
