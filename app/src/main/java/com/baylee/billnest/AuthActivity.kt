package com.baylee.billnest

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baylee.billnest.data.AuthApi
import com.baylee.billnest.model.AuthUiState
import com.baylee.billnest.ui.AuthViewModel
import com.baylee.billnest.ui.auth.AuthGate
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
        when (val current = state) {
            AuthUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            AuthUiState.SignedOut -> AuthGate(vm)
            is AuthUiState.SignedIn -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                LaunchedEffect(current.session.sessionToken) {
                    startActivity(Intent(this@AuthActivity, MainActivity::class.java))
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
