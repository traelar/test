package com.baylee.billnest

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baylee.billnest.data.AuthApi
import com.baylee.billnest.model.AuthUiState
import com.baylee.billnest.model.SessionData
import com.baylee.billnest.ui.AuthViewModel
import com.baylee.billnest.ui.auth.AuthGate
import com.baylee.billnest.ui.theme.BillNestColors
import com.baylee.billnest.ui.theme.BillNestTheme

class AuthActivity : FragmentActivity() {
    private val authVm by viewModels<AuthViewModel> {
        val app = application as BillNestApp
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AuthViewModel(app.repo, app.sessionStore, app.householdSync, AuthApi()) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BillNestTheme {
                AuthRoot(authVm)
            }
        }
    }

    @Composable
    private fun AuthRoot(vm: AuthViewModel) {
        val state by vm.state.collectAsStateWithLifecycle()
        var launchedMain by rememberSaveable { mutableStateOf(false) }

        when (val current = state) {
            AuthUiState.Loading -> Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            AuthUiState.SignedOut -> {
                launchedMain = false
                AuthGate(vm)
            }
            is AuthUiState.SignedIn -> {
                if (!launchedMain) {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    LaunchedEffect(current.session.sessionToken) {
                        launchedMain = true
                        startActivity(Intent(this@AuthActivity, FinanceActivity::class.java))
                    }
                } else {
                    SignedInHub(current.session, vm)
                }
            }
        }
    }

    @Composable
    private fun SignedInHub(session: SessionData, vm: AuthViewModel) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BillNestColors.cardRaised),
                border = BorderStroke(1.dp, BillNestColors.border),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("BillNest", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(session.householdName.ifBlank { "Your household" }, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Signed in as ${session.displayLabel.ifBlank { session.username }}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { startActivity(Intent(this@AuthActivity, FinanceActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open BillNest") }
                    OutlinedButton(
                        onClick = { startActivity(Intent(this@AuthActivity, HouseholdActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Household & sync") }
                    OutlinedButton(
                        onClick = { vm.logout() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Sign out") }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing && intent.getBooleanExtra("forceSignOut", false)) {
            authVm.logout()
            intent.removeExtra("forceSignOut")
        }
    }
}
