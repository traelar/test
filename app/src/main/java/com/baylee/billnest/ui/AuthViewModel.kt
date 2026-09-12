package com.baylee.billnest.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baylee.billnest.data.AuthApi
import com.baylee.billnest.data.BankApi
import com.baylee.billnest.data.BillRepository
import com.baylee.billnest.data.HouseholdSyncRepository
import com.baylee.billnest.data.HttpApiException
import com.baylee.billnest.data.SecureSessionStore
import com.baylee.billnest.model.AuthUiState
import com.baylee.billnest.model.SessionData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun resolveBootstrapServerKey(savedKey: String, enteredKey: String): String {
    val entered = enteredKey.trim()
    if (entered.isNotBlank()) return entered
    val saved = savedKey.trim()
    if (saved.isNotBlank()) return saved
    error("Enter the existing BillNest bank server key to set up the first household owner.")
}

class AuthViewModel(
    private val repo: BillRepository,
    private val sessionStore: SecureSessionStore,
    private val householdSync: HouseholdSyncRepository? = null,
    private val api: AuthApi = AuthApi()
) : ViewModel() {
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = sessionStore.load()
            if (cached == null) {
                BankApi.setSessionToken(null)
                _state.value = AuthUiState.SignedOut
            } else {
                BankApi.setSessionToken(cached.sessionToken)
                _state.value = AuthUiState.SignedIn(cached)
                runCatching { householdSync?.migrateLegacyIfNeeded(cached) }
                verifyCachedSession(cached)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun bootstrap(username: String, password: String, householdName: String, enteredServerKey: String = "") {
        launchAuth {
            val data = repo.data.value
            val bootstrapKey = resolveBootstrapServerKey(data.backendApiKey, enteredServerKey)
            val session = api.bootstrap(
                backendUrl = data.backendUrl,
                legacyApiKey = bootstrapKey,
                username = username,
                password = password,
                householdName = householdName
            )
            if (data.backendApiKey != bootstrapKey) {
                repo.setBackendApiKey(bootstrapKey)
            }
            session
        }
    }

    fun login(username: String, password: String) {
        launchAuth {
            api.login(repo.data.value.backendUrl, username, password)
        }
    }

    fun registerWithInvite(inviteCode: String, username: String, password: String) {
        launchAuth {
            api.registerWithInvite(repo.data.value.backendUrl, inviteCode, username, password)
        }
    }

    fun logout() {
        val signedIn = (_state.value as? AuthUiState.SignedIn)?.session ?: return
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { api.logout(repo.data.value.backendUrl, signedIn.sessionToken) }
            BankApi.setSessionToken(null)
            sessionStore.clear()
            _state.value = AuthUiState.SignedOut
            _busy.value = false
        }
    }

    private fun launchAuth(block: suspend () -> SessionData) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block() }
                .onSuccess { session ->
                    sessionStore.save(session)
                    BankApi.setSessionToken(session.sessionToken)
                    _state.value = AuthUiState.SignedIn(session)
                    runCatching { householdSync?.migrateLegacyIfNeeded(session) }
                }
                .onFailure { failure ->
                    _error.value = failure.message ?: "Could not sign in to BillNest"
                }
            _busy.value = false
        }
    }

    private suspend fun verifyCachedSession(cached: SessionData) {
        runCatching {
            api.fetchMe(repo.data.value.backendUrl, cached.sessionToken)
        }.onSuccess { refreshed ->
            sessionStore.save(refreshed)
            BankApi.setSessionToken(refreshed.sessionToken)
            _state.value = AuthUiState.SignedIn(refreshed)
            runCatching { householdSync?.syncNow() }
        }.onFailure { failure ->
            if (failure is HttpApiException && failure.statusCode == 401) {
                BankApi.setSessionToken(null)
                sessionStore.clear()
                _state.value = AuthUiState.SignedOut
            }
        }
    }
}
