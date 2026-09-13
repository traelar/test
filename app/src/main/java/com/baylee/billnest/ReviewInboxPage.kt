package com.baylee.billnest

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.Account
import com.baylee.billnest.model.AppData
import com.baylee.billnest.model.Debt
import com.baylee.billnest.model.DebtType
import com.baylee.billnest.model.FinanceTransaction
import com.baylee.billnest.model.MerchantProfile
import com.baylee.billnest.model.ReviewDisposition
import com.baylee.billnest.model.ReviewItem
import com.baylee.billnest.model.ReviewType
import com.baylee.billnest.model.SmartTransactionRule
import com.baylee.billnest.model.SubscriptionPreference
import com.baylee.billnest.model.SubscriptionStatus
import com.baylee.billnest.model.TransactionClassification
import com.baylee.billnest.model.effectiveDisplayName
import com.baylee.billnest.model.reclassifyTransaction
import com.baylee.billnest.model.subscriptionKey
import com.baylee.billnest.model.visibleReviewItems
import com.baylee.billnest.ui.MainViewModel
import kotlin.math.abs

@Composable
fun ReviewInboxPage(
    data: AppData,
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val items = remember(data) { visibleReviewItems(data) }
    var editTransactionFor by remember { mutableStateOf<ReviewItem?>(null) }
    var categorizeFor by remember { mutableStateOf<ReviewItem?>(null) }
    var merchantFor by remember { mutableStateOf<ReviewItem?>(null) }
    var smartRuleFor by remember { mutableStateOf<ReviewItem?>(null) }
    var accountFor by remember { mutableStateOf<ReviewItem?>(null) }
    var debtFor by remember { mutableStateOf<ReviewItem?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Review Inbox", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "BillNest found things worth checking. Nothing here changes your finances until you choose an action.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (items.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "You're all caught up. BillNest has nothing that needs review right now.",
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(items, key = { it.fingerprint }) { item ->
                ReviewCard(
                    item = item,
                    data = data,
                    onAction = { action ->
                        when (action) {
                            ReviewUiAction.CATEGORIZE -> categorizeFor = item
                            ReviewUiAction.MERCHANT -> merchantFor = item
                            ReviewUiAction.CONFIRM_TRANSFER -> confirmTransferReview(item, data, vm)
                            ReviewUiAction.MARK_INCOME -> markIncomeReview(item, data, vm)
                            ReviewUiAction.TRACK_RECURRING -> trackRecurringReview(item, data, vm)
                            ReviewUiAction.EDIT -> editTransactionFor = item
                            ReviewUiAction.DELETE_DUPLICATE -> deleteDuplicateReview(item, data, vm)
                            ReviewUiAction.USE_CATEGORY -> useSuggestedCategory(item, data, vm)
                            ReviewUiAction.MAKE_RULE -> smartRuleFor = item
                            ReviewUiAction.KEEP_CURRENT,
                            ReviewUiAction.LOOKS_RIGHT,
                            ReviewUiAction.DISMISS -> vm.resolveReview(item.fingerprint, ReviewDisposition.DISMISSED)
                            ReviewUiAction.EDIT_ACCOUNT -> accountFor = item
                            ReviewUiAction.EDIT_DEBT -> debtFor = item
                        }
                    }
                )
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 32.dp)) }
    }

    editTransactionFor?.let { item ->
        val transaction = item.transactionIds.firstNotNullOfOrNull { id -> data.transactions.firstOrNull { it.id == id } }
        if (transaction == null) {
            editTransactionFor = null
        } else {
            TransactionEditorDialog(
                data = data,
                existing = transaction,
                onDismiss = { editTransactionFor = null },
                onSave = { updated ->
                    vm.saveTransaction(updated)
                    vm.resolveReview(item.fingerprint, ReviewDisposition.RESOLVED)
                    editTransactionFor = null
                }
            )
        }
    }

    categorizeFor?.let { item ->
        val transaction = item.transactionIds.firstNotNullOfOrNull { id -> data.transactions.firstOrNull { it.id == id } }
        if (transaction == null) {
            categorizeFor = null
        } else {
            ReviewCategoryDialog(
                data = data,
                transaction = transaction,
                suggested = item.suggestedCategory,
                onDismiss = { categorizeFor = null },
                onSave = { category ->
                    vm.saveTransaction(
                        transaction.copy(
                            category = category,
                            transfer = false,
                            income = false,
                            userClassificationOverride = true
                        )
                    )
                    vm.resolveReview(item.fingerprint, ReviewDisposition.RESOLVED)
                    categorizeFor = null
                }
            )
        }
    }

    merchantFor?.let { item ->
        val transaction = item.transactionIds.firstNotNullOfOrNull { id -> data.transactions.firstOrNull { it.id == id } }
        if (transaction == null) {
            merchantFor = null
        } else {
            MerchantEditorDialog(
                data = data,
                sourceTransaction = transaction,
                onDismiss = { merchantFor = null },
                onSave = { profile: MerchantProfile ->
                    vm.saveMerchantProfile(profile)
                    vm.resolveReview(item.fingerprint, ReviewDisposition.RESOLVED)
                    merchantFor = null
                }
            )
        }
    }

    smartRuleFor?.let { item ->
        val transaction = item.transactionIds.firstNotNullOfOrNull { id -> data.transactions.firstOrNull { it.id == id } }
        if (transaction == null) {
            smartRuleFor = null
        } else {
            SmartRuleEditorDialog(
                data = data,
                sourceTransaction = transaction,
                onDismiss = { smartRuleFor = null },
                onSave = { rule: SmartTransactionRule ->
                    vm.saveSmartTransactionRule(rule)
                    vm.resolveReview(item.fingerprint, ReviewDisposition.RESOLVED)
                    smartRuleFor = null
                }
            )
        }
    }

    accountFor?.let { item ->
        val account = item.accountId?.let { id -> data.accounts.firstOrNull { it.id == id } }
        if (account == null) {
            accountFor = null
        } else {
            AssetAccountDialog(
                original = account,
                onDismiss = { accountFor = null },
                onSave = { updated: Account ->
                    vm.updateAccount(updated)
                    vm.resolveReview(item.fingerprint, ReviewDisposition.RESOLVED)
                    accountFor = null
                }
            )
        }
    }

    debtFor?.let { item ->
        val debt = item.debtId?.let { id -> data.debts.firstOrNull { it.id == id } }
        if (debt == null) {
            debtFor = null
        } else {
            ReviewDebtEditorDialog(
                debt = debt,
                onDismiss = { debtFor = null },
                onSave = { updated ->
                    vm.saveDebt(updated)
                    vm.resolveReview(item.fingerprint, ReviewDisposition.RESOLVED)
                    debtFor = null
                }
            )
        }
    }
}

