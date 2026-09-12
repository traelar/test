package com.baylee.billnest

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.baylee.billnest.data.*
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

class V2MainActivity : FragmentActivity() {
    private val vm by viewModels<MainViewModel> {
        val repo = (application as BillNestApp).repo
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo) as T
        }
    }

    private val transactionApi = TransactionApi()
    private var bankIssues by mutableStateOf<List<BankConnectionIssue>>(emptyList())
    private var reconnectingItemId: String? = null

    private val plaidLauncher = registerForActivityResult(OpenPlaidLink()) { result ->
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
                        val data = vm.data.value
                        if (reconnectItem == null) {
                            BankApi.exchangePublicToken(data.backendUrl, data.backendApiKey, token!!, institution)
                        }
                        refreshAllFinanceData(showToast = false)
                    }.onSuccess {
                        toast(if (reconnectItem == null) "Bank connected" else "Bank reconnected")
                    }.onFailure {
                        toast(it.message ?: "Could not finish bank connection")
                    }
                    reconnectingItemId = null
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
        val app = application as BillNestApp
        if (app.sessionStore.load() == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }
        setContent {
            BillNestTheme {
                V2AppShell(
                    vm = vm,
                    bankIssues = bankIssues,
                    onConnectBank = ::connectBank,
                    onReconnectBank = ::reconnectBank,
                    onRefresh = { lifecycleScope.launch { refreshAllFinanceData(showToast = true) } },
                    onHousehold = { startActivity(Intent(this, HouseholdActivity::class.java)) },
                    onSignOut = {
                        app.sessionStore.clear()
                        BankApi.setSessionToken(null)
                        startActivity(Intent(this, AuthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }
                )
            }
        }
        lifecycleScope.launch { refreshAllFinanceData(showToast = false) }
    }

    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) lifecycleScope.launch { refreshAllFinanceData(showToast = false) }
    }

    private suspend fun refreshAllFinanceData(showToast: Boolean) {
        val app = application as BillNestApp
        val session = app.sessionStore.load() ?: return
        BankApi.setSessionToken(session.sessionToken)
        val data = vm.data.value
        val bankResult = runCatching { BankApi.fetchAccounts(data.backendUrl, data.backendApiKey) }.getOrNull()
        if (bankResult != null) {
            bankIssues = bankResult.issues
            vm.syncPlaidAccounts(bankResult.accounts, bankResult.connectedItems)
        }
        runCatching {
            val (syncResult, transactions) = transactionApi.syncAndList(data.backendUrl, session.sessionToken)
            if (syncResult.issues.isNotEmpty()) {
                val merged = (bankIssues + syncResult.issues).distinctBy { it.itemId }
                bankIssues = merged
            }
            vm.setPlaidTransactions(transactions)
        }
        runCatching { app.householdSync.syncNow() }
        if (showToast) {
            when {
                bankIssues.any { it.requiresReconnect } -> toast("A bank needs to be reconnected")
                bankIssues.isNotEmpty() -> toast("Some bank connections need attention")
                else -> toast("BillNest is up to date")
            }
        }
    }

    private fun connectBank() {
        val data = vm.data.value
        lifecycleScope.launch {
            runCatching { BankApi.createLinkToken(data.backendUrl, data.backendApiKey) }
                .onSuccess { launchPlaid(it, null) }
                .onFailure { toast(it.message ?: "Could not connect bank") }
        }
    }

    private fun reconnectBank(itemId: String) {
        val data = vm.data.value
        lifecycleScope.launch {
            runCatching { BankApi.createUpdateLinkToken(data.backendUrl, data.backendApiKey, itemId) }
                .onSuccess { launchPlaid(it, itemId) }
                .onFailure { toast(it.message ?: "Could not reconnect bank") }
        }
    }

    private fun launchPlaid(linkToken: String, reconnectItem: String?) {
        runCatching {
            reconnectingItemId = reconnectItem
            val session = Plaid.createPlaidLinkSession(this, linkTokenConfiguration { token = linkToken })
            plaidLauncher.launch(session)
        }.onFailure {
            reconnectingItemId = null
            toast(it.message ?: "Could not open Plaid")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

private enum class V2Destination(val label: String) {
    DASHBOARD("Dashboard"),
    ACCOUNTS("Accounts"),
    TRANSACTIONS("Transactions"),
    BILLS("Bills"),
    BUDGETS("Budgets"),
    DEBT("Debt & Mortgage"),
    GOALS("Savings Goals"),
    RESERVED("Reserved Funds"),
    INCOME("Income"),
    CALENDAR("Calendar"),
    HOUSEHOLD("Household & Sync"),
    SETTINGS("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun V2AppShell(
    vm: MainViewModel,
    bankIssues: List<BankConnectionIssue>,
    onConnectBank: () -> Unit,
    onReconnectBank: (String) -> Unit,
    onRefresh: () -> Unit,
    onHousehold: () -> Unit,
    onSignOut: () -> Unit
) {
    val data by vm.data.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(V2Destination.DASHBOARD) }
    var showBill by remember { mutableStateOf(false) }
    var editingBill by remember { mutableStateOf<Bill?>(null) }
    var showPayday by remember { mutableStateOf(false) }
    var editingPayday by remember { mutableStateOf<Payday?>(null) }
    var showManualAccount by remember { mutableStateOf(false) }
    var editingManualAccount by remember { mutableStateOf<Account?>(null) }

    val iconFor: (V2Destination) -> androidx.compose.ui.graphics.vector.ImageVector = {
        when (it) {
            V2Destination.DASHBOARD -> Icons.Default.Home
            V2Destination.ACCOUNTS -> Icons.Default.AccountBalance
            V2Destination.TRANSACTIONS -> Icons.Default.ReceiptLong
            V2Destination.BILLS -> Icons.Default.EventNote
            V2Destination.BUDGETS -> Icons.Default.PieChart
            V2Destination.DEBT -> Icons.Default.CreditCard
            V2Destination.GOALS -> Icons.Default.Savings
            V2Destination.RESERVED -> Icons.Default.Lock
            V2Destination.INCOME -> Icons.Default.Payments
            V2Destination.CALENDAR -> Icons.Default.CalendarMonth
            V2Destination.HOUSEHOLD -> Icons.Default.Group
            V2Destination.SETTINGS -> Icons.Default.Settings
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(20.dp))
                Text("BillNest", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                Text("Household finance", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                V2Destination.entries.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.label) },
                        icon = { Icon(iconFor(item), contentDescription = null) },
                        selected = destination == item,
                        onClick = {
                            if (item == V2Destination.HOUSEHOLD) onHousehold() else destination = item
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSignOut, modifier = Modifier.padding(16.dp)) { Text("Sign out") }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(destination.label) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                        when (destination) {
                            V2Destination.BILLS -> TextButton(onClick = { showBill = true }) { Text("+ Bill") }
                            V2Destination.INCOME -> TextButton(onClick = { showPayday = true }) { Text("+ Payday") }
                            V2Destination.ACCOUNTS -> TextButton(onClick = { showManualAccount = true }) { Text("+ Account") }
                            else -> Unit
                        }
                    }
                )
            }
        ) { padding ->
            when (destination) {
                V2Destination.DASHBOARD -> V2Dashboard(data, Modifier.padding(padding))
                V2Destination.ACCOUNTS -> V2AccountsScreen(data, vm, bankIssues, onConnectBank, onReconnectBank, Modifier.padding(padding), onEditManual = { editingManualAccount = it })
                V2Destination.TRANSACTIONS -> TransactionsScreen(data, vm, Modifier.padding(padding))
                V2Destination.BILLS -> BillsPage(data, vm, Modifier.padding(padding), onEdit = { editingBill = it })
                V2Destination.BUDGETS -> BudgetsScreen(data, vm, Modifier.padding(padding))
                V2Destination.DEBT -> DebtScreen(data, vm, Modifier.padding(padding))
                V2Destination.GOALS -> GoalsScreen(data, vm, Modifier.padding(padding))
                V2Destination.RESERVED -> ReservedFundsScreen(data, vm, Modifier.padding(padding))
                V2Destination.INCOME -> IncomePage(data, vm, Modifier.padding(padding), onEdit = { editingPayday = it })
                V2Destination.CALENDAR -> CalendarPage(data, Modifier.padding(padding))
                V2Destination.HOUSEHOLD -> Unit
                V2Destination.SETTINGS -> V2SettingsScreen(data, vm, Modifier.padding(padding), onHousehold)
            }
        }
    }

    if (showBill) BillEditorDialog(null, data.accounts, { showBill = false }) { vm.add(it); showBill = false }
    editingBill?.let { BillEditorDialog(it, data.accounts, { editingBill = null }) { bill -> vm.updateBill(bill); editingBill = null } }
    if (showPayday) PaydayDialog(null, { showPayday = false }) { vm.addPayday(it); showPayday = false }
    editingPayday?.let { PaydayDialog(it, { editingPayday = null }) { p -> vm.updatePayday(p); editingPayday = null } }
    if (showManualAccount) AccountDialog(null, { showManualAccount = false }) { vm.addAccount(it); showManualAccount = false }
    editingManualAccount?.let { AccountDialog(it, { editingManualAccount = null }) { a -> vm.updateAccount(a); editingManualAccount = null } }
}

