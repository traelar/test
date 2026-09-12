package com.baylee.billnest

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.baylee.billnest.data.BankApi
import com.baylee.billnest.ui.household.HouseholdSettings
import com.baylee.billnest.ui.theme.BillNestTheme

class HouseholdActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BillNestApp
        setContent {
            BillNestTheme {
                HouseholdRoot(app)
            }
        }
    }

    @Composable
    private fun HouseholdRoot(app: BillNestApp) {
        val session = app.sessionStore.load()
        if (session == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Sign in to BillNest first")
            }
            return
        }

        HouseholdSettings(
            session = session,
            backendUrl = app.repo.data.value.backendUrl,
            syncRepository = app.householdSync,
            onBankConnectionsChanged = {
                val data = app.repo.data.value
                val refresh = BankApi.fetchAccounts(data.backendUrl, data.backendApiKey)
                app.repo.syncPlaidAccounts(refresh.accounts)
            },
            onBack = { finish() }
        )
    }
}