@Composable
private fun ReviewCard(
    item: ReviewItem,
    data: AppData,
    onAction: (ReviewUiAction) -> Unit
) {
    val affected = reviewAffectedText(item, data)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(reviewTypeLabel(item.type), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("${reviewConfidenceLabel(item.confidence)} confidence", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (affected.isNotBlank()) Text(affected, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.explanation, style = MaterialTheme.typography.bodyMedium)
            item.suggestedCategory?.let {
                Text("Suggested category: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            reviewActionRows(item.type).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { action ->
                        val destructive = action == ReviewUiAction.DELETE_DUPLICATE
                        TextButton(onClick = { onAction(action) }, modifier = Modifier.weight(1f)) {
                            Text(reviewActionLabel(action, item), color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

private fun reviewTypeLabel(type: ReviewType): String = when (type) {
    ReviewType.UNCATEGORIZED -> "UNCATEGORIZED"
    ReviewType.UNKNOWN_MERCHANT -> "UNKNOWN MERCHANT"
    ReviewType.POSSIBLE_TRANSFER -> "POSSIBLE TRANSFER"
    ReviewType.POSSIBLE_INCOME -> "POSSIBLE INCOME"
    ReviewType.POSSIBLE_RECURRING -> "POSSIBLE RECURRING"
    ReviewType.POTENTIAL_DUPLICATE -> "POTENTIAL DUPLICATE"
    ReviewType.CATEGORY_CONFLICT -> "CATEGORY CONFLICT"
    ReviewType.UNUSUAL_AMOUNT -> "UNUSUAL AMOUNT"
    ReviewType.ACCOUNT_METADATA_MISSING -> "ACCOUNT SETUP"
    ReviewType.DEBT_METADATA_MISSING -> "DEBT SETUP"
}

private fun reviewActionLabel(action: ReviewUiAction, item: ReviewItem): String = when (action) {
    ReviewUiAction.CATEGORIZE -> "Choose category"
    ReviewUiAction.MERCHANT -> "Set merchant"
    ReviewUiAction.CONFIRM_TRANSFER -> "Confirm transfer"
    ReviewUiAction.MARK_INCOME -> "Mark income"
    ReviewUiAction.TRACK_RECURRING -> "Track"
    ReviewUiAction.EDIT -> "Edit"
    ReviewUiAction.DELETE_DUPLICATE -> "Delete duplicate"
    ReviewUiAction.USE_CATEGORY -> item.suggestedCategory?.let { "Use $it" } ?: "Use suggestion"
    ReviewUiAction.MAKE_RULE -> "Make rule"
    ReviewUiAction.KEEP_CURRENT -> "Keep current"
    ReviewUiAction.LOOKS_RIGHT -> "Looks right"
    ReviewUiAction.EDIT_ACCOUNT -> "Edit account"
    ReviewUiAction.EDIT_DEBT -> "Edit debt"
    ReviewUiAction.DISMISS -> "Dismiss"
}

private fun reviewAffectedText(item: ReviewItem, data: AppData): String {
    item.accountId?.let { id -> return data.accounts.firstOrNull { it.id == id }?.name.orEmpty() }
    item.debtId?.let { id -> return data.debts.firstOrNull { it.id == id }?.name.orEmpty() }
    val rows = item.transactionIds.mapNotNull { id -> data.transactions.firstOrNull { it.id == id } }
    if (rows.isEmpty()) return ""
    return rows.joinToString(" • ") { row ->
        val display = row.effectiveDisplayName()
        val raw = if (display != row.name) " (${row.name})" else ""
        "$display$raw · ${row.dateIso} · $${"%.2f".format(abs(row.amount))}"
    }
}

private fun confirmTransferReview(item: ReviewItem, data: AppData, vm: MainViewModel) {
    val rows = item.transactionIds.mapNotNull { id -> data.transactions.firstOrNull { it.id == id } }
    if (rows.size != 2) return
    val outflow = rows.firstOrNull { it.amount >= 0.0 } ?: return
    val inflow = rows.firstOrNull { it.amount < 0.0 } ?: return
    val fromId = outflow.accountId ?: return
    val toId = inflow.accountId ?: return
    rows.forEach { row ->
        vm.saveTransaction(
            row.copy(
                category = "Transfer",
                transfer = true,
                income = false,
                transferFromAccountId = fromId,
                transferToAccountId = toId,
                userClassificationOverride = true,
                splits = null
            )
        )
    }
    vm.resolveReview(item.fingerprint, ReviewDisposition.RESOLVED)
}

private fun markIncomeReview(item: ReviewItem, data: AppData, vm: MainViewModel) {
    val transaction = item.transactionIds.firstNotNullOfOrNull { id -> data.transactions.firstOrNull { it.id == id } } ?: return
    vm.saveTransaction(reclassifyTransaction(transaction, TransactionClassification.INCOME))
    vm.resolveReview(item.fingerprint, ReviewDisposition.RESOLVED)
}

private fun trackRecurringReview(item: ReviewItem, data: AppData, vm: MainViewModel) {
    val transaction = item.transactionIds.firstNotNullOfOrNull { id -> data.transactions.firstOrNull { it.id == id } } ?: return
    val name = transaction.effectiveDisplayName()
    vm.saveSubscriptionPreference(
        SubscriptionPreference(
            merchantKey = subscriptionKey(name),
            name = name,
            status = SubscriptionStatus.CONFIRMED
        )
    )
    vm.resolveReview(item.fingerprint, ReviewDisposition.RESOLVED)
}

private fun deleteDuplicateReview(item: ReviewItem, data: AppData, vm: MainViewModel) {
    val candidates = item.transactionIds.mapNotNull { id -> data.transactions.firstOrNull { it.id == id } }
        .sortedWith(compareByDescending<FinanceTransaction> { it.dateIso }.thenByDescending { it.id })
    val duplicate = candidates.firstOrNull() ?: return
    vm.deleteTransaction(duplicate.id)
    vm.resolveReview(item.fingerprint, ReviewDisposition.RESOLVED)
}

private fun useSuggestedCategory(item: ReviewItem, data: AppData, vm: MainViewModel) {
    val category = item.suggestedCategory ?: return
    val transaction = item.transactionIds.firstNotNullOfOrNull { id -> data.transactions.firstOrNull { it.id == id } } ?: return
    vm.saveTransaction(
        transaction.copy(
            category = category,
            transfer = false,
            income = false,
            userClassificationOverride = true
        )
    )
    vm.resolveReview(item.fingerprint, ReviewDisposition.RESOLVED)
}

@Composable
private fun ReviewCategoryDialog(
    data: AppData,
    transaction: FinanceTransaction,
    suggested: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var category by remember(transaction.id) { mutableStateOf(suggested ?: transaction.category) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(transaction.effectiveDisplayName(), fontWeight = FontWeight.SemiBold)
                CategoryPickerField(
                    label = "Category",
                    value = category,
                    data = data,
                    onValueChange = { category = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(category) }, enabled = category.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ReviewDebtEditorDialog(
    debt: Debt,
    onDismiss: () -> Unit,
    onSave: (Debt) -> Unit
) {
    var apr by remember(debt.id) { mutableStateOf(debt.apr.toString()) }
    var minimum by remember(debt.id) { mutableStateOf(debt.minimumPayment.toString()) }
    var limit by remember(debt.id) { mutableStateOf(debt.creditLimit.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Finish debt details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(debt.name, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(apr, { apr = it }, label = { Text("APR %") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(minimum, { minimum = it }, label = { Text("Minimum payment") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (debt.type == DebtType.CREDIT_CARD) {
                    OutlinedTextField(limit, { limit = it }, label = { Text("Credit limit") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            }
        },
        confirmButton = {
            val parsedApr = apr.toDoubleOrNull()
            val parsedMinimum = minimum.toDoubleOrNull()
            val parsedLimit = if (debt.type == DebtType.CREDIT_CARD) limit.toDoubleOrNull() else debt.creditLimit
            Button(
                onClick = {
                    onSave(
                        debt.copy(
                            apr = parsedApr ?: debt.apr,
                            minimumPayment = parsedMinimum ?: debt.minimumPayment,
                            creditLimit = parsedLimit ?: debt.creditLimit
                        )
                    )
                },
                enabled = parsedApr != null && parsedApr >= 0.0 && parsedMinimum != null && parsedMinimum >= 0.0 && parsedLimit != null && parsedLimit >= 0.0
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