@Composable
private fun V2Dashboard(data: AppData, modifier: Modifier = Modifier) {
    val cash = CashPosition.calculate(data.accounts, data.accountPreferences, data.reservedFunds)
    val today = LocalDate.now()
    val unpaid = data.bills.filter { !it.isPaidFor() }
    val due30 = unpaid.filter { !it.dueDate().isBefore(today) && ChronoUnit.DAYS.between(today, it.dueDate()) <= 30 }
    val due30Total = due30.sumOf { it.amount }
    val spendAfterBills = cash.availableSpending - due30Total
    val needsReview = data.billMatches.count { it.status == MatchStatus.NEEDS_REVIEW }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Money at a glance", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Total Money", style = MaterialTheme.typography.labelLarge)
                    Text(money(cash.totalMoney), style = MaterialTheme.typography.headlineMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MoneyMiniCard("Spending", cash.spendingMoney, Modifier.weight(1f))
                        MoneyMiniCard("Savings", cash.savingsMoney, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MoneyMiniCard("Reserved", cash.reservedMoney, Modifier.weight(1f))
                        MoneyMiniCard("Free savings", cash.freeSavings, Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Available after upcoming bills", style = MaterialTheme.typography.titleMedium)
                    Text(money(spendAfterBills), style = MaterialTheme.typography.headlineMedium)
                    Text("Spending available: ${money(cash.availableSpending)}")
                    Text("Bills due next 30 days: ${money(due30Total)}")
                }
            }
        }
        if (needsReview > 0) item { AssistChip(onClick = {}, label = { Text("$needsReview transaction match${if (needsReview == 1) "" else "es"} need review") }) }
        item { Text("Upcoming bills", style = MaterialTheme.typography.titleLarge) }
        items(due30.sortedBy { it.dueDate() }.take(6), key = { it.id }) { bill ->
            ListItem(
                headlineContent = { Text(bill.name) },
                supportingContent = { Text("Due ${bill.dueDate().format(DateTimeFormatter.ofPattern("MMM d"))}") },
                trailingContent = { Text(money(bill.amount)) }
            )
        }
    }
}

