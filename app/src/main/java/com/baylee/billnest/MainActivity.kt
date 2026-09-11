package com.baylee.billnest

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel
import com.baylee.billnest.ui.theme.BillNestTheme
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class MainActivity : FragmentActivity() {
    private val vm by viewModels<MainViewModel> {
        val repo = (application as BillNestApp).repo
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo) as T
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BillNestTheme { BillNestHome(vm) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillNestHome(vm: MainViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var showAddBill by remember { mutableStateOf(false) }
    var showAddPayday by remember { mutableStateOf(false) }
    var editingBill by remember { mutableStateOf<Bill?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
    val titles = listOf("Home", "Bills", "Calendar", "Income", "Settings")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BillNest") },
                actions = {
                    when (tab) {
                        1 -> TextButton(onClick = { showAddBill = true }) { Text("+ Bill") }
                        3 -> TextButton(onClick = { showAddPayday = true }) { Text("+ Payday") }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, name ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(name) })
                }
            }
        }
    ) { pad ->
        when (tab) {
            0 -> Dashboard(data, vm, Modifier.padding(pad), onEdit = { editingBill = it })
            1 -> BillsPage(data, vm, Modifier.padding(pad), onEdit = { editingBill = it })
            2 -> CalendarPage(data, Modifier.padding(pad))
            3 -> IncomePage(data, vm, Modifier.padding(pad))
            else -> SettingsPage(data, vm, Modifier.padding(pad))
        }
    }
    if (showAddBill) BillEditorDialog(null, { showAddBill = false }) { vm.add(it); showAddBill = false }
    editingBill?.let { bill -> BillEditorDialog(bill, { editingBill = null }) { vm.updateBill(it); editingBill = null } }
    if (showAddPayday) PaydayDialog({ showAddPayday = false }) { vm.addPayday(it); showAddPayday = false }
}

@Composable
fun Dashboard(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier, onEdit: (Bill) -> Unit) {
    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val balance = data.balances.sumOf { it.available ?: it.current }.takeIf { data.balances.isNotEmpty() } ?: data.manualBalance
    val unpaid = data.bills.filter { !it.isPaidFor() }
    val overdue = unpaid.filter { it.dueDate().isBefore(today) }
    val due30 = unpaid.filter { !it.dueDate().isBefore(today) && ChronoUnit.DAYS.between(today, it.dueDate()) <= 30 }
    val thisMonth = unpaid.filter { YearMonth.from(it.dueDate()) == month }
    val incoming30 = data.paydays.filter {
        val d = it.nextDate()
        !d.isBefore(today) && ChronoUnit.DAYS.between(today, d) <= 30
    }.sumOf { it.amount }
    val due30Total = due30.sumOf { it.amount }
    val safe = balance - due30Total
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Money at a glance", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Available balance")
                    Text(currency(balance), style = MaterialTheme.typography.headlineMedium)
                    Text("Bills next 30 days: " + currency(due30Total))
                    Text("Expected income next 30 days: " + currency(incoming30))
                    Text("Safe after upcoming bills: " + currency(safe), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("This month", currency(thisMonth.sumOf { it.amount }), Modifier.weight(1f))
                StatCard("Overdue", overdue.size.toString(), Modifier.weight(1f))
            }
        }
        if (overdue.isNotEmpty()) {
            item { Text("Overdue", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error) }
            items(overdue.sortedBy { it.dueDate() }, key = { it.id }) { BillRow(it, vm, onEdit, true) }
        }
        item { Text("Next bills", style = MaterialTheme.typography.titleLarge) }
        val next = due30.sortedBy { it.dueDate() }.take(6)
        if (next.isEmpty()) item { Text("No upcoming bills in the next 30 days.") }
        items(next, key = { it.id }) { BillRow(it, vm, onEdit, false) }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) { Column(Modifier.padding(14.dp)) { Text(label, style = MaterialTheme.typography.labelLarge); Text(value, style = MaterialTheme.typography.titleLarge) } }
}

