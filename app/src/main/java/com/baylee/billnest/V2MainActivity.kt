package com.baylee.billnest

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.baylee.billnest.data.BankApi
import com.baylee.billnest.data.BankConnectionIssue
import com.baylee.billnest.data.BillNestApp
import com.baylee.billnest.data.FinanceAutomationCoordinator
import com.baylee.billnest.data.TransactionApi
import com.baylee.billnest.ui.MainViewModel
import com.baylee.billnest.ui.theme.BillNestTheme
import com.baylee.billnest.ui.v2.V2AppShell
import com.plaid.link.OpenPlaidLink
import com.plaid.link.Plaid
import com.plaid.link.configuration.linkTokenConfiguration
import com.plaid.link.result.LinkExit
import com.plaid.link.result.LinkSuccess
import kotlinx.coroutines.launch

class V2MainActivity : FragmentActivity() {
    private val vm by viewModels<MainViewModel> {
        val repo = (application as BillNestApp).repo
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo) as T
        }
    }

    private val transactionApi = TransactionApi()
    private val automation by lazy { FinanceAutomationCoordinator(vm.repo) }
    private var bankIssues = androidx.compose.runtime.mutableStateOf<List<BankConnectionIssue>>(emptyList())
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
                    bankIssues = bankIssues.value,
                    onConnectBank = ::connectBank,
                    onReconnectBank = ::reconnectBank,
                    onRefresh = { lifecycleScope.launch { refreshAllFinanceData(showToast = true) } },
                    onHousehold = { startActivity(Intent(this, HouseholdActivity::class.java)) },
                    onSignOut = ::signOut
                )
            }
        }
        lifecycleScope.launch { refreshAllFinanceData(showToast = false) }
    }

    override fun onResume() {
        super.onResume()
        val app = application as BillNestApp
        if (app.sessionStore.load() != null) {
            lifecycleScope.launch { refreshAllFinanceData(showToast = false) }
        }
    }

    private suspend fun refreshAllFinanceData(showToast: Boolean) {
        val app = application as BillNestApp
        val session = app.sessionStore.load() ?: return
        BankApi.setSessionToken(session.sessionToken)
        val data = vm.data.value

        runCatching { BankApi.fetchAccounts(data.backendUrl, data.backendApiKey) }
            .onSuccess { result ->
                bankIssues.value = result.issues
                vm.syncPlaidAccounts(result.accounts, result.connectedItems)
            }

        runCatching { transactionApi.syncAndList(data.backendUrl, session.sessionToken) }
            .onSuccess { (syncResult, transactions) ->
                bankIssues.value = (bankIssues.value + syncResult.issues).distinctBy { it.itemId }
                vm.setPlaidTransactions(transactions)
                automation.processNewTransactions()
            }

        runCatching { app.householdSync.syncNow() }

        if (showToast) {
            when {
                bankIssues.value.any { it.requiresReconnect } -> toast("A bank needs to be reconnected")
                bankIssues.value.isNotEmpty() -> toast("Some bank connections need attention")
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

    private fun signOut() {
        val app = application as BillNestApp
        app.sessionStore.clear()
        BankApi.setSessionToken(null)
        startActivity(Intent(this, AuthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