@Composable
private fun MoneyMiniCard(label: String, value: Double, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(money(value), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun V2AccountsScreen(
    data: AppData,
    vm: MainViewModel,
    bankIssues: List<BankConnectionIssue>,
    onConnectBank: () -> Unit,
    onReconnectBank: (String) -> Unit,
    modifier: Modifier = Modifier,
    onEditManual: (Account) -> Unit
) {
    val ordered = AccountFinance.sortAccounts(data.accounts, data.accountPreferences)
    val cash = CashPosition.calculate(data.accounts, data.accountPreferences, data.reservedFunds)
    var editingPreference by remember { mutableStateOf<Account?>(null) }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyMiniCard("Total Money", cash.totalMoney, Modifier.weight(1f))
                MoneyMiniCard("Spending Money", cash.spendingMoney, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyMiniCard("Reserved", cash.reservedMoney, Modifier.weight(1f))
                MoneyMiniCard("Free Savings", cash.freeSavings, Modifier.weight(1f))
            }
        }
        item { Button(onClick = onConnectBank) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Connect bank") } }
        items(bankIssues, key = { "issue-${it.itemId}" }) { issue ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(issue.label ?: "Bank connection", style = MaterialTheme.typography.titleMedium)
                    Text(if (issue.requiresReconnect) "Sign in to this bank again to resume syncing." else issue.message, color = MaterialTheme.colorScheme.error)
                    if (issue.requiresReconnect) Button(onClick = { onReconnectBank(issue.itemId) }) { Text("Reconnect bank") }
                }
            }
        }
        items(ordered, key = { AccountFinance.stableKey(it) }) { account ->
            val originalIndex = data.accounts.indexOfFirst { AccountFinance.stableKey(it) == AccountFinance.stableKey(account) }.coerceAtLeast(0)
            val pref = AccountFinance.preferenceFor(account, data.accountPreferences, originalIndex)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(AccountFinance.displayName(account, data.accountPreferences, originalIndex), style = MaterialTheme.typography.titleMedium)
                            Text(accountRoleLabel(pref.role) + if (account.mask.isNotBlank()) " ••••${account.mask}" else "")
                            Text(if (account.source == AccountSource.PLAID) "Bank connected" else "Manual account", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(money(account.balance), style = MaterialTheme.typography.titleLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = {}, label = { Text(if (pref.includeInSpending) "Spendable" else "Not spendable") })
                        AssistChip(onClick = {}, label = { Text(if (pref.includeInTotal) "In total" else "Excluded") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { vm.moveAccount(pref.accountKey, -1) }) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                        IconButton(onClick = { vm.moveAccount(pref.accountKey, 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                        TextButton(onClick = { editingPreference = account }) { Text("Money settings") }
                        if (account.source == AccountSource.MANUAL) TextButton(onClick = { onEditManual(account) }) { Text("Edit") }
                    }
                }
            }
        }
    }

    editingPreference?.let { account ->
        AccountPreferenceDialog(data, account, onDismiss = { editingPreference = null }) {
            vm.updateAccountPreference(it)
            editingPreference = null
        }
    }
}

@Composable
private fun AccountPreferenceDialog(data: AppData, account: Account, onDismiss: () -> Unit, onSave: (AccountPreference) -> Unit) {
    val index = data.accounts.indexOfFirst { AccountFinance.stableKey(it) == AccountFinance.stableKey(account) }.coerceAtLeast(0)
    val current = AccountFinance.preferenceFor(account, data.accountPreferences, index)
    var role by remember { mutableStateOf(current.role) }
    var inTotal by remember { mutableStateOf(current.includeInTotal) }
    var spendable by remember { mutableStateOf(current.includeInSpending) }
    var name by remember { mutableStateOf(current.customName ?: account.name) }
    var roleMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account money settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedButton(onClick = { roleMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Role: ${accountRoleLabel(role)}") }
                    DropdownMenu(roleMenu, { roleMenu = false }) {
                        AccountRole.entries.forEach { value -> DropdownMenuItem(text = { Text(accountRoleLabel(value)) }, onClick = { role = value; roleMenu = false }) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Include in Total Money"); Switch(inTotal, { inTotal = it }) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Include in Spending Money"); Switch(spendable, { spendable = it }) }
                Text("You can keep savings visible in Total Money without treating it as spendable cash.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onSave(current.copy(role = role, includeInTotal = inTotal, includeInSpending = spendable, customName = name.trim().takeIf { it.isNotBlank() })) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun TransactionsScreen(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var showAdd by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val all = (data.plaidTransactions + data.manualTransactions).sortedByDescending { it.dateIso }
    val visible = all.filter { query.isBlank() || it.name.contains(query, true) || it.merchantName.orEmpty().contains(query, true) || it.category.contains(query, true) }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search transactions") }, modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = { showAdd = true }) { Text("+ Manual") }
            }
        }
        if (visible.isEmpty()) item { Text("No transactions yet. Connected bank activity will appear here automatically.") }
        items(visible, key = { it.id }) { tx ->
            ListItem(
                headlineContent = { Text(tx.merchantName ?: tx.name) },
                supportingContent = { Text("${tx.dateIso} • ${tx.category}${if (tx.type == TransactionType.TRANSFER) " • Transfer" else ""}") },
                trailingContent = { Text((if (tx.type == TransactionType.INCOME) "+" else if (tx.type == TransactionType.EXPENSE) "−" else "") + money(tx.amount)) }
            )
            if (tx.source == TransactionSource.MANUAL) TextButton(onClick = { vm.deleteManualTransaction(tx.id) }) { Text("Delete manual transaction") }
        }
    }
    if (showAdd) TransactionDialog(data, { showAdd = false }) { vm.addManualTransaction(it); showAdd = false }
}

@Composable
private fun TransactionDialog(data: AppData, onDismiss: () -> Unit, onSave: (FinanceTransaction) -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Other") }
    var type by remember { mutableStateOf(TransactionType.EXPENSE) }
    var accountKey by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add manual transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") })
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount") })
                SimpleEnumPicker("Type", type, TransactionType.entries) { type = it }
                OutlinedTextField(category, { category = it }, label = { Text("Category") })
                SimpleAccountPicker(data, accountKey, "Account") { accountKey = it }
            }
        },
        confirmButton = { Button(onClick = { amount.toDoubleOrNull()?.let { onSave(FinanceTransaction(name = name.ifBlank { "Transaction" }, amount = it, type = type, category = category.ifBlank { "Other" }, accountKey = accountKey, excludedFromSpending = type == TransactionType.TRANSFER)) } }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun BudgetsScreen(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var showAdd by remember { mutableStateOf(false) }
    val tx = data.plaidTransactions + data.manualTransactions
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Button(onClick = { showAdd = true }) { Text("+ Budget") } }
        if (data.budgets.isEmpty()) item { Text("Create budgets by category with weekly, biweekly, monthly, yearly, or custom periods.") }
        items(data.budgets, key = { it.id }) { budget ->
            val spent = budgetSpent(budget, tx)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(budget.name, style = MaterialTheme.typography.titleMedium)
                    Text("${budget.category} • ${budget.period.name.lowercase().replaceFirstChar { it.uppercase() }}")
                    LinearProgressIndicator(progress = { if (budget.amount <= 0.0) 0f else (spent / budget.amount).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text("${money(spent)} of ${money(budget.amount)} • ${money(budget.amount - spent)} remaining")
                    if (budget.rollover) Text("Rollover enabled", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.deleteBudget(budget.id) }) { Text("Delete") }
                }
            }
        }
    }
    if (showAdd) BudgetDialog({ showAdd = false }) { vm.addBudget(it); showAdd = false }
}

