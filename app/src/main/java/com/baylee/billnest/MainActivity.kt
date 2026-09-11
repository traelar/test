package com.baylee.billnest

import android.Manifest
import android.app.DatePickerDialog
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.baylee.billnest.data.BankApi
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel
import com.baylee.billnest.ui.theme.BillNestTheme
import com.plaid.link.OpenPlaidLink
import com.plaid.link.Plaid
import com.plaid.link.configuration.linkTokenConfiguration
import com.plaid.link.result.LinkExit
import com.plaid.link.result.LinkSuccess
import kotlinx.coroutines.launch
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

    private val linkAccountToPlaid = registerForActivityResult(OpenPlaidLink()) { result ->
        when (result) {
            is LinkSuccess -> {
                val token = result.publicToken
                if (token.isNullOrBlank()) {
                    toast("Plaid did not return a bank token")
                    return@registerForActivityResult
                }
                val institution = result.metadata.institution?.name
                lifecycleScope.launch {
                    runCatching {
                        val url = vm.data.value.backendUrl
                        BankApi.exchangePublicToken(url, token, institution)
                        BankApi.fetchAccounts(url)
                    }.onSuccess { accounts ->
                        vm.syncPlaidAccounts(accounts)
                        toast("Bank connected")
                    }.onFailure { toast(it.message ?: "Could not finish bank connection") }
                }
            }
            is LinkExit -> {
                result.error?.let { toast(it.displayMessage ?: it.errorMessage ?: "Plaid connection closed") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BillNestTheme {
                BillNestHome(
                    vm = vm,
                    onConnectBank = { connectBank() },
                    onRefreshBanks = { refreshBanks() }
                )
            }
        }
    }

    private fun connectBank() {
        val url = vm.data.value.backendUrl
        if (url.isBlank()) {
            toast("Add your BillNest bank server address in Settings first")
            return
        }
        lifecycleScope.launch {
            runCatching {
                BankApi.createLinkToken(url)
            }.onSuccess { linkToken ->
                runCatching {
                    val session = Plaid.createPlaidLinkSession(
                        this@MainActivity,
                        linkTokenConfiguration { token = linkToken }
                    )
                    linkAccountToPlaid.launch(session)
                }.onFailure { toast(it.message ?: "Could not open Plaid") }
            }.onFailure { toast(it.message ?: "Could not contact BillNest bank server") }
        }
    }

    private fun refreshBanks() {
        val url = vm.data.value.backendUrl
        if (url.isBlank()) {
            toast("Add your BillNest bank server address in Settings first")
            return
        }
        lifecycleScope.launch {
            runCatching { BankApi.fetchAccounts(url) }
                .onSuccess {
                    vm.syncPlaidAccounts(it)
                    toast("Bank balances refreshed")
                }
                .onFailure { toast(it.message ?: "Could not refresh bank balances") }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillNestHome(
    vm: MainViewModel,
    onConnectBank: () -> Unit,
    onRefreshBanks: () -> Unit
) {
    val data by vm.data.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var showAddBill by remember { mutableStateOf(false) }
    var showAddPayday by remember { mutableStateOf(false) }
    var showAddAccount by remember { mutableStateOf(false) }
    var editingBill by remember { mutableStateOf<Bill?>(null) }
    var editingPayday by remember { mutableStateOf<Payday?>(null) }
    var editingAccount by remember { mutableStateOf<Account?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    val titles = listOf("Home", "Bills", "Accts", "Cal", "Income", "Settings")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BillNest") },
                actions = {
                    when (tab) {
                        1 -> TextButton(onClick = { showAddBill = true }) { Text("+ Bill") }
                        2 -> TextButton(onClick = { showAddAccount = true }) { Text("+ Account") }
                        4 -> TextButton(onClick = { showAddPayday = true }) { Text("+ Payday") }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, name ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {},
                        label = { Text(name, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    ) { pad ->
        when (tab) {
            0 -> Dashboard(data, vm, Modifier.padding(pad), onEdit = { editingBill = it })
            1 -> BillsPage(data, vm, Modifier.padding(pad), onEdit = { editingBill = it })
            2 -> AccountsPage(
                data = data,
                vm = vm,
                modifier = Modifier.padding(pad),
                onEdit = { editingAccount = it },
                onConnectBank = onConnectBank,
                onRefreshBanks = onRefreshBanks
            )
            3 -> CalendarPage(data, Modifier.padding(pad))
            4 -> IncomePage(data, vm, Modifier.padding(pad), onEdit = { editingPayday = it })
            else -> SettingsPage(data, vm, Modifier.padding(pad))
        }
    }

    if (showAddBill) {
        BillEditorDialog(null, data.accounts, { showAddBill = false }) {
            vm.add(it)
            showAddBill = false
        }
    }
    editingBill?.let { bill ->
        BillEditorDialog(bill, data.accounts, { editingBill = null }) {
            vm.updateBill(it)
            editingBill = null
        }
    }
    if (showAddPayday) PaydayDialog(null, { showAddPayday = false }) { vm.addPayday(it); showAddPayday = false }
    editingPayday?.let { payday -> PaydayDialog(payday, { editingPayday = null }) { vm.updatePayday(it); editingPayday = null } }
    if (showAddAccount) AccountDialog(null, { showAddAccount = false }) { vm.addAccount(it); showAddAccount = false }
    editingAccount?.let { account -> AccountDialog(account, { editingAccount = null }) { vm.updateAccount(it); editingAccount = null } }
}

@Composable
fun Dashboard(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier, onEdit: (Bill) -> Unit) {
    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val balance = if (data.accounts.isNotEmpty()) data.accounts.sumOf { it.balance } else data.manualBalance
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
    val nextPayday = data.paydays.filter { !it.nextDate().isBefore(today) }.minByOrNull { it.nextDate() }
    val billsBeforeNextPayday = nextPayday?.let { payday ->
        unpaid.filter { !it.dueDate().isBefore(today) && !it.dueDate().isAfter(payday.nextDate()) }.sumOf { it.amount }
    } ?: 0.0

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Money at a glance", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Across ${data.accounts.size} account${if (data.accounts.size == 1) "" else "s"}")
                    Text(currency(balance), style = MaterialTheme.typography.headlineMedium)
                    Text("Bills next 30 days: " + currency(due30Total))
                    Text("Expected income next 30 days: " + currency(incoming30))
                    Text("Safe after upcoming bills: " + currency(safe), style = MaterialTheme.typography.titleLarge)
                    nextPayday?.let { payday ->
                        Text("Next payday: " + prettyDate(payday.nextDate()) + " • " + currency(payday.amount))
                        Text("Bills before payday: " + currency(billsBeforeNextPayday))
                    }
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
            items(overdue.sortedBy { it.dueDate() }, key = { it.id }) { bill ->
                BillRow(bill, vm, onEdit, true, accountName(data, bill.accountId))
            }
        }
        item { Text("Next bills", style = MaterialTheme.typography.titleLarge) }
        val next = due30.sortedBy { it.dueDate() }.take(6)
        if (next.isEmpty()) item { Text("No upcoming bills in the next 30 days.") }
        items(next, key = { it.id }) { bill ->
            BillRow(bill, vm, onEdit, false, accountName(data, bill.accountId))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
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
                    categories.forEach { c ->
                        DropdownMenuItem(text = { Text(c) }, onClick = { category = c; expanded = false })
                    }
                }
            }
        }
        if (visible.isEmpty()) item { Text("No bills in this category.") }
        items(visible, key = { it.id }) { bill ->
            BillRow(
                bill,
                vm,
                onEdit,
                !bill.isPaidFor() && bill.dueDate().isBefore(LocalDate.now()),
                accountName(data, bill.accountId)
            )
        }
    }
}

@Composable
fun BillRow(b: Bill, vm: MainViewModel, onEdit: (Bill) -> Unit, overdue: Boolean, account: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(b.name, style = MaterialTheme.typography.titleMedium)
            Text(currency(b.amount) + " • " + b.category + if (b.variableAmount) " • Variable" else "")
            Text("Due " + prettyDate(b.dueDate()) + if (b.autopay) " • Autopay" else "")
            account?.let { Text("From: $it", style = MaterialTheme.typography.bodySmall) }
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
fun AccountsPage(
    data: AppData,
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onEdit: (Account) -> Unit,
    onConnectBank: () -> Unit,
    onRefreshBanks: () -> Unit
) {
    val total = data.accounts.sumOf { it.balance }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Accounts", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Total available")
                    Text(currency(total), style = MaterialTheme.typography.headlineMedium)
                    Text("${data.accounts.size} account${if (data.accounts.size == 1) "" else "s"}")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnectBank) { Text("Connect bank") }
                OutlinedButton(onClick = onRefreshBanks, enabled = data.plaidConnected) { Text("Refresh") }
            }
        }
        if (data.backendUrl.isBlank()) {
            item { Text("Set your BillNest bank server address in Settings before connecting Plaid.") }
        }
        if (data.accounts.isEmpty()) {
            item { Text("No accounts yet. Tap + Account for a manual Checking/Savings account, or Connect bank for Plaid.") }
        }
        items(data.accounts, key = { it.id }) { account ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(account.name, style = MaterialTheme.typography.titleMedium)
                            Text(account.type.name.lowercase().replaceFirstChar { it.uppercase() } + if (account.mask.isNotBlank()) " ••••${account.mask}" else "")
                            Text(if (account.source == AccountSource.PLAID) "Connected with Plaid" else "Manual account", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(currency(account.balance), style = MaterialTheme.typography.titleLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onEdit(account) }) { Text("Edit") }
                        if (account.source == AccountSource.MANUAL) {
                            TextButton(onClick = { vm.deleteAccount(account.id) }) { Text("Delete") }
                        }
                    }
                }
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
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(prettyDate(b.dueDate()), style = MaterialTheme.typography.titleMedium)
                    Text(b.name + " • " + currency(b.amount) + " • " + b.category)
                    accountName(data, b.accountId)?.let { Text("From: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        items(monthIncome, key = { "pay-" + it.id }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(prettyDate(p.nextDate()), style = MaterialTheme.typography.titleMedium)
                    Text(p.label + " • +" + currency(p.amount))
                }
            }
        }
    }
}