@Composable
fun BillsPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier, onEdit: (Bill) -> Unit) {
    var category by remember { mutableStateOf("All") }
    val categories = listOf("All") + BillCategories
    val visible = data.bills.filter { category == "All" || it.category == category }.sortedBy { it.dueDate() }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("All bills", style = MaterialTheme.typography.headlineSmall) }
        item {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text("Category: " + category) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    categories.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { category = c; expanded = false }) }
                }
            }
        }
        if (visible.isEmpty()) item { Text("No bills in this category.") }
        items(visible, key = { it.id }) { BillRow(it, vm, onEdit, !it.isPaidFor() && it.dueDate().isBefore(LocalDate.now())) }
    }
}

@Composable
fun BillRow(b: Bill, vm: MainViewModel, onEdit: (Bill) -> Unit, overdue: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(b.name, style = MaterialTheme.typography.titleMedium)
            Text(currency(b.amount) + " • " + b.category + if (b.variableAmount) " • Variable" else "")
            Text("Due " + prettyDate(b.dueDate()) + if (b.autopay) " • Autopay" else "")
            if (overdue) Text("OVERDUE", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            if (b.notes.isNotBlank()) Text(b.notes, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.paid(b.id) }) { Text("Paid") }
                TextButton(onClick = { onEdit(b) }) { Text("Edit") }
                TextButton(onClick = { vm.delete(b.id) }) { Text("Delete") }
            }
        }
    }
}

@Composable
fun CalendarPage(data: AppData, modifier: Modifier = Modifier) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val monthBills = data.bills.filter { YearMonth.from(it.dueDate()) == month }.sortedBy { it.dueDate() }
    val monthIncome = data.paydays.filter { YearMonth.from(it.nextDate()) == month }.sortedBy { it.nextDate() }
    val formatter = DateTimeFormatter.ofPattern("MMMM yyyy")
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                Text(month.format(formatter), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { month = month.plusMonths(1) }) { Text("›") }
            }
        }
        item { Text("Bills: " + currency(monthBills.sumOf { it.amount }) + " • Income: " + currency(monthIncome.sumOf { it.amount }), style = MaterialTheme.typography.titleMedium) }
        if (monthBills.isEmpty() && monthIncome.isEmpty()) item { Text("Nothing scheduled this month.") }
        items(monthBills, key = { "bill-" + it.id }) { b ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(prettyDate(b.dueDate()), style = MaterialTheme.typography.titleMedium); Text(b.name + " • " + currency(b.amount) + " • " + b.category) } }
        }
        items(monthIncome, key = { "pay-" + it.id }) { p ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(prettyDate(p.nextDate()), style = MaterialTheme.typography.titleMedium); Text(p.label + " • +" + currency(p.amount)) } }
        }
    }
}

@Composable
fun IncomePage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Paydays & income", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Use + Payday to add your paycheck schedule.") }
        if (data.paydays.isEmpty()) item { Text("No paydays added yet.") }
        items(data.paydays.sortedBy { it.nextDate() }, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.label, style = MaterialTheme.typography.titleMedium)
                    Text(currency(p.amount) + " • " + prettyDate(p.nextDate()) + " • " + p.frequency.name.replace('_', ' '))
                    TextButton(onClick = { vm.deletePayday(p.id) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
fun SettingsPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var balance by remember(data.manualBalance) { mutableStateOf(if (data.manualBalance == 0.0) "" else data.manualBalance.toString()) }
    val options = listOf(7, 3, 1, 0)
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
        item { OutlinedTextField(value = balance, onValueChange = { balance = it }, label = { Text("Current bank balance") }, supportingText = { Text("Manual balance until Plaid is connected") }, modifier = Modifier.fillMaxWidth()) }
        item { Button(onClick = { balance.toDoubleOrNull()?.let(vm::balance) }) { Text("Save balance") } }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bill reminders", style = MaterialTheme.typography.titleMedium)
                    options.forEach { day ->
                        val checked = day in data.reminderDays
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (day == 0) "On due date" else day.toString() + " day" + if (day == 1) " before" else "s before")
                            Switch(checked = checked, onCheckedChange = { enabled ->
                                val next = if (enabled) data.reminderDays + day else data.reminderDays - day
                                vm.reminderDays(next)
                            })
                        }
                    }
                }
            }
        }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Automatic bank connection", style = MaterialTheme.typography.titleMedium); Text("Plaid connection is prepared as the next banking step. Your bill data does not depend on it.") } } }
        item { Text("Bill data is encrypted on-device using Android Keystore.") }
        item { Text("BillNest v1.1.0") }
    }
}