@Composable
private fun BudgetDialog(onDismiss: () -> Unit, onSave: (Budget) -> Unit) {
    var name by remember { mutableStateOf("") }; var category by remember { mutableStateOf("Groceries") }; var amount by remember { mutableStateOf("") }; var period by remember { mutableStateOf(BudgetPeriod.MONTHLY) }; var rollover by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add budget") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Budget name") })
            OutlinedTextField(category, { category = it }, label = { Text("Category") })
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount") })
            SimpleEnumPicker("Period", period, BudgetPeriod.entries) { period = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Rollover unused amount"); Switch(rollover, { rollover = it }) }
        }
    }, confirmButton = { Button(onClick = { amount.toDoubleOrNull()?.let { onSave(Budget(name = name.ifBlank { category }, category = category.ifBlank { "Other" }, amount = it, period = period, rollover = rollover)) } }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun DebtScreen(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var showAdd by remember { mutableStateOf(false) }
    var strategy by remember { mutableStateOf(DebtPayoffStrategy.AVALANCHE) }
    val ordered = DebtPlanner.prioritize(data.debts, strategy)
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(strategy == DebtPayoffStrategy.AVALANCHE, { strategy = DebtPayoffStrategy.AVALANCHE }, { Text("Avalanche") })
                FilterChip(strategy == DebtPayoffStrategy.SNOWBALL, { strategy = DebtPayoffStrategy.SNOWBALL }, { Text("Snowball") })
                Spacer(Modifier.weight(1f)); Button(onClick = { showAdd = true }) { Text("+ Debt") }
            }
        }
        item { Text("Total debt: ${money(data.debts.filter { it.active }.sumOf { it.balance })}", style = MaterialTheme.typography.titleLarge) }
        if (ordered.isEmpty()) item { Text("Add credit cards, loans, or a mortgage manually. Nothing is created automatically.") }
        items(ordered, key = { it.id }) { debt ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(debt.name, style = MaterialTheme.typography.titleMedium)
                    Text(debtTypeLabel(debt.type) + " • ${money(debt.balance)}")
                    Text("APR ${"%.2f".format(debt.aprPercent)}% • Minimum ${money(debt.minimumPayment)}")
                    TextButton(onClick = { vm.deleteDebt(debt.id) }) { Text("Delete") }
                }
            }
        }
    }
    if (showAdd) DebtDialog(data, { showAdd = false }) { vm.addDebt(it); showAdd = false }
}

