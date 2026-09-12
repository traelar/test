package com.baylee.billnest

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
    val rows = data.transactions.filter { query.isBlank() || it.name.contains(query, true) || it.category.contains(query, true) }.sortedByDescending { it.dateIso }
    FinanceList(modifier, "Transactions", "Add transaction", { editor = true }) {
        item { OutlinedTextField(query, { query = it }, label = { Text("Search transactions") }, modifier = Modifier.fillMaxWidth()) }
        if (rows.isEmpty()) item { Text("No transactions yet. Manual entries will appear here; connected-bank transaction sync is the next data integration step.") }
        items(rows, key = { it.id }) { row -> FinanceRow(row.name, currencyV2(if (row.transfer) 0.0 else row.amount), row.category + if (row.transfer) " • Transfer" else "") { vm.deleteTransaction(row.id) } }
    }
    if (editor) SimpleFinanceEditor(EditorKind.TRANSACTION, data, { editor = false }) { name, amount, option ->
        vm.saveTransaction(FinanceTransaction(name = name, amount = amount, dateIso = LocalDate.now().toString(), category = option.ifBlank { "Other" }))
        editor = false
    }
}

@Composable
fun BudgetsPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var editor by remember { mutableStateOf(false) }
    FinanceList(modifier, "Budgets", "Add budget", { editor = true }) {
        if (data.budgets.isEmpty()) item { Text("No budgets yet. Create weekly, biweekly, monthly, yearly, or custom budgets.") }
        items(data.budgets, key = { it.id }) { row -> FinanceRow(row.name, currencyV2(row.amount), row.period.name.replace('_', ' ')) { vm.deleteBudget(row.id) } }
    }
    if (editor) SimpleFinanceEditor(EditorKind.BUDGET, data, { editor = false }) { name, amount, option ->
        vm.saveBudget(Budget(name = name, amount = amount, category = option.ifBlank { "Other" }))
        editor = false
    }
}

@Composable
fun DebtPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var editor by remember { mutableStateOf(false) }
    FinanceList(modifier, "Debt", "Add debt", { editor = true }) {
        item { Text("Total debt: ${currencyV2(data.debts.sumOf { it.balance })}", style = MaterialTheme.typography.titleLarge) }
        if (data.debts.isEmpty()) item { Text("No debts yet. Add a credit card, loan, mortgage, or other debt.") }
        items(data.debts, key = { it.id }) { row -> FinanceRow(row.name, currencyV2(row.balance), "${row.type.name.replace('_', ' ')} • ${row.apr}% APR") { vm.deleteDebt(row.id) } }
    }
    if (editor) SimpleFinanceEditor(EditorKind.DEBT, data, { editor = false }) { name, amount, option ->
        val type = runCatching { DebtType.valueOf(option) }.getOrDefault(DebtType.OTHER)
        vm.saveDebt(Debt(name = name, type = type, balance = amount))
        editor = false
    }
}

@Composable
fun SavingsGoalsPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var editor by remember { mutableStateOf(false) }
    FinanceList(modifier, "Savings / Goals", "Add goal", { editor = true }) {
        if (data.savingsGoals.isEmpty()) item { Text("No savings goals yet.") }
        items(data.savingsGoals, key = { it.id }) { row -> FinanceRow(row.name, "${currencyV2(row.savedAmount)} / ${currencyV2(row.targetAmount)}", row.targetDateIso?.let { "Target $it" } ?: "No target date") { vm.deleteGoal(row.id) } }
    }
    if (editor) SimpleFinanceEditor(EditorKind.GOAL, data, { editor = false }) { name, amount, _ ->
        vm.saveGoal(SavingsGoal(name = name, targetAmount = amount)); editor = false
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
                Text(data.accounts.firstOrNull { it.id == row.accountId }?.name ?: "No account linked")
                Row { TextButton({ vm.fundReserved(row.id, 25.0) }) { Text("Add $25") }; TextButton({ vm.fundReserved(row.id, -25.0) }) { Text("Release $25") }; TextButton({ vm.deleteReservedFund(row.id) }) { Text("Delete") } }
            } }
        }
    }
    if (editor) SimpleFinanceEditor(EditorKind.RESERVE, data, { editor = false }) { name, amount, option ->
        vm.saveReservedFund(ReservedFund(name = name, amount = amount, accountId = option.ifBlank { null })); editor = false
    }
}

@Composable private fun FinanceList(modifier: Modifier, title: String, action: String, onAdd: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, style = MaterialTheme.typography.headlineSmall); Button(onAdd) { Text(action) } } }
        content()
    }
}

@Composable private fun FinanceRow(title: String, amount: String, subtitle: String, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle) }
        Column { Text(amount, style = MaterialTheme.typography.titleMedium); TextButton(onDelete) { Text("Delete") } }
    } }
}

@Composable private fun SimpleFinanceEditor(kind: EditorKind, data: AppData, onDismiss: () -> Unit, onSave: (String, Double, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }; var option by remember { mutableStateOf("") }; var expanded by remember { mutableStateOf(false) }
    val choices = when (kind) { EditorKind.DEBT -> DebtType.entries.map { it.name }; EditorKind.RESERVE -> data.accounts.map { it.id }; else -> emptyList() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add ${kind.name.lowercase().replaceFirstChar { it.uppercase() }}") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }); OutlinedTextField(amount, { amount = it }, label = { Text(if (kind == EditorKind.DEBT) "Current balance" else "Amount") })
        if (kind == EditorKind.TRANSACTION || kind == EditorKind.BUDGET) OutlinedTextField(option, { option = it }, label = { Text("Category") })
        if (choices.isNotEmpty()) Box { OutlinedButton({ expanded = true }) { Text(if (kind == EditorKind.DEBT) "Type: ${option.ifBlank { "OTHER" }}" else "Account: ${data.accounts.firstOrNull { it.id == option }?.name ?: "None"}") }; DropdownMenu(expanded, { expanded = false }) { choices.forEach { choice -> DropdownMenuItem({ Text(if (kind == EditorKind.RESERVE) data.accounts.firstOrNull { it.id == choice }?.name ?: choice else choice.replace('_', ' ')) }, { option = choice; expanded = false }) } } }
    } }, confirmButton = { Button({ amount.toDoubleOrNull()?.takeIf { it >= 0 }?.let { onSave(name.ifBlank { kind.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, it, option) } }) { Text("Save") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

private fun currencyV2(value: Double): String = java.text.NumberFormat.getCurrencyInstance().format(value)
