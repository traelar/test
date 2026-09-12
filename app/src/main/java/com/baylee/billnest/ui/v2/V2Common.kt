package com.baylee.billnest.ui.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.baylee.billnest.model.*
import java.text.NumberFormat

internal fun money(value: Double): String = NumberFormat.getCurrencyInstance().format(value)

internal fun accountRoleLabel(role: AccountRole): String = when (role) {
    AccountRole.SPENDING -> "Spending"
    AccountRole.SAVINGS -> "Savings"
    AccountRole.CREDIT_DEBT -> "Credit / Debt"
    AccountRole.OTHER -> "Other"
}

internal fun debtTypeLabel(type: DebtType): String = when (type) {
    DebtType.CREDIT_CARD -> "Credit card"
    DebtType.PERSONAL_LOAN -> "Personal loan"
    DebtType.AUTO_LOAN -> "Auto loan"
    DebtType.STUDENT_LOAN -> "Student loan"
    DebtType.MORTGAGE -> "Mortgage"
    DebtType.OTHER -> "Other debt"
}

@Composable
internal fun <T : Enum<T>> SimpleEnumPicker(
    label: String,
    value: T,
    values: List<T>,
    onChange: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${value.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                    onClick = { onChange(option); expanded = false }
                )
            }
        }
    }
}

@Composable
internal fun SimpleAccountPicker(
    data: AppData,
    value: String?,
    label: String,
    onChange: (String?) -> Unit
) {
    val options = AccountFinance.sortAccounts(data.accounts, data.accountPreferences)
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { AccountFinance.stableKey(it) == value }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${selected?.let { AccountFinance.displayName(it, data.accountPreferences, data.accounts.indexOf(it).coerceAtLeast(0)) } ?: "None"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = { onChange(null); expanded = false })
            options.forEach { account ->
                val index = data.accounts.indexOf(account).coerceAtLeast(0)
                DropdownMenuItem(
                    text = { Text(AccountFinance.displayName(account, data.accountPreferences, index)) },
                    onClick = { onChange(AccountFinance.stableKey(account)); expanded = false }
                )
            }
        }
    }
}

@Composable
internal fun SimpleStringPicker(
    label: String,
    value: String?,
    options: List<Pair<String, String>>,
    onChange: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == value }?.second ?: "None"
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $display") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = { onChange(null); expanded = false })
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.second) }, onClick = { onChange(option.first); expanded = false })
            }
        }
    }
}