@Composable
private fun DebtDialog(data: AppData, onDismiss: () -> Unit, onSave: (Debt) -> Unit) {
    var name by remember { mutableStateOf("") }; var balance by remember { mutableStateOf("") }; var apr by remember { mutableStateOf("") }; var minimum by remember { mutableStateOf("") }; var type by remember { mutableStateOf(DebtType.CREDIT_CARD) }; var accountKey by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add debt") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") })
            SimpleEnumPicker("Type", type, DebtType.entries) { type = it }
            OutlinedTextField(balance, { balance = it }, label = { Text("Current balance") })
            OutlinedTextField(apr, { apr = it }, label = { Text("APR %") })
            OutlinedTextField(minimum, { minimum = it }, label = { Text("Minimum payment") })
            SimpleAccountPicker(data, accountKey, "Linked account (optional)") { accountKey = it }
        }
    }, confirmButton = { Button(onClick = { balance.toDoubleOrNull()?.let { onSave(Debt(name = name.ifBlank { debtTypeLabel(type) }, type = type, balance = it, aprPercent = apr.toDoubleOrNull() ?: 0.0, minimumPayment = minimum.toDoubleOrNull() ?: 0.0, linkedAccountKey = accountKey)) } }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun GoalsScreen(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var showAdd by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Button(onClick = { showAdd = true }) { Text("+ Savings goal") } }
        if (data.savingsGoals.isEmpty()) item { Text("Create goals for emergency savings, vacation, Christmas, repairs, or anything else.") }
        items(data.savingsGoals, key = { it.id }) { goal ->
            val progress = if (goal.targetAmount <= 0.0) 0f else (goal.currentAmount / goal.targetAmount).coerceIn(0.0, 1.0).toFloat()
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(goal.name, style = MaterialTheme.typography.titleMedium); LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()); Text("${money(goal.currentAmount)} of ${money(goal.targetAmount)}"); if (goal.contributionPerPaycheck > 0) Text("${money(goal.contributionPerPaycheck)} per paycheck"); TextButton(onClick = { vm.deleteGoal(goal.id) }) { Text("Delete") }
            } }
        }
    }
    if (showAdd) GoalDialog(data, { showAdd = false }) { vm.addGoal(it); showAdd = false }
}

