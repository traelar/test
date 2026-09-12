package com.baylee.billnest.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baylee.billnest.data.BankApi
import com.baylee.billnest.data.BankConnection
import com.baylee.billnest.data.HouseholdApi
import com.baylee.billnest.data.HouseholdSyncRepository
import com.baylee.billnest.model.HouseholdDetails
import com.baylee.billnest.model.HouseholdInvite
import com.baylee.billnest.model.SessionData
import kotlinx.coroutines.launch

@Composable
fun HouseholdSettings(
    session: SessionData,
    backendUrl: String,
    syncRepository: HouseholdSyncRepository,
    api: HouseholdApi = HouseholdApi(),
    onBankConnectionsChanged: suspend () -> Unit = {},
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<HouseholdDetails?>(null) }
    var invite by remember { mutableStateOf<HouseholdInvite?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var bankConnections by remember { mutableStateOf<List<BankConnection>>(emptyList()) }
    var bankBusy by remember { mutableStateOf(false) }
    var bankError by remember { mutableStateOf<String?>(null) }
    var disconnectTarget by remember { mutableStateOf<BankConnection?>(null) }

    fun refresh() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { api.fetchHousehold(backendUrl, session.sessionToken) }
                .onSuccess { details = it }
                .onFailure { error = it.message ?: "Could not load household" }
            busy = false
        }
    }

    fun refreshBankConnections() {
        if (!session.isOwner || bankBusy) return
        bankBusy = true
        bankError = null
        scope.launch {
            BankApi.setSessionToken(session.sessionToken)
            runCatching { BankApi.listBankConnections(backendUrl, "") }
                .onSuccess { bankConnections = it }
                .onFailure { bankError = it.message ?: "Could not load bank connections" }
            bankBusy = false
        }
    }

    LaunchedEffect(session.householdId) {
        BankApi.setSessionToken(session.sessionToken)
        runCatching { api.fetchHousehold(backendUrl, session.sessionToken) }
            .onSuccess { details = it }
            .onFailure { error = it.message ?: "Could not load household" }
        if (session.isOwner) {
            runCatching { BankApi.listBankConnections(backendUrl, "") }
                .onSuccess { bankConnections = it }
                .onFailure { bankError = it.message ?: "Could not load bank connections" }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Household", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(details?.name ?: session.householdName.ifBlank { "BillNest household" })
                }
                TextButton(onClick = onBack) { Text("Back") }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Signed in as ${session.displayLabel.ifBlank { session.username }}")
                    Text(if (session.isOwner) "Household owner" else "Household member")
                    Text("Sync conflicts needing review: ${syncRepository.conflictCount}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { refresh() }, enabled = !busy) { Text("Refresh") }
                        Button(
                            onClick = {
                                if (busy) return@Button
                                busy = true
                                error = null
                                status = null
                                scope.launch {
                                    runCatching { syncRepository.syncNow() }
                                        .onSuccess {
                                            status = if (it.conflictCount == 0) "Household is synced" else "Synced with ${it.conflictCount} item(s) needing review"
                                        }
                                        .onFailure { error = it.message ?: "Sync failed" }
                                    busy = false
                                }
                            },
                            enabled = !busy
                        ) { Text("Sync now") }
                    }
                    if (busy) CircularProgressIndicator()
                    status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        if (session.isOwner) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Invite a household member", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Invite codes are single-use and expire after 7 days.")
                        Button(
                            onClick = {
                                if (busy) return@Button
                                busy = true
                                error = null
                                scope.launch {
                                    runCatching { api.createInvite(backendUrl, session.sessionToken) }
                                        .onSuccess { invite = it }
                                        .onFailure { error = it.message ?: "Could not create invite" }
                                    busy = false
                                }
                            },
                            enabled = !busy
                        ) { Text("Create invite code") }
                        invite?.let {
                            Text(it.inviteCode, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Expires: ${it.expiresAt}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Bank connections", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("Remove duplicate bank links here. Each connection can contain several bank accounts.", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { refreshBankConnections() }, enabled = !bankBusy) { Text("Refresh") }
                }
            }

            if (bankBusy && bankConnections.isEmpty()) {
                item { CircularProgressIndicator() }
            }
            bankError?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            if (!bankBusy && bankConnections.isEmpty() && bankError == null) {
                item { Text("No connected banks found.") }
            }
            items(bankConnections, key = { it.itemId }) { connection ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(connection.label?.takeIf { it.isNotBlank() } ?: "Connected bank", fontWeight = FontWeight.SemiBold)
                            if (connection.createdAt.isNotBlank()) {
                                Text("Connected ${connection.createdAt.take(10)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = { disconnectTarget = connection }, enabled = !bankBusy) {
                            Text("Disconnect", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        item { Text("Members", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        val members = details?.members.orEmpty()
        if (members.isEmpty()) {
            item { Text(if (busy) "Loading members…" else "No member list available yet") }
        } else {
            items(members, key = { it.userId }) { member ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(member.displayLabel.ifBlank { member.username }, fontWeight = FontWeight.SemiBold)
                            Text(member.role.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
                        }
                        if (session.isOwner && member.userId != details?.ownerUserId) {
                            TextButton(
                                onClick = {
                                    if (busy) return@TextButton
                                    busy = true
                                    error = null
                                    scope.launch {
                                        runCatching { api.removeMember(backendUrl, session.sessionToken, member.userId) }
                                            .onSuccess {
                                                details = api.fetchHousehold(backendUrl, session.sessionToken)
                                                status = "Member removed"
                                            }
                                            .onFailure { error = it.message ?: "Could not remove member" }
                                        busy = false
                                    }
                                },
                                enabled = !busy
                            ) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }

    disconnectTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!bankBusy) disconnectTarget = null },
            title = { Text("Disconnect bank?") },
            text = {
                Text("This removes the selected Plaid connection and all accounts that came from that connection. It does not close or change anything at your bank.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (bankBusy) return@Button
                        bankBusy = true
                        bankError = null
                        scope.launch {
                            BankApi.setSessionToken(session.sessionToken)
                            runCatching {
                                BankApi.disconnectBank(backendUrl, "", target.itemId)
                                bankConnections = BankApi.listBankConnections(backendUrl, "")
                                onBankConnectionsChanged()
                            }.onSuccess {
                                status = "Bank connection disconnected"
                                disconnectTarget = null
                            }.onFailure {
                                bankError = it.message ?: "Could not disconnect bank"
                            }
                            bankBusy = false
                        }
                    },
                    enabled = !bankBusy
                ) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { disconnectTarget = null }, enabled = !bankBusy) { Text("Cancel") }
            }
        )
    }
}
