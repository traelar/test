package com.baylee.billnest.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baylee.billnest.ui.AuthViewModel

private enum class AuthMode { SIGN_IN, CREATE_ACCOUNT, JOIN }

@Composable
fun AuthGate(vm: AuthViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var householdName by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var setupKey by remember { mutableStateOf("") }

    Box(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("BillNest", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Shared household money, bills, accounts, and cash flow.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton("Sign in", mode == AuthMode.SIGN_IN, Modifier.weight(1f)) {
                        mode = AuthMode.SIGN_IN
                        vm.clearError()
                    }
                    ModeButton("Create", mode == AuthMode.CREATE_ACCOUNT, Modifier.weight(1f)) {
                        mode = AuthMode.CREATE_ACCOUNT
                        vm.clearError()
                    }
                    ModeButton("Join", mode == AuthMode.JOIN, Modifier.weight(1f)) {
                        mode = AuthMode.JOIN
                        vm.clearError()
                    }
                }

                Text(
                    when (mode) {
                        AuthMode.SIGN_IN -> "Sign in"
                        AuthMode.CREATE_ACCOUNT -> "Create account"
                        AuthMode.JOIN -> "Join household"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )

                when (mode) {
                    AuthMode.JOIN -> OutlinedTextField(
                        value = inviteCode,
                        onValueChange = { inviteCode = it.uppercase() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Household invite code") },
                        singleLine = true
                    )
                    AuthMode.CREATE_ACCOUNT -> {
                        OutlinedTextField(
                            value = householdName,
                            onValueChange = { householdName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Household name") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = setupKey,
                            onValueChange = { setupKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("First-owner setup key") },
                            supportingText = { Text("Enter it here if this phone does not already have it saved.") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                    AuthMode.SIGN_IN -> Unit
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Username") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Text(
                    "Use at least 10 characters. Your password is never stored in the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                Button(
                    onClick = {
                        when (mode) {
                            AuthMode.SIGN_IN -> vm.login(username, password)
                            AuthMode.JOIN -> vm.registerWithInvite(inviteCode, username, password)
                            AuthMode.CREATE_ACCOUNT -> vm.bootstrap(username, password, householdName, setupKey)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && username.isNotBlank() && password.length >= 10 &&
                        (mode != AuthMode.JOIN || inviteCode.isNotBlank()) &&
                        (mode != AuthMode.CREATE_ACCOUNT || householdName.isNotBlank())
                ) {
                    if (busy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                    } else {
                        Text(
                            when (mode) {
                                AuthMode.SIGN_IN -> "Sign in"
                                AuthMode.JOIN -> "Join household"
                                AuthMode.CREATE_ACCOUNT -> "Create account & household"
                            }
                        )
                    }
                }

                if (mode == AuthMode.CREATE_ACCOUNT) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "The setup key is only needed to authorize the first household owner. After that, use your BillNest username and password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, maxLines = 1) }
    }
}