@Composable
private fun GoalDialog(data: AppData, onDismiss: () -> Unit, onSave: (SavingsGoal) -> Unit) {
    var name by remember { mutableStateOf("") }; var target by remember { mutableStateOf("") }; var current by remember { mutableStateOf("") }; var perCheck by remember { mutableStateOf("") }; var accountKey by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add savings goal") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Goal name") }); OutlinedTextField(target, { target = it }, label = { Text("Target amount") }); OutlinedTextField(current, { current = it }, label = { Text("Current amount") }); OutlinedTextField(perCheck, { perCheck = it }, label = { Text("Contribution per paycheck") }); SimpleAccountPicker(data, accountKey, "Linked account") { accountKey = it }
    } }, confirmButton = { Button(onClick = { target.toDoubleOrNull()?.let { onSave(SavingsGoal(name = name.ifBlank { "Savings goal" }, targetAmount = it, currentAmount = current.toDoubleOrNull() ?: 0.0, linkedAccountKey = accountKey, contributionPerPaycheck = perCheck.toDoubleOrNull() ?: 0.0)) } }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ReservedFundsScreen(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var showAdd by remember { mutableStateOf(false) }
    val cash = CashPosition.calculate(data.accounts, data.accountPreferences, data.reservedFunds)
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("Reserved Money", style = MaterialTheme.typography.labelLarge); Text(money(cash.reservedMoney), style = MaterialTheme.typography.headlineMedium) }; Button(onClick = { showAdd = true }) { Text("+ Reserve") } } }
        item { Text("Reserved funds still belong to you and stay in Total Money, but BillNest keeps them out of free spending/savings where appropriate.") }
        if (data.reservedFunds.isEmpty()) item { Text("No reserves yet. Create one manually for a mortgage, insurance, taxes, repairs, or anything else.") }
        items(data.reservedFunds, key = { it.id }) { fund ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(fund.name, style = MaterialTheme.typography.titleMedium); Text("Reserved: ${money(fund.reservedAmount)}"); if (fund.contributionPerPaycheck > 0) Text("Add ${money(fund.contributionPerPaycheck)} per paycheck"); data.accounts.firstOrNull { AccountFinance.stableKey(it) == fund.accountKey }?.let { Text("Account: ${AccountFinance.displayName(it, data.accountPreferences, data.accounts.indexOf(it))}") }; TextButton(onClick = { vm.deleteReservedFund(fund.id) }) { Text("Delete") }
            } }
        }
    }
    if (showAdd) ReservedFundDialog(data, { showAdd = false }) { vm.addReservedFund(it); showAdd = false }
}