@Composable
fun BillEditorDialog(original: Bill?, onDismiss: () -> Unit, onSave: (Bill) -> Unit) {
    var name by remember { mutableStateOf(original?.name ?: "") }
    var amount by remember { mutableStateOf(original?.amount?.toString() ?: "") }
    var date by remember { mutableStateOf(original?.dueDateIso ?: LocalDate.now().plusDays(7).toString()) }
    var autopay by remember { mutableStateOf(original?.autopay ?: false) }
    var variable by remember { mutableStateOf(original?.variableAmount ?: false) }
    var frequency by remember { mutableStateOf(original?.frequency ?: Frequency.MONTHLY) }
    var category by remember { mutableStateOf(original?.category ?: "Other") }
    var notes by remember { mutableStateOf(original?.notes ?: "") }
    var freqExpanded by remember { mutableStateOf(false) }
    var catExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "Add bill" else "Edit bill") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 520.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Bill name") }) }
                item { OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }) }
                item { OutlinedTextField(date, { date = it }, label = { Text("Due date (YYYY-MM-DD)") }) }
                item { Box { OutlinedButton(onClick = { catExpanded = true }) { Text("Category: " + category) }; DropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) { BillCategories.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { category = c; catExpanded = false }) } } } }
                item { Box { OutlinedButton(onClick = { freqExpanded = true }) { Text("Repeats: " + frequency.name.replace('_', ' ')) }; DropdownMenu(expanded = freqExpanded, onDismissRequest = { freqExpanded = false }) { Frequency.entries.forEach { f -> DropdownMenuItem(text = { Text(f.name.replace('_', ' ')) }, onClick = { frequency = f; freqExpanded = false }) } } } }
                item { Row { Checkbox(autopay, { autopay = it }); Text("Autopay", modifier = Modifier.padding(top = 12.dp)) } }
                item { Row { Checkbox(variable, { variable = it }); Text("Amount changes month to month", modifier = Modifier.padding(top = 12.dp)) } }
                item { OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedAmount = amount.toDoubleOrNull()
                val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
                if (parsedAmount != null && parsedDate != null) {
                    onSave((original ?: Bill(name = "", amount = 0.0, dueDateIso = parsedDate.toString())).copy(name = name.ifBlank { "Bill" }, amount = parsedAmount, dueDateIso = parsedDate.toString(), frequency = frequency, autopay = autopay, category = category, notes = notes, variableAmount = variable))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") }
    )
}

@Composable
fun PaydayDialog(onDismiss: () -> Unit, onSave: (Payday) -> Unit) {
    var label by remember { mutableStateOf("Paycheck") }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var frequency by remember { mutableStateOf(Frequency.BIWEEKLY) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add payday") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Name") })
                OutlinedTextField(amount, { amount = it }, label = { Text("Take-home amount") })
                OutlinedTextField(date, { date = it }, label = { Text("Next payday (YYYY-MM-DD)") })
                Box { OutlinedButton(onClick = { expanded = true }) { Text("Repeats: " + frequency.name.replace('_', ' ')) }; DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { listOf(Frequency.WEEKLY, Frequency.BIWEEKLY, Frequency.MONTHLY).forEach { f -> DropdownMenuItem(text = { Text(f.name.replace('_', ' ')) }, onClick = { frequency = f; expanded = false }) } } }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedAmount = amount.toDoubleOrNull()
                val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
                if (parsedAmount != null && parsedDate != null) onSave(Payday(label = label.ifBlank { "Paycheck" }, amount = parsedAmount, nextDateIso = parsedDate.toString(), frequency = frequency))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") }
    )
}

private fun currency(value: Double): String = NumberFormat.getCurrencyInstance().format(value)
private fun prettyDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))