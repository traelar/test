package com.baylee.billnest

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.baylee.billnest.data.BankApi
import com.baylee.billnest.data.BankConnectionIssue
import com.baylee.billnest.data.canRefreshBanks
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

    private var bankIssues by mutableStateOf<List<BankConnectionIssue>>(emptyList())
    private var reconnectingItemId: String? = null

    private val linkAccountToPlaid = registerForActivityResult(OpenPlaidLink()) { result ->
        when (result) {
            is LinkSuccess -> {
                val reconnectItem = reconnectingItemId
                val token = result.publicToken
                if (reconnectItem == null && token.isNullOrBlank()) {
                    toast("Plaid did not return a bank token")
                    return@registerForActivityResult
                }
                val institution = result.metadata.institution?.name
                lifecycleScope.launch {
                    runCatching {
                        val url = vm.data.value.backendUrl
                        if (reconnectItem == null) {
                            BankApi.exchangePublicToken(url, vm.data.value.backendApiKey, token!!, institution)
                        }
                        BankApi.fetchAccounts(url, vm.data.value.backendApiKey)
                    }.onSuccess { refresh ->
                        reconnectingItemId = null
                        bankIssues = refresh.issues
                        vm.syncPlaidAccounts(refresh.accounts)
                        runCatching { BankApi.fetchTransactions(url, vm.data.value.backendApiKey) }.onSuccess { (transactions, issues) ->
                            vm.syncPlaidTransactions(transactions)
                            bankIssues = (bankIssues + issues).distinctBy { it.itemId }
                        }
                        toast(if (reconnectItem == null) "Bank connected" else "Bank reconnected")
                    }.onFailure {
                        reconnectingItemId = null
                        toast(it.message ?: if (reconnectItem == null) "Could not finish bank connection" else "Could not reconnect bank")
                    }
                }
            }
            is LinkExit -> {
                reconnectingItemId = null
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
                    bankIssues = bankIssues,
                    onConnectBank = { connectBank() },
                    onReconnectBank = { reconnectBank(it) },
                    onRefreshBanks = { refreshBanks() }
                )
            }
        }
        refreshBanks(showSuccessToast = false)
    }

    private fun launchPlaid(linkToken: String) {
        runCatching {
            val session = Plaid.createPlaidLinkSession(
                this@MainActivity,
                linkTokenConfiguration { token = linkToken }
            )
            linkAccountToPlaid.launch(session)
        }.onFailure {
            reconnectingItemId = null
            toast(it.message ?: "Could not open Plaid")
        }
    }

    private fun connectBank() {
        val url = vm.data.value.backendUrl
        if (url.isBlank()) {
            toast("Add your BillNest bank server address in Settings first")
            return
        }
        reconnectingItemId = null
        lifecycleScope.launch {
            runCatching {
                BankApi.createLinkToken(url, vm.data.value.backendApiKey)
            }.onSuccess { launchPlaid(it) }
                .onFailure { toast(it.message ?: "Could not contact BillNest bank server") }
        }
    }

    private fun reconnectBank(itemId: String) {
        val url = vm.data.value.backendUrl
        if (url.isBlank()) {
            toast("Add your BillNest bank server address in Settings first")
            return
        }
        lifecycleScope.launch {
            runCatching {
                BankApi.createUpdateLinkToken(url, vm.data.value.backendApiKey, itemId)
            }.onSuccess { linkToken ->
                reconnectingItemId = itemId
                launchPlaid(linkToken)
            }.onFailure {
                reconnectingItemId = null
                toast(it.message ?: "Could not start bank reconnect")
            }
        }
    }

    private fun refreshBanks(showSuccessToast: Boolean = true) {
        val url = vm.data.value.backendUrl
        if (url.isBlank()) {
            toast("Add your BillNest bank server address in Settings first")
            return
        }
        lifecycleScope.launch {
            runCatching { BankApi.fetchAccounts(url, vm.data.value.backendApiKey) }
                .onSuccess { refresh ->
                    bankIssues = refresh.issues
                    vm.syncPlaidAccounts(refresh.accounts)
                    runCatching { BankApi.fetchTransactions(url, vm.data.value.backendApiKey) }.onSuccess { (transactions, issues) ->
                        vm.syncPlaidTransactions(transactions)
                        bankIssues = (bankIssues + issues).distinctBy { it.itemId }
                    }
                    when {
                        refresh.issues.any { it.requiresReconnect } -> toast("A bank connection needs to be reconnected")
                        refresh.issues.isNotEmpty() -> toast("Some bank connections need attention")
                        showSuccessToast -> toast("Bank balances refreshed")
                    }
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
    bankIssues: List<BankConnectionIssue>,
    onConnectBank: () -> Unit,
    onReconnectBank: (String) -> Unit,
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
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val destinations = listOf(
        "Dashboard", "Accounts", "Transactions", "Bills", "Budgets",
        "Debt", "Savings / Goals", "Reserved Funds", "Subscriptions", "Income", "Calendar", "Household", "Settings"
    )
    var destination by remember { mutableStateOf("Dashboard") }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight(),
                    contentPadding = PaddingValues(
                        top = 12.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
                    )
                ) {
                    item {
                        Text(
                            "BillNest",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                        HorizontalDivider()
                    }
                    items(destinations) { name ->
                        NavigationDrawerItem(
                            label = { Text(name) },
                            selected = destination == name,
                            onClick = {
                                destination = name
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(destination) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open navigation")
                        }
                    },
                    actions = {
                        when (destination) {
                            "Bills" -> TextButton(onClick = { showAddBill = true }) { Text("+ Bill") }
                            "Accounts" -> TextButton(onClick = { showAddAccount = true }) { Text("+ Account") }
                            "Income" -> TextButton(onClick = { showAddPayday = true }) { Text("+ Payday") }
                        }
                    }
                )
            }
        ) { pad ->
            when (destination) {
                "Dashboard" -> Dashboard(data, vm, Modifier.padding(pad), onEdit = { editingBill = it })
                "Bills" -> BillsPage(data, vm, Modifier.padding(pad), onEdit = { editingBill = it })
                "Accounts" -> AccountsPage(
                    data = data,
                    vm = vm,
                    bankIssues = bankIssues,
                    modifier = Modifier.padding(pad),
                    onEdit = { editingAccount = it },
                    onConnectBank = onConnectBank,
                    onReconnectBank = onReconnectBank,
                    onRefreshBanks = onRefreshBanks
                )
                "Transactions" -> TransactionsPage(data, vm, Modifier.padding(pad))
                "Budgets" -> BudgetsPage(data, vm, Modifier.padding(pad))
                "Debt" -> DebtPage(data, vm, Modifier.padding(pad))
                "Savings / Goals" -> SavingsGoalsPage(data, vm, Modifier.padding(pad))
                "Reserved Funds" -> ReservedFundsPage(data, vm, Modifier.padding(pad))
                "Subscriptions" -> SubscriptionsPage(data, Modifier.padding(pad))
                "Calendar" -> CalendarPage(data, Modifier.padding(pad))
                "Income" -> IncomePage(data, vm, Modifier.padding(pad), onEdit = { editingPayday = it })
                "Household" -> LaunchedEffect(Unit) { context.startActivity(Intent(context, HouseholdActivity::class.java)) }
                "Settings" -> SettingsPage(data, vm, Modifier.padding(pad))
                else -> V2ComingSoonPage(destination, Modifier.padding(pad))
            }
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
    val money = calculateMoneySummary(data)
    val balance = if (data.accounts.isNotEmpty()) money.totalMoney else data.manualBalance
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
                    Text("Spending Money: " + currency(money.spendingMoney))
                    Text("Savings: " + currency(money.savings))
                    Text("Reserved Money: " + currency(money.reserved))
                    Text("Bills next 30 days: " + currency(due30Total))
                    Text("Expected income next 30 days: " + currency(incoming30))
                    Text("Available after upcoming bills: " + currency(money.availableAfterUpcomingBills), style = MaterialTheme.typography.titleLarge)
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
    val matches = findBillMatches(data.bills, data.transactions)

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("All bills", style = MaterialTheme.typography.headlineSmall) }
        if (matches.isNotEmpty()) {
            item { Text("Needs review", style = MaterialTheme.typography.titleLarge) }
            items(matches, key = { "match-${it.billId}-${it.transactionId}" }) { match ->
                val bill = data.bills.first { it.id == match.billId }
                val transaction = data.transactions.first { it.id == match.transactionId }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Possible payment: ${bill.name}", style = MaterialTheme.typography.titleMedium)
                        Text("${transaction.name} • ${currency(transaction.amount)} • ${prettyDate(LocalDate.parse(transaction.dateIso))}")
                        Text(if (match.highConfidence) "High-confidence match" else "Review before marking paid", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { vm.paid(bill.id) }) { Text("Confirm and mark paid") }
                    }
                }
            }
        }
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
    bankIssues: List<BankConnectionIssue>,
    modifier: Modifier = Modifier,
    onEdit: (Account) -> Unit,
    onConnectBank: () -> Unit,
    onReconnectBank: (String) -> Unit,
    onRefreshBanks: () -> Unit
) {
    val orderedAccounts = data.accounts.sortedWith(compareBy<Account> { it.displayOrder }.thenBy { it.name })
    val money = calculateMoneySummary(data)
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Accounts", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Total Money")
                    Text(currency(money.totalMoney), style = MaterialTheme.typography.headlineMedium)
                    Text("Spending Money: ${currency(money.spendingMoney)}")
                    Text("Savings: ${currency(money.savings)}")
                    Text("Reserved: ${currency(money.reserved)}")
                    Text("${data.accounts.size} account${if (data.accounts.size == 1) "" else "s"}")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnectBank) { Text("Connect bank") }
                OutlinedButton(onClick = onRefreshBanks, enabled = canRefreshBanks(data.backendUrl)) { Text("Refresh") }
            }
        }
        if (data.backendUrl.isBlank()) {
            item { Text("Set your BillNest bank server address in Settings before connecting Plaid.") }
        }
        items(bankIssues, key = { "bank-issue-${it.itemId}" }) { issue ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(issue.label?.takeIf { it.isNotBlank() } ?: "Bank connection", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (issue.requiresReconnect) "Connection needs to be repaired" else "Bank connection needs attention",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        if (issue.requiresReconnect) "Your bank is asking you to sign in again before BillNest can refresh this account."
                        else issue.message.ifBlank { "BillNest could not refresh this bank right now." },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (issue.requiresReconnect) {
                        Button(onClick = { onReconnectBank(issue.itemId) }) { Text("Reconnect bank") }
                    }
                }
            }
        }
        if (data.accounts.isEmpty() && bankIssues.isEmpty()) {
            item { Text("No accounts yet. Tap + Account for a manual Checking/Savings account, or Connect bank for Plaid.") }
        }
        items(orderedAccounts, key = { it.id }) { account ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(account.name, style = MaterialTheme.typography.titleMedium)
                            Text(account.type.name.lowercase().replaceFirstChar { it.uppercase() } + if (account.mask.isNotBlank()) " ••••${account.mask}" else "")
                            Text(if (account.source == AccountSource.PLAID) "Connected with Plaid" else "Manual account", style = MaterialTheme.typography.bodySmall)
                            Text("${account.role.name.lowercase().replaceFirstChar { it.uppercase() }} • ${if (account.includeInSpendable) "Included in spending" else "Excluded from spending"}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(currency(account.balance), style = MaterialTheme.typography.titleLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.moveAccount(account.id, -1) }) { Text("↑") }
                        TextButton(onClick = { vm.moveAccount(account.id, 1) }) { Text("↓") }
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
    val detected = detectPaydayPatterns(data.transactions).filterNot { suggestion ->
        data.paydays.any { it.label.equals(suggestion.label, true) && it.frequency == suggestion.frequency }
    }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Paydays & income", style = MaterialTheme.typography.headlineSmall) }
        item { Text("BillNest detects recurring paydays from income transactions even when check amounts change. You can still use + Payday to add one manually.") }
        if (detected.isNotEmpty()) {
            item { Text("Detected paydays", style = MaterialTheme.typography.titleMedium) }
            items(detected, key = { "detected-${it.label}-${it.frequency}" }) { suggestion ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(suggestion.label, style = MaterialTheme.typography.titleMedium)
                        Text("Typical check ${currency(suggestion.typicalAmount)} • ${suggestion.frequency.name.replace('_', ' ')}")
                        Text("Next expected ${prettyDate(LocalDate.parse(suggestion.nextDateIso))} • based on ${suggestion.sampleCount} deposits", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { vm.addPayday(Payday(label = suggestion.label, amount = suggestion.typicalAmount, nextDateIso = suggestion.nextDateIso, frequency = suggestion.frequency)) }) { Text("Use this schedule") }
                    }
                }
            }
        }
        if (data.paydays.isEmpty()) item { Text("No paydays added yet.") }
        items(data.paydays.sortedBy { it.nextDate() }, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.label, style = MaterialTheme.typography.titleMedium)
                    Text(currency(p.amount) + " • " + prettyDate(p.nextDate()) + " • " + p.frequency.name.replace('_', ' '))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.receivePayday(p.id) }) { Text("Pay received") }
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
    var backendApiKey by remember(data.backendApiKey) { mutableStateOf(data.backendApiKey) }
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
                    OutlinedTextField(
                        value = backendApiKey,
                        onValueChange = { backendApiKey = it },
                        label = { Text("Bank server key") },
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text("Private key used only by your BillNest app") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = {
                        vm.backendUrl(backendUrl)
                        vm.backendApiKey(backendApiKey)
                    }) { Text("Save bank connection") }
                    Text("Your Plaid secret stays on Cloudflare and is never stored in the APK.", style = MaterialTheme.typography.bodySmall)
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
        item { Text("BillNest v2.0.0-alpha5") }
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
    var role by remember { mutableStateOf(original?.role ?: AccountRole.OTHER) }
    var includeInSpendable by remember { mutableStateOf(original?.includeInSpendable ?: true) }
    var expanded by remember { mutableStateOf(false) }
    var roleExpanded by remember { mutableStateOf(false) }

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
                Box {
                    OutlinedButton(onClick = { roleExpanded = true }) {
                        Text("Role: " + role.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                    DropdownMenu(expanded = roleExpanded, onDismissRequest = { roleExpanded = false }) {
                        AccountRole.entries.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(value.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    role = value
                                    if (value == AccountRole.SAVINGS || value == AccountRole.CREDIT) includeInSpendable = false
                                    roleExpanded = false
                                }
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Include in Spending Money")
                        Text("Account stays visible when excluded.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = includeInSpendable, onCheckedChange = { includeInSpendable = it })
                }
                if (plaid) Text("Balance and bank type come from Plaid. Name, role, and spending behavior are controlled by you.")
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
                            balance = parsedBalance,
                            role = role,
                            includeInSpendable = includeInSpendable
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