@Composable
private fun ReservedFundDialog(data: AppData, onDismiss: () -> Unit, onSave: (ReservedFund) -> Unit) {
    var name by remember { mutableStateOf("") }; var reserved by remember { mutableStateOf("") }; var perCheck by remember { mutableStateOf("") }; var target by remember { mutableStateOf("") }; var accountKey by remember { mutableStateOf<String?>(null) }; var linkedBill by remember { mutableStateOf<String?>(null) }; var linkedPayday by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add reserved fund") }, text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 520.dp)) {
        item { OutlinedTextField(name, { name = it }, label = { Text("Reserve name") }) }; item { SimpleAccountPicker(data, accountKey, "Account") { accountKey = it } }; item { OutlinedTextField(reserved, { reserved = it }, label = { Text("Amount currently reserved") }) }; item { OutlinedTextField(perCheck, { perCheck = it }, label = { Text("Contribution per paycheck") }) }; item { OutlinedTextField(target, { target = it }, label = { Text("Target amount (optional)") }) }; item { SimpleStringPicker("Linked bill (optional)", linkedBill, data.bills.map { it.id to it.name }) { linkedBill = it } }; item { SimpleStringPicker("Linked payday (optional)", linkedPayday, data.paydays.map { it.id to it.label }) { linkedPayday = it } }
    } }, confirmButton = { Button(onClick = { val key = accountKey; val amount = reserved.toDoubleOrNull(); if (key != null && amount != null) onSave(ReservedFund(name = name.ifBlank { "Reserved fund" }, accountKey = key, reservedAmount = amount, contributionPerPaycheck = perCheck.toDoubleOrNull() ?: 0.0, targetAmount = target.toDoubleOrNull(), linkedBillId = linkedBill, linkedPaydayId = linkedPayday)) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun V2SettingsScreen(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier, onHousehold: () -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Household", style = MaterialTheme.typography.titleMedium); Text("Your household login restores shared bills, account preferences, budgets, debts, goals, reserves, and bank connections after reinstall."); Button(onClick = onHousehold) { Text("Household & sync") } } } }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Bank security", style = MaterialTheme.typography.titleMedium); Text("Plaid access tokens stay on the BillNest Cloudflare backend and are not stored in the app.") } } }
        item { Text("BillNest v2 finance preview") }
    }
}

