package com.baylee.billnest.ui.v2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baylee.billnest.*
import com.baylee.billnest.data.BankConnectionIssue
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal enum class V2Destination(val label: String) {
    DASHBOARD("Dashboard"),
    ACCOUNTS("Accounts"),
    TRANSACTIONS("Transactions"),
    REVIEW("Needs Review"),
    SUBSCRIPTIONS("Subscriptions"),
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
fun V2AppShell(
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(20.dp))
                Text("BillNest", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                Text("Shared household finance", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                V2Destination.entries.forEach { item ->
                    val badge = when (item) {
                        V2Destination.REVIEW -> data.billMatches.count { it.status == MatchStatus.NEEDS_REVIEW }
                        else -> 0
                    }
                    NavigationDrawerItem(
                        label = {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(item.label)
                                if (badge > 0) Badge { Text(badge.toString()) }
                            }
                        },
                        icon = { Icon(destinationIcon(item), null) },
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
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "Open menu") }
                    },
                    actions = {
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
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
            val screenModifier = Modifier.padding(padding)
            when (destination) {
                V2Destination.DASHBOARD -> V2Dashboard(data, screenModifier, onOpenReview = { destination = V2Destination.REVIEW })
                V2Destination.ACCOUNTS -> V2AccountsScreen(data, vm, bankIssues, onConnectBank, onReconnectBank, screenModifier) { editingManualAccount = it }
                V2Destination.TRANSACTIONS -> TransactionsScreen(data, vm, screenModifier)
                V2Destination.REVIEW -> ReviewMatchesScreen(data, vm, screenModifier)
                V2Destination.SUBSCRIPTIONS -> SubscriptionsScreen(data, vm, screenModifier)
                V2Destination.BILLS -> BillsPage(data, vm, screenModifier, onEdit = { editingBill = it })
                V2Destination.BUDGETS -> BudgetsScreen(data, vm, screenModifier)
                V2Destination.DEBT -> DebtScreen(data, vm, screenModifier)
                V2Destination.GOALS -> GoalsScreen(data, vm, screenModifier)
                V2Destination.RESERVED -> ReservedFundsScreen(data, vm, screenModifier)
                V2Destination.INCOME -> IncomePage(data, vm, screenModifier, onEdit = { editingPayday = it })
                V2Destination.CALENDAR -> CalendarPage(data, screenModifier)
                V2Destination.HOUSEHOLD -> Unit
                V2Destination.SETTINGS -> V2SettingsScreen(data, vm, screenModifier, onHousehold)
            }
        }
    }

    if (showBill) BillEditorDialog(null, data.accounts, { showBill = false }) { vm.add(it); showBill = false }
    editingBill?.let { bill -> BillEditorDialog(bill, data.accounts, { editingBill = null }) { vm.updateBill(it); editingBill = null } }
    if (showPayday) PaydayDialog(null, { showPayday = false }) { vm.addPayday(it); showPayday = false }
    editingPayday?.let { payday -> PaydayDialog(payday, { editingPayday = null }) { vm.updatePayday(it); editingPayday = null } }
    if (showManualAccount) AccountDialog(null, { showManualAccount = false }) { vm.addAccount(it); showManualAccount = false }
    editingManualAccount?.let { account -> AccountDialog(account, { editingManualAccount = null }) { vm.updateAccount(it); editingManualAccount = null } }
}

@Composable
private fun V2Dashboard(data: AppData, modifier: Modifier, onOpenReview: () -> Unit) {
    val cash = CashPosition.calculate(data.accounts, data.accountPreferences, data.reservedFunds)
    val today = LocalDate.now()
    val unpaid = data.bills.filter { !it.isPaidFor() }
    val due30 = unpaid.filter { !it.dueDate().isBefore(today) && ChronoUnit.DAYS.between(today, it.dueDate()) <= 30 }
    val due30Total = due30.sumOf { it.amount }
    val spendAfterBills = cash.availableSpending - due30Total
    val needsReview = data.billMatches.count { it.status == MatchStatus.NEEDS_REVIEW }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Text("Available spending: ${money(cash.availableSpending)}")
                    Text("Bills next 30 days: ${money(due30Total)}")
                }
            }
        }
        if (needsReview > 0) item {
            Button(onClick = onOpenReview, modifier = Modifier.fillMaxWidth()) { Text("Review $needsReview transaction match${if (needsReview == 1) "" else "es"}") }
        }
        item { Text("Upcoming bills", style = MaterialTheme.typography.titleLarge) }
        if (due30.isEmpty()) item { Text("No bills due in the next 30 days.") }
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
internal fun MoneyMiniCard(label: String, value: Double, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(money(value), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun V2SettingsScreen(data: AppData, vm: MainViewModel, modifier: Modifier, onHousehold: () -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Household", style = MaterialTheme.typography.titleMedium)
                    Text("Your signed-in household restores shared finance setup and bank connections after reinstall.")
                    Button(onClick = onHousehold) { Text("Household & sync") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Bank security", style = MaterialTheme.typography.titleMedium)
                    Text("Bank access tokens stay on the BillNest Cloudflare backend. They are never stored in the APK.")
                    Text(if (data.plaidConnected) "Bank connections are linked to this household." else "No active bank data loaded on this device yet.")
                }
            }
        }
        item { Text("BillNest v2 finance preview") }
    }
}

private fun destinationIcon(item: V2Destination) = when (item) {
    V2Destination.DASHBOARD -> Icons.Default.Home
    V2Destination.ACCOUNTS -> Icons.Default.AccountBalance
    V2Destination.TRANSACTIONS -> Icons.Default.ReceiptLong
    V2Destination.REVIEW -> Icons.Default.Rule
    V2Destination.SUBSCRIPTIONS -> Icons.Default.Repeat
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
