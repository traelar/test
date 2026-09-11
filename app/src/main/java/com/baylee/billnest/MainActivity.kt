package com.baylee.billnest

import android.Manifest
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel
import com.baylee.billnest.ui.theme.BillNestTheme
import java.text.NumberFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class MainActivity : FragmentActivity() {
    private val vm by viewModels<MainViewModel> {
        val repo = (application as BillNestApp).repo
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo) as T
        }
    }
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((application as BillNestApp).repo.data.value.biometricLock) authenticate() else unlocked = true
        setContent {
            BillNestTheme {
                if (unlocked) BillNestHome(vm) else LockedScreen { authenticate() }
            }
        }
    }

    private fun authenticate() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS || errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT) unlocked = true
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock BillNest")
                .setSubtitle("Use your fingerprint or phone PIN")
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                ).build()
        )
    }
}

@Composable
private fun LockedScreen(retry: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
            Text("BillNest", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = retry) { Text("Unlock BillNest") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillNestHome(vm: MainViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BillNest") },
                actions = { TextButton(onClick = { showAdd = true }) { Text("+ Bill") } }
            )
        },
        bottomBar = {
            NavigationBar {
                listOf("Home", "Bills", "Calendar", "Settings").forEachIndexed { i, name ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(name) })
                }
            }
        }
    ) { pad ->
        when (tab) {
            0 -> Dashboard(data, vm, Modifier.padding(pad))
            1 -> BillsPage(data, vm, Modifier.padding(pad))
            2 -> CalendarPage(data, Modifier.padding(pad))
            else -> SettingsPage(data, vm, Modifier.padding(pad))
        }
    }

    if (showAdd) {
        AddBillDialog(onDismiss = { showAdd = false }, onSave = { vm.add(it); showAdd = false })
    }
}

@Composable
fun Dashboard(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val balance = data.balances.sumOf { it.available ?: it.current }.takeIf { data.balances.isNotEmpty() } ?: data.manualBalance
    val upcoming = data.bills.filter {
        !it.isPaidFor() && !it.dueDate().isBefore(today) && ChronoUnit.DAYS.between(today, it.dueDate()) <= 30
    }.sumOf { it.amount }
    val safe = balance - upcoming

    LazyColumn(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Money at a glance", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Available balance")
                    Text(currency(balance), style = MaterialTheme.typography.headlineMedium)
                    Text("Bills in next 30 days: ${currency(upcoming)}")
                    Text("Safe after bills: ${currency(safe)}", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        item { Text("Next bills", style = MaterialTheme.typography.titleLarge) }
        val next = data.bills.filter { !it.dueDate().isBefore(today) }.sortedBy { it.dueDate() }.take(5)
        if (next.isEmpty()) item { Text("No bills added yet. Tap + Bill to add your first one.") }
        items(next, key = { it.id }) { BillRow(it, vm) }
    }
}

@Composable
fun BillsPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("All bills", style = MaterialTheme.typography.headlineSmall) }
        if (data.bills.isEmpty()) item { Text("No bills yet.") }
        items(data.bills.sortedBy { it.dueDate() }, key = { it.id }) { BillRow(it, vm) }
    }
}

@Composable
fun BillRow(b: Bill, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(b.name, style = MaterialTheme.typography.titleMedium)
                Text("${currency(b.amount)} • Due ${b.dueDateIso}${if (b.autopay) " • Autopay" else ""}")
            }
            TextButton(onClick = { vm.paid(b.id) }) { Text("Paid") }
        }
    }
}

@Composable
fun CalendarPage(data: AppData, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Bill calendar", style = MaterialTheme.typography.headlineSmall) }
        if (data.bills.isEmpty()) item { Text("Your bill dates will appear here.") }
        items(data.bills.sortedBy { it.dueDate() }, key = { it.id }) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(it.dueDateIso, style = MaterialTheme.typography.titleMedium)
                    Text("${it.name} • ${currency(it.amount)}")
                }
            }
        }
    }
}

@Composable
fun SettingsPage(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    var balance by remember(data.manualBalance) { mutableStateOf(if (data.manualBalance == 0.0) "" else data.manualBalance.toString()) }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
        item {
            OutlinedTextField(
                value = balance,
                onValueChange = { balance = it },
                label = { Text("Current bank balance") },
                supportingText = { Text("Manual balance for this test build") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item { Button(onClick = { balance.toDoubleOrNull()?.let(vm::balance) }) { Text("Save balance") } }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Automatic bank connection", style = MaterialTheme.typography.titleMedium)
                    Text("Plaid linking is the next connection step. Bill tracking and reminders work without it.")
                }
            }
        }
        item { Text("Reminder schedule: ${data.reminderDays.joinToString()} days before due date") }
        item { Text("Bill data is encrypted on-device using Android Keystore.") }
    }
}

@Composable
fun AddBillDialog(onDismiss: () -> Unit, onSave: (Bill) -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().plusDays(7).toString()) }
    var autopay by remember { mutableStateOf(false) }
    var frequency by remember { mutableStateOf(Frequency.MONTHLY) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add bill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Bill name") })
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount") })
                OutlinedTextField(date, { date = it }, label = { Text("Due date (YYYY-MM-DD)") })
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text("Repeats: ${frequency.name.replace('_', ' ')}") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Frequency.entries.forEach { f ->
                            DropdownMenuItem(text = { Text(f.name.replace('_', ' ')) }, onClick = { frequency = f; expanded = false })
                        }
                    }
                }
                Row { Checkbox(autopay, { autopay = it }); Text("Autopay", modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedAmount = amount.toDoubleOrNull()
                val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
                if (parsedAmount != null && parsedDate != null) {
                    onSave(Bill(name = name.ifBlank { "Bill" }, amount = parsedAmount, dueDateIso = parsedDate.toString(), frequency = frequency, autopay = autopay))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun currency(value: Double): String = NumberFormat.getCurrencyInstance().format(value)
