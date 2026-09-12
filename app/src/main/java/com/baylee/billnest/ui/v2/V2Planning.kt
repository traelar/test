package com.baylee.billnest.ui.v2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baylee.billnest.DatePickerButton
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

@Composable
internal fun BudgetsScreen(data: AppData, vm: MainViewModel, modifier: Modifier) {
    var editing by remember { mutableStateOf<Budget?>(null) }
    var creating by remember { mutableStateOf(false) }
    val transactions = data.plaidTransactions + data.manualTransactions
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Button(onClick = { creating = true }) { Text("+ Budget") } }
        if (data.budgets.isEmpty()) item { Text("Create budgets by category for weekly, biweekly, monthly, yearly, or custom date ranges.") }
        items(data.budgets, key = { it.id }) { budget ->
            val spent = budgetSpent(budget, transactions)
            val remaining = budget.amount - spent
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(budget.name, style = MaterialTheme.typography.titleMedium)
                    Text("${budget.category} • ${periodLabel(budget.period)}")
                    LinearProgressIndicator(
                        progress = { if (budget.amount <= 0.0) 0f else (spent / budget.amount).coerceIn(0.0, 1.0).toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${money(spent)} spent • ${money(remaining)} remaining")
                    if (budget.period == BudgetPeriod.CUSTOM) Text("${budget.startDateIso} through ${budget.endDateIso ?: "open ended"}")
                    if (budget.rollover) Text("Rollover enabled", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { editing = budget }) { Text("Edit") }
                        TextButton(onClick = { vm.deleteBudget(budget.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
    if (creating) BudgetDialog(null, { creating = false }) { vm.addBudget(it); creating = false }
    editing?.let { budget -> BudgetDialog(budget, { editing = null }) { vm.updateBudget(it); editing = null } }
}

@Composable
private fun BudgetDialog(original: Budget?, onDismiss: () -> Unit, onSave: (Budget) -> Unit) {
    var name by remember { mutableStateOf(original?.name ?: "") }
    var category by remember { mutableStateOf(original?.category ?: "Groceries") }
    var amount by remember { mutableStateOf(original?.amount?.toString() ?: "") }
    var period by remember { mutableStateOf(original?.period ?: BudgetPeriod.MONTHLY) }
    var start by remember { mutableStateOf(original?.startDateIso ?: LocalDate.now().toString()) }
    var end by remember { mutableStateOf(original?.endDateIso ?: LocalDate.now().plusMonths(1).toString()) }
    var rollover by remember { mutableStateOf(original?.rollover ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "Add budget" else "Edit budget") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 540.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Budget name") }) }
                item { OutlinedTextField(category, { category = it }, label = { Text("Category") }) }
                item { OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }) }
                item { SimpleEnumPicker("Period", period, BudgetPeriod.entries) { period = it } }
                if (period == BudgetPeriod.CUSTOM) {
                    item { DatePickerButton("Start date", start) { start = it } }
                    item { DatePickerButton("End date", end) { end = it } }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Rollover unused amount")
                        Switch(rollover, { rollover = it })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                amount.toDoubleOrNull()?.let { parsed ->
                    onSave((original ?: Budget(name = "", category = "Other", amount = parsed)).copy(
                        name = name.ifBlank { category.ifBlank { "Budget" } },
                        category = category.ifBlank { "Other" },
                        amount = parsed,
                        period = period,
                        startDateIso = start,
                        endDateIso = if (period == BudgetPeriod.CUSTOM) end else null,
                        rollover = rollover
                    ))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun DebtScreen(data: AppData, vm: MainViewModel, modifier: Modifier) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Debt?>(null) }
    var strategy by remember { mutableStateOf(DebtPayoffStrategy.AVALANCHE) }
    var extraText by remember { mutableStateOf("0") }
    val extra = extraText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val priority = DebtPlanner.prioritize(data.debts, strategy)
    val avalanche = DebtPlanner.estimate(data.debts, DebtPayoffStrategy.AVALANCHE, extra)
    val snowball = DebtPlanner.estimate(data.debts, DebtPayoffStrategy.SNOWBALL, extra)

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = strategy == DebtPayoffStrategy.AVALANCHE, onClick = { strategy = DebtPayoffStrategy.AVALANCHE }, label = { Text("Avalanche") })
                FilterChip(selected = strategy == DebtPayoffStrategy.SNOWBALL, onClick = { strategy = DebtPayoffStrategy.SNOWBALL }, label = { Text("Snowball") })
                Spacer(Modifier.weight(1f))
                Button(onClick = { creating = true }) { Text("+ Debt") }
            }
        }
        item { Text("Total debt: ${money(data.debts.filter { it.active }.sumOf { it.balance })}", style = MaterialTheme.typography.titleLarge) }
        item { OutlinedTextField(extraText, { extraText = it }, label = { Text("Extra monthly debt payment") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        if (data.debts.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayoffCard("Avalanche", avalanche, Modifier.weight(1f))
                    PayoffCard("Snowball", snowball, Modifier.weight(1f))
                }
            }
            item { Text("Payoff priority", style = MaterialTheme.typography.titleLarge) }
        }
        if (priority.isEmpty()) item { Text("Add credit cards, loans, or a mortgage manually. BillNest never creates a mortgage or balance for you.") }
        items(priority, key = { it.id }) { debt ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(debt.name, style = MaterialTheme.typography.titleMedium)
                    Text("${debtTypeLabel(debt.type)} • ${money(debt.balance)}")
                    Text("APR ${"%.2f".format(debt.aprPercent)}% • Minimum ${money(debt.minimumPayment)}")
                    Row {
                        TextButton(onClick = { editing = debt }) { Text("Edit") }
                        TextButton(onClick = { vm.deleteDebt(debt.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
    if (creating) DebtDialog(data, null, { creating = false }) { vm.addDebt(it); creating = false }
    editing?.let { debt -> DebtDialog(data, debt, { editing = null }) { vm.updateDebt(it); editing = null } }
}

@Composable
private fun PayoffCard(label: String, estimate: DebtPayoffEstimate, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (estimate.debtFreeDateIso == null) {
                Text("Add minimum payments to estimate payoff", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${estimate.months} months")
                Text("Interest ${money(estimate.interestPaid)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DebtDialog(data: AppData, original: Debt?, onDismiss: () -> Unit, onSave: (Debt) -> Unit) {
    var name by remember { mutableStateOf(original?.name ?: "") }
    var balance by remember { mutableStateOf(original?.balance?.toString() ?: "") }
    var apr by remember { mutableStateOf(original?.aprPercent?.toString() ?: "") }
    var minimum by remember { mutableStateOf(original?.minimumPayment?.toString() ?: "") }
    var dueDay by remember { mutableStateOf(original?.dueDay?.toString() ?: "") }
    var notes by remember { mutableStateOf(original?.notes ?: "") }
    var type by remember { mutableStateOf(original?.type ?: DebtType.CREDIT_CARD) }
    var accountKey by remember { mutableStateOf(original?.linkedAccountKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "Add debt" else "Edit debt") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 540.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Name") }) }
                item { SimpleEnumPicker("Type", type, DebtType.entries) { type = it } }
                item { OutlinedTextField(balance, { balance = it }, label = { Text("Current balance") }) }
                item { OutlinedTextField(apr, { apr = it }, label = { Text("APR %") }) }
                item { OutlinedTextField(minimum, { minimum = it }, label = { Text("Minimum payment") }) }
                item { OutlinedTextField(dueDay, { dueDay = it }, label = { Text("Due day of month (optional)") }) }
                item { SimpleAccountPicker(data, accountKey, "Linked account (optional)") { accountKey = it } }
                item { OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }) }
            }
        },
        confirmButton = {
            Button(onClick = {
                balance.toDoubleOrNull()?.let { parsed ->
                    onSave((original ?: Debt(name = "", balance = parsed)).copy(
                        name = name.ifBlank { debtTypeLabel(type) },
                        type = type,
                        balance = parsed,
                        aprPercent = apr.toDoubleOrNull() ?: 0.0,
                        minimumPayment = minimum.toDoubleOrNull() ?: 0.0,
                        dueDay = dueDay.toIntOrNull()?.coerceIn(1, 31),
                        linkedAccountKey = accountKey,
                        notes = notes
                    ))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun GoalsScreen(data: AppData, vm: MainViewModel, modifier: Modifier) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavingsGoal?>(null) }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Button(onClick = { creating = true }) { Text("+ Savings goal") } }
        if (data.savingsGoals.isEmpty()) item { Text("Create goals for emergency savings, vacation, Christmas, repairs, or anything else.") }
        items(data.savingsGoals, key = { it.id }) { goal ->
            val progress = if (goal.targetAmount <= 0.0) 0f else (goal.currentAmount / goal.targetAmount).coerceIn(0.0, 1.0).toFloat()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(goal.name, style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${money(goal.currentAmount)} of ${money(goal.targetAmount)}")
                    if (goal.contributionPerPaycheck > 0) Text("${money(goal.contributionPerPaycheck)} per paycheck")
                    goal.targetDateIso?.let { Text("Target $it") }
                    Row {
                        TextButton(onClick = { editing = goal }) { Text("Edit") }
                        TextButton(onClick = { vm.deleteGoal(goal.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
    if (creating) GoalDialog(data, null, { creating = false }) { vm.addGoal(it); creating = false }
    editing?.let { goal -> GoalDialog(data, goal, { editing = null }) { vm.updateGoal(it); editing = null } }
}

@Composable
private fun GoalDialog(data: AppData, original: SavingsGoal?, onDismiss: () -> Unit, onSave: (SavingsGoal) -> Unit) {
    var name by remember { mutableStateOf(original?.name ?: "") }
    var target by remember { mutableStateOf(original?.targetAmount?.toString() ?: "") }
    var current by remember { mutableStateOf(original?.currentAmount?.toString() ?: "") }
    var perCheck by remember { mutableStateOf(original?.contributionPerPaycheck?.toString() ?: "") }
    var targetDate by remember { mutableStateOf(original?.targetDateIso ?: LocalDate.now().plusMonths(6).toString()) }
    var hasTargetDate by remember { mutableStateOf(original?.targetDateIso != null) }
    var accountKey by remember { mutableStateOf(original?.linkedAccountKey) }
    var paydayId by remember { mutableStateOf(original?.linkedPaydayId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "Add savings goal" else "Edit savings goal") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 520.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Goal name") }) }
                item { OutlinedTextField(target, { target = it }, label = { Text("Target amount") }) }
                item { OutlinedTextField(current, { current = it }, label = { Text("Current amount") }) }
                item { OutlinedTextField(perCheck, { perCheck = it }, label = { Text("Contribution per paycheck") }) }
                item { SimpleAccountPicker(data, accountKey, "Linked account") { accountKey = it } }
                item { SimpleStringPicker("Linked payday", paydayId, data.paydays.map { it.id to it.label }) { paydayId = it } }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Use target date"); Switch(hasTargetDate, { hasTargetDate = it }) } }
                if (hasTargetDate) item { DatePickerButton("Target date", targetDate) { targetDate = it } }
            }
        },
        confirmButton = {
            Button(onClick = {
                target.toDoubleOrNull()?.let { parsed ->
                    onSave((original ?: SavingsGoal(name = "", targetAmount = parsed)).copy(
                        name = name.ifBlank { "Savings goal" },
                        targetAmount = parsed,
                        currentAmount = current.toDoubleOrNull() ?: 0.0,
                        targetDateIso = if (hasTargetDate) targetDate else null,
                        linkedAccountKey = accountKey,
                        contributionPerPaycheck = perCheck.toDoubleOrNull() ?: 0.0,
                        linkedPaydayId = paydayId
                    ))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun ReservedFundsScreen(data: AppData, vm: MainViewModel, modifier: Modifier) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ReservedFund?>(null) }
    val cash = CashPosition.calculate(data.accounts, data.accountPreferences, data.reservedFunds)
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("Reserved Money", style = MaterialTheme.typography.labelLarge); Text(money(cash.reservedMoney), style = MaterialTheme.typography.headlineMedium) }
                Button(onClick = { creating = true }) { Text("+ Reserve") }
            }
        }
        item { Text("Reserved funds stay in Total Money, but BillNest keeps them out of free spending or free savings where appropriate.") }
        if (data.reservedFunds.isEmpty()) item { Text("No reserves yet. Add one manually for a mortgage, insurance, taxes, repairs, or anything else.") }
        items(data.reservedFunds, key = { it.id }) { fund ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(fund.name, style = MaterialTheme.typography.titleMedium)
                    Text("Reserved: ${money(fund.reservedAmount)}")
                    if (fund.contributionPerPaycheck > 0) Text("Add ${money(fund.contributionPerPaycheck)} per paycheck")
                    fund.targetAmount?.let { Text("Target ${money(it)}") }
                    data.accounts.firstOrNull { AccountFinance.stableKey(it) == fund.accountKey }?.let { account ->
                        Text("Account: ${AccountFinance.displayName(account, data.accountPreferences, data.accounts.indexOf(account).coerceAtLeast(0))}")
                    }
                    Row {
                        TextButton(onClick = { editing = fund }) { Text("Edit") }
                        TextButton(onClick = { vm.deleteReservedFund(fund.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
    if (creating) ReservedFundDialog(data, null, { creating = false }) { vm.addReservedFund(it); creating = false }
    editing?.let { fund -> ReservedFundDialog(data, fund, { editing = null }) { vm.updateReservedFund(it); editing = null } }
}

@Composable
private fun ReservedFundDialog(data: AppData, original: ReservedFund?, onDismiss: () -> Unit, onSave: (ReservedFund) -> Unit) {
    var name by remember { mutableStateOf(original?.name ?: "") }
    var reserved by remember { mutableStateOf(original?.reservedAmount?.toString() ?: "") }
    var perCheck by remember { mutableStateOf(original?.contributionPerPaycheck?.toString() ?: "") }
    var target by remember { mutableStateOf(original?.targetAmount?.toString() ?: "") }
    var notes by remember { mutableStateOf(original?.notes ?: "") }
    var accountKey by remember { mutableStateOf(original?.accountKey) }
    var linkedBill by remember { mutableStateOf(original?.linkedBillId) }
    var linkedPayday by remember { mutableStateOf(original?.linkedPaydayId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "Add reserved fund" else "Edit reserved fund") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 540.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Reserve name") }) }
                item { SimpleAccountPicker(data, accountKey, "Account") { accountKey = it } }
                item { OutlinedTextField(reserved, { reserved = it }, label = { Text("Amount currently reserved") }) }
                item { OutlinedTextField(perCheck, { perCheck = it }, label = { Text("Contribution per paycheck") }) }
                item { OutlinedTextField(target, { target = it }, label = { Text("Target amount (optional)") }) }
                item { SimpleStringPicker("Linked bill (optional)", linkedBill, data.bills.map { it.id to it.name }) { linkedBill = it } }
                item { SimpleStringPicker("Linked payday (optional)", linkedPayday, data.paydays.map { it.id to it.label }) { linkedPayday = it } }
                item { OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val key = accountKey
                val amount = reserved.toDoubleOrNull()
                if (key != null && amount != null) {
                    onSave((original ?: ReservedFund(name = "", accountKey = key)).copy(
                        name = name.ifBlank { "Reserved fund" },
                        accountKey = key,
                        reservedAmount = amount,
                        contributionPerPaycheck = perCheck.toDoubleOrNull() ?: 0.0,
                        targetAmount = target.toDoubleOrNull(),
                        linkedBillId = linkedBill,
                        linkedPaydayId = linkedPayday,
                        notes = notes
                    ))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun budgetSpent(budget: Budget, transactions: List<FinanceTransaction>): Double {
    val today = LocalDate.now()
    val start = when (budget.period) {
        BudgetPeriod.WEEKLY -> today.minusDays((today.dayOfWeek.value - 1).toLong())
        BudgetPeriod.BIWEEKLY -> runCatching { LocalDate.parse(budget.startDateIso) }.getOrDefault(today).let { anchor ->
            anchor.plusWeeks((ChronoUnit.WEEKS.between(anchor, today).coerceAtLeast(0) / 2) * 2)
        }
        BudgetPeriod.MONTHLY -> YearMonth.from(today).atDay(1)
        BudgetPeriod.YEARLY -> LocalDate.of(today.year, 1, 1)
        BudgetPeriod.CUSTOM -> runCatching { LocalDate.parse(budget.startDateIso) }.getOrDefault(today)
    }
    val end = when (budget.period) {
        BudgetPeriod.WEEKLY -> start.plusDays(6)
        BudgetPeriod.BIWEEKLY -> start.plusDays(13)
        BudgetPeriod.MONTHLY -> YearMonth.from(start).atEndOfMonth()
        BudgetPeriod.YEARLY -> LocalDate.of(start.year, 12, 31)
        BudgetPeriod.CUSTOM -> budget.endDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
    }
    return transactions.filter { tx ->
        if (tx.type != TransactionType.EXPENSE || tx.excludedFromSpending || !tx.category.equals(budget.category, true)) return@filter false
        val date = runCatching { LocalDate.parse(tx.dateIso) }.getOrNull() ?: return@filter false
        !date.isBefore(start) && !date.isAfter(end)
    }.sumOf { it.amount }
}

private fun periodLabel(period: BudgetPeriod): String = period.name.lowercase().replaceFirstChar { it.uppercase() }