@Composable
fun IncomePage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier, onEdit: (Payday) -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Paydays & income", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Use + Payday to add your paycheck schedule.") }
        if (data.paydays.isEmpty()) item { Text("No paydays added yet.") }
        items(data.paydays.sortedBy { it.nextDate() }, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.label, style = MaterialTheme.typography.titleMedium)
                    Text(currency(p.amount) + " • " + prettyDate(p.nextDate()) + " • " + p.frequency.name.replace('_', ' '))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onEdit(p) }) { Text("Edit") }
                        TextButton(onClick = { vm.deletePayday(p.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var backendUrl by remember(data.backendUrl) { mutableStateOf(data.backendUrl) }
    val options = listOf(7, 3, 1, 0)
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bank connection server", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = { backendUrl = it },
                        label = { Text("Backend address") },
                        supportingText = { Text("Example: https://your-domain.com or http://192.168.1.50:8787") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { vm.backendUrl(backendUrl) }) { Text("Save server address") }
                    Text("Your Plaid secret stays on this server and is never stored in the APK.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
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
        item { Text("Bill and account data is encrypted on-device using Android Keystore.") }
        item { Text("BillNest v1.3.0") }
    }
}

@Composable
fun BillEditorDialog(original: Bill?, accounts: List<Account>, onDismiss: () -> Unit, onSave: (Bill) -> Unit) {
    var name by remember { mutableStateOf(original?.name ?: "") }
    var amount by remember { mutableStateOf(original?.amount?.toString() ?: "") }
    var date by remember { mutableStateOf(original?.dueDateIso ?: LocalDate.now().plusDays(7).toString()) }
    var autopay by remember { mutableStateOf(original?.autopay ?: false) }
    var variable by remember { mutableStateOf(original?.variableAmount ?: false) }
    var frequency by remember { mutableStateOf(original?.frequency ?: Frequency.MONTHLY) }
    var category by remember { mutableStateOf(original?.category ?: "Other") }
    var accountId by remember { mutableStateOf(original?.accountId) }
    var notes by remember { mutableStateOf(original?.notes ?: "") }
    var freqExpanded by remember { mutableStateOf(false) }
    var catExpanded by remember { mutableStateOf(false) }
    var accountExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "Add bill" else "Edit bill") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 540.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Bill name") }) }
                item { OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }) }
                item { DatePickerButton(label = "Due date", dateIso = date, onDateSelected = { date = it }) }
                item {
                    Box {
                        OutlinedButton(onClick = { catExpanded = true }) { Text("Category: $category") }
                        DropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                            BillCategories.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { category = c; catExpanded = false }) }
                        }
                    }
                }
                item {
                    Box {
                        OutlinedButton(onClick = { accountExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Paid from: " + (accounts.firstOrNull { it.id == accountId }?.name ?: "Unassigned"))
                        }
                        DropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                            DropdownMenuItem(text = { Text("Unassigned") }, onClick = { accountId = null; accountExpanded = false })
                            accounts.forEach { account ->
                                DropdownMenuItem(text = { Text(account.name) }, onClick = { accountId = account.id; accountExpanded = false })
                            }
                        }
                    }
                }
                item {
                    Box {
                        OutlinedButton(onClick = { freqExpanded = true }) { Text("Repeats: " + frequency.name.replace('_', ' ')) }
                        DropdownMenu(expanded = freqExpanded, onDismissRequest = { freqExpanded = false }) {
                            Frequency.entries.forEach { f -> DropdownMenuItem(text = { Text(f.name.replace('_', ' ')) }, onClick = { frequency = f; freqExpanded = false }) }
                        }
                    }
                }
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
                    onSave(
                        (original ?: Bill(name = "", amount = 0.0, dueDateIso = parsedDate.toString())).copy(
                            name = name.ifBlank { "Bill" },
                            amount = parsedAmount,
                            dueDateIso = parsedDate.toString(),
                            frequency = frequency,
                            autopay = autopay,
                            category = category,
                            notes = notes,
                            variableAmount = variable,
                            accountId = accountId
                        )
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun AccountDialog(original: Account?, onDismiss: () -> Unit, onSave: (Account) -> Unit) {
    val plaid = original?.source == AccountSource.PLAID
    var name by remember { mutableStateOf(original?.name ?: "") }
    var balance by remember { mutableStateOf(original?.balance?.toString() ?: "") }
    var type by remember { mutableStateOf(original?.type ?: AccountType.CHECKING) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "Add account" else "Edit account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Account name") })
                OutlinedTextField(
                    value = balance,
                    onValueChange = { balance = it },
                    label = { Text("Current balance") },
                    enabled = !plaid
                )
                Box {
                    OutlinedButton(onClick = { if (!plaid) expanded = true }, enabled = !plaid) {
                        Text("Type: " + type.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        AccountType.entries.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(value.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = { type = value; expanded = false }
                            )
                        }
                    }
                }
                if (plaid) Text("Balance and type come from your bank. You can rename the account here.")
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedBalance = if (plaid) original?.balance else balance.toDoubleOrNull()
                if (parsedBalance != null) {
                    onSave(
                        (original ?: Account(name = "", type = type, balance = parsedBalance)).copy(
                            name = name.ifBlank { "Account" },
                            type = if (plaid) original?.type ?: type else type,
                            balance = parsedBalance
                        )
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun PaydayDialog(original: Payday?, onDismiss: () -> Unit, onSave: (Payday) -> Unit) {
    var label by remember { mutableStateOf(original?.label ?: "Paycheck") }
    var amount by remember { mutableStateOf(original?.amount?.toString() ?: "") }
    var date by remember { mutableStateOf(original?.nextDateIso ?: LocalDate.now().toString()) }
    var frequency by remember { mutableStateOf(original?.frequency ?: Frequency.BIWEEKLY) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "Add payday" else "Edit payday") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Name") })
                OutlinedTextField(amount, { amount = it }, label = { Text("Take-home amount") })
                DatePickerButton(label = "Next payday", dateIso = date, onDateSelected = { date = it })
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text("Repeats: " + frequency.name.replace('_', ' ')) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf(Frequency.WEEKLY, Frequency.BIWEEKLY, Frequency.MONTHLY).forEach { f ->
                            DropdownMenuItem(text = { Text(f.name.replace('_', ' ')) }, onClick = { frequency = f; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedAmount = amount.toDoubleOrNull()
                val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
                if (parsedAmount != null && parsedDate != null) {
                    onSave(
                        (original ?: Payday(label = "Paycheck", amount = parsedAmount, nextDateIso = parsedDate.toString())).copy(
                            label = label.ifBlank { "Paycheck" },
                            amount = parsedAmount,
                            nextDateIso = parsedDate.toString(),
                            frequency = frequency
                        )
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun DatePickerButton(label: String, dateIso: String, onDateSelected: (String) -> Unit) {
    val context = LocalContext.current
    val current = runCatching { LocalDate.parse(dateIso) }.getOrDefault(LocalDate.now())
    OutlinedButton(
        onClick = {
            DatePickerDialog(
                context,
                { _, year, month, day -> onDateSelected(LocalDate.of(year, month + 1, day).toString()) },
                current.year,
                current.monthValue - 1,
                current.dayOfMonth
            ).show()
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label + ": " + prettyDate(current))
    }
}

private fun accountName(data: AppData, accountId: String?): String? =
    accountId?.let { id -> data.accounts.firstOrNull { it.id == id }?.name }

private fun currency(value: Double): String = NumberFormat.getCurrencyInstance().format(value)
private fun prettyDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
