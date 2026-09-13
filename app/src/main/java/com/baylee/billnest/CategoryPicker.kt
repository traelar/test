package com.baylee.billnest

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.AppData
import com.baylee.billnest.model.categoryOptions
import com.baylee.billnest.model.cleanCategoryName
import com.baylee.billnest.model.normalizeCategoryKey

private fun mergedCategoryOptions(data: AppData, extraOptions: List<String>): List<String> {
    val result = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    (categoryOptions(data) + extraOptions).forEach { raw ->
        val cleaned = cleanCategoryName(raw)
        if (cleaned.isBlank()) return@forEach
        if (seen.add(normalizeCategoryKey(cleaned))) result += cleaned
    }
    return result
}

private fun canonicalPickerValue(value: String, options: List<String>): String {
    val cleaned = cleanCategoryName(value)
    if (cleaned.isBlank()) return ""
    val key = normalizeCategoryKey(cleaned)
    return options.firstOrNull { normalizeCategoryKey(it) == key } ?: cleaned
}

@Composable
fun CategoryPickerField(
    data: AppData,
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Category",
    extraOptions: List<String> = emptyList(),
    allowEmpty: Boolean = false,
    emptyLabel: String = "No category"
) {
    var expanded by remember { mutableStateOf(false) }
    var addingCustom by remember { mutableStateOf(false) }
    var customValue by remember { mutableStateOf("") }
    val options = remember(data, extraOptions) { mergedCategoryOptions(data, extraOptions) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(value.ifBlank { emptyLabel })
                    Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                if (allowEmpty) {
                    DropdownMenuItem(
                        text = { Text(emptyLabel) },
                        onClick = {
                            onValueChange("")
                            expanded = false
                        }
                    )
                    HorizontalDivider()
                }
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("+ Add new category") },
                    onClick = {
                        customValue = ""
                        addingCustom = true
                        expanded = false
                    }
                )
            }
        }

        if (addingCustom) {
            OutlinedTextField(
                value = customValue,
                onValueChange = { customValue = it },
                label = { Text("New category") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    addingCustom = false
                    customValue = ""
                }) { Text("Cancel") }
                TextButton(
                    enabled = cleanCategoryName(customValue).isNotBlank(),
                    onClick = {
                        onValueChange(canonicalPickerValue(customValue, options))
                        addingCustom = false
                        customValue = ""
                    }
                ) { Text("Use category") }
            }
        }
    }
}

@Composable
fun CategoryMultiPickerField(
    data: AppData,
    selected: List<String>,
    onSelectedChange: (List<String>) -> Unit,
    label: String,
    extraOptions: List<String> = emptyList()
) {
    var expanded by remember { mutableStateOf(false) }
    var addingCustom by remember { mutableStateOf(false) }
    var customValue by remember { mutableStateOf("") }
    val options = remember(data, selected, extraOptions) {
        mergedCategoryOptions(data, extraOptions + selected)
    }
    val selectedKeys = selected.mapTo(mutableSetOf()) { normalizeCategoryKey(it) }

    fun addCategory(raw: String) {
        val canonical = canonicalPickerValue(raw, options)
        if (canonical.isBlank()) return
        if (normalizeCategoryKey(canonical) !in selectedKeys) {
            onSelectedChange(selected + canonical)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        selected.forEach { category ->
            Surface(
                shape = MaterialTheme.shapes.small,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(category, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val key = normalizeCategoryKey(category)
                        onSelectedChange(selected.filterNot { normalizeCategoryKey(it) == key })
                    }) { Text("Remove") }
                }
            }
        }

        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (selected.isEmpty()) "Choose category" else "+ Add category")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                options.filterNot { normalizeCategoryKey(it) in selectedKeys }.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            addCategory(option)
                            expanded = false
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("+ Add new category") },
                    onClick = {
                        customValue = ""
                        addingCustom = true
                        expanded = false
                    }
                )
            }
        }

        if (addingCustom) {
            OutlinedTextField(
                value = customValue,
                onValueChange = { customValue = it },
                label = { Text("New category") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    addingCustom = false
                    customValue = ""
                }) { Text("Cancel") }
                TextButton(
                    enabled = cleanCategoryName(customValue).isNotBlank(),
                    onClick = {
                        addCategory(customValue)
                        addingCustom = false
                        customValue = ""
                    }
                ) { Text("Add category") }
            }
        }
    }
}
