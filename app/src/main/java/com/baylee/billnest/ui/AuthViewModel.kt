package com.baylee.billnest.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baylee.billnest.data.AuthApi
import com.baylee.billnest.data.BillRepository
import com.baylee.billnest.data.HttpApiException
import com.baylee.billnest.data.SecureSessionStore
import com.baylee.billnest.model.AuthUiState
import com.baylee.billnest.model.SessionData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(
    private val repo: BillRepository,
    private val sessionStore: SecureSessionStore,
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
                _state.value = AuthUiState.SignedOut
            } else {
                _state.value = AuthUiState.SignedIn(cached)
                verifyCachedSession(cached)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun bootstrap(username: String, password: String, householdName: String) {
        launchAuth {
            val data = repo.data.value
            if (data.backendApiKey.isBlank()) {
                error("This phone does not have the existing BillNest bank server key needed for first-owner setup.")
            }
            api.bootstrap(
                backendUrl = data.backendUrl,
                legacyApiKey = data.backendApiKey,
                username = username,
                password = password,
                householdName = householdName
            )
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
                    _state.value = AuthUiState.SignedIn(session)
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
            _state.value = AuthUiState.SignedIn(refreshed)
        }.onFailure { failure ->
            if (failure is HttpApiException && failure.statusCode == 401) {
                sessionStore.clear()
                _state.value = AuthUiState.SignedOut
            }
        }
    }
}
