package com.baylee.billnest

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.baylee.billnest.data.BankApi
import com.baylee.billnest.data.BankConnectionIssue
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel
import com.baylee.billnest.ui.theme.BillNestTheme
import com.plaid.link.OpenPlaidLink
import com.plaid.link.Plaid
import com.plaid.link.configuration.linkTokenConfiguration
import com.plaid.link.result.LinkExit
import com.plaid.link.result.LinkSuccess
import kotlinx.coroutines.launch

/** Current BillNest finance shell with the Alpha19 planning and insights experience. */
class FinanceActivity : FragmentActivity() {
    private val vm by viewModels<MainViewModel> {
        val repo = (application as BillNestApp).repo
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo) as T
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
                    val url = vm.data.value.backendUrl
                    runCatching {
                        if (reconnectItem == null) {
                            BankApi.exchangePublicToken(url, vm.data.value.backendApiKey, token!!, institution)
                        }
                        BankApi.fetchAccounts(url, vm.data.value.backendApiKey)
                    }.onSuccess { refresh ->
                        reconnectingItemId = null
                        bankIssues = refresh.issues
                        vm.syncPlaidAccounts(refresh.accounts, retainMissing = refresh.issues.isNotEmpty())
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
                BillNestHomeV2(
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
                this@FinanceActivity,
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
            runCatching { BankApi.createLinkToken(url, vm.data.value.backendApiKey) }
                .onSuccess { launchPlaid(it) }
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
            runCatching { BankApi.createUpdateLinkToken(url, vm.data.value.backendApiKey, itemId) }
                .onSuccess { linkToken ->
                    reconnectingItemId = itemId
                    launchPlaid(linkToken)
                }
                .onFailure {
                    reconnectingItemId = null
                    toast(it.message ?: "Could not start bank reconnect")
                }
        }
    }

    private fun refreshBanks(showSuccessToast: Boolean = true) {
        val url = vm.data.value.backendUrl
        if (url.isBlank()) {
            if (showSuccessToast) toast("Add your BillNest bank server address in Settings first")
            return
        }
        lifecycleScope.launch {
            runCatching { BankApi.fetchAccounts(url, vm.data.value.backendApiKey) }
                .onSuccess { refresh ->
                    bankIssues = refresh.issues
                    vm.syncPlaidAccounts(refresh.accounts, retainMissing = refresh.issues.isNotEmpty())
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
                .onFailure { if (showSuccessToast) toast(it.message ?: "Could not refresh bank balances") }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillNestHomeV2(
    vm: MainViewModel,
    bankIssues: List<BankConnectionIssue>,
    onConnectBank: () -> Unit,
    onReconnectBank: (String) -> Unit,
    onRefreshBanks: () -> Unit
) {
    val data by vm.data.collectAsStateWithLifecycle()
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
        "Dashboard", "Accounts", "Transactions", "Bills", "Budgets", "Debt",
        "Paycheck Plan", "Insights", "Savings / Goals", "Reserved Funds", "Subscriptions",
        "Income", "Calendar", "Household", "Settings"
    )
    var destination by remember { mutableStateOf("Dashboard") }
    val destinationHistory = remember { mutableStateListOf<String>() }

    BackHandler(enabled = drawerState.isOpen || destinationHistory.isNotEmpty() || destination != "Dashboard") {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            destinationHistory.isNotEmpty() -> destination = destinationHistory.removeAt(destinationHistory.lastIndex)
            destination != "Dashboard" -> destination = "Dashboard"
        }
    }

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
                                if (name != destination) {
                                    destinationHistory.add(destination)
                                    destination = name
                                }
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
                "Dashboard" -> DashboardV3(data, Modifier.padding(pad))
                "Bills" -> BillsPage(data, vm, Modifier.padding(pad), onEdit = { editingBill = it })
                "Accounts" -> AccountsPageV3(
                    data = data,
                    vm = vm,
                    bankIssues = bankIssues,
                    modifier = Modifier.padding(pad),
                    onEdit = { editingAccount = it },
                    onConnectBank = onConnectBank,
                    onReconnectBank = onReconnectBank,
                    onRefreshBanks = onRefreshBanks
                )
                "Transactions" -> TransactionsPageV3(data, vm, Modifier.padding(pad))
                "Budgets" -> BudgetsPageV2(data, vm, Modifier.padding(pad))
                "Debt" -> DebtPageV3(data, vm, Modifier.padding(pad))
                "Paycheck Plan" -> PaycheckPlanPageV3(data, Modifier.padding(pad))
                "Insights" -> InsightsPageV3(data, Modifier.padding(pad))
                "Savings / Goals" -> SavingsGoalsPage(data, vm, Modifier.padding(pad))
                "Reserved Funds" -> ReservedFundsPage(data, vm, Modifier.padding(pad))
                "Subscriptions" -> SubscriptionsPage(data, vm, Modifier.padding(pad))
                "Calendar" -> CalendarPage(data, Modifier.padding(pad))
                "Income" -> IncomePageV2(data, vm, Modifier.padding(pad), onEditPayday = { editingPayday = it })
                "Household" -> LaunchedEffect(Unit) { context.startActivity(Intent(context, HouseholdActivity::class.java)) }
                "Settings" -> SettingsPageV3(data, vm, Modifier.padding(pad))
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
    if (showAddPayday) {
        PaydayDialog(null, { showAddPayday = false }) {
            vm.addPayday(it)
            showAddPayday = false
        }
    }
    editingPayday?.let { payday ->
        PaydayDialog(payday, { editingPayday = null }) {
            vm.updatePayday(it)
            editingPayday = null
        }
    }
    if (showAddAccount) {
        AssetAccountDialog(null, { showAddAccount = false }) {
            vm.addAccount(it)
            showAddAccount = false
        }
    }
    editingAccount?.let { account ->
        AssetAccountDialog(account, { editingAccount = null }) {
            vm.updateAccount(it)
            editingAccount = null
        }
    }
}