@Composable
private fun <T : Enum<T>> SimpleEnumPicker(label: String, value: T, values: List<T>, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: ${value.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }}") }
        DropdownMenu(expanded, { expanded = false }) { values.forEach { option -> DropdownMenuItem(text = { Text(option.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) }, onClick = { onChange(option); expanded = false }) } }
    }
}

@Composable
private fun SimpleAccountPicker(data: AppData, value: String?, label: String, onChange: (String?) -> Unit) {
    val options = AccountFinance.sortAccounts(data.accounts, data.accountPreferences)
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { AccountFinance.stableKey(it) == value }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: ${selected?.name ?: "None"}") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = { onChange(null); expanded = false })
            options.forEachIndexed { index, account -> DropdownMenuItem(text = { Text(AccountFinance.displayName(account, data.accountPreferences, index)) }, onClick = { onChange(AccountFinance.stableKey(account)); expanded = false }) }
        }
    }
}

@Composable
private fun SimpleStringPicker(label: String, value: String?, options: List<Pair<String, String>>, onChange: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == value }?.second ?: "None"
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $display") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = { onChange(null); expanded = false })
            options.forEach { option -> DropdownMenuItem(text = { Text(option.second) }, onClick = { onChange(option.first); expanded = false }) }
        }
    }
}

private fun budgetSpent(budget: Budget, transactions: List<FinanceTransaction>): Double {
    val today = LocalDate.now()
    val start = when (budget.period) {
        BudgetPeriod.WEEKLY -> today.minusDays((today.dayOfWeek.value - 1).toLong())
        BudgetPeriod.BIWEEKLY -> LocalDate.parse(budget.startDateIso).let { anchor -> anchor.plusWeeks(((ChronoUnit.WEEKS.between(anchor, today).coerceAtLeast(0)) / 2) * 2) }
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

private fun accountRoleLabel(role: AccountRole): String = when (role) {
    AccountRole.SPENDING -> "Spending"
    AccountRole.SAVINGS -> "Savings"
    AccountRole.CREDIT_DEBT -> "Credit / Debt"
    AccountRole.OTHER -> "Other"
}

private fun debtTypeLabel(type: DebtType): String = when (type) {
    DebtType.CREDIT_CARD -> "Credit card"
    DebtType.PERSONAL_LOAN -> "Personal loan"
    DebtType.AUTO_LOAN -> "Auto loan"
    DebtType.STUDENT_LOAN -> "Student loan"
    DebtType.MORTGAGE -> "Mortgage"
    DebtType.OTHER -> "Other debt"
}

private fun money(value: Double): String = NumberFormat.getCurrencyInstance().format(value)
