package com.baylee.billnest.data

import android.content.Context
import com.baylee.billnest.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

class BillRepository(
    context: Context,
    private val syncDb: LocalSyncDb? = null,
    private val onSyncNeeded: (() -> Unit)? = null
) {
    private val store = EncryptedStore(context)
    private val initialData = captureFinancialSnapshot(reconcileDebtBills(migrateLegacy(store.load()))).also { store.save(it) }
    private val _data = MutableStateFlow(initialData)
    val data: StateFlow<AppData> = _data

    private fun migrateLegacy(data: AppData): AppData {
        val importedAccounts = if (data.accounts.isNotEmpty()) {
            data.accounts
        } else {
            when {
                data.balances.isNotEmpty() -> data.balances.map { old ->
                    Account(
                        name = old.name,
                        type = AccountType.CHECKING,
                        balance = old.available ?: old.current,
                        source = AccountSource.PLAID,
                        plaidAccountId = old.accountId,
                        mask = old.mask,
                        updatedAtEpochMs = old.updatedAtEpochMs
                    )
                }
                data.manualBalance != 0.0 -> listOf(
                    Account(name = "Main Account", type = AccountType.CHECKING, balance = data.manualBalance)
                )
                else -> emptyList()
            }
        }

        val savedBackend = data.backendUrl.trim()
        val backend = when {
            savedBackend.isBlank() -> BILLNEST_BACKEND_URL
            savedBackend == "https://billnest-api.joshsolution.workers.dev" -> BILLNEST_BACKEND_URL
            else -> savedBackend
        }
        return data.copy(accounts = importedAccounts, backendUrl = backend)
    }

    private fun update(transform: (AppData) -> AppData) {
        val next = transform(_data.value)
        store.save(next)
        _data.value = next
    }

    private fun queue(draft: SyncRecordDraft?, deleted: Boolean = false) {
        if (draft == null) return
        syncDb?.enqueueCurrent(
            kind = draft.kind,
            recordId = draft.recordId,
            deleted = deleted,
            payloadJson = if (deleted) "{}" else draft.payloadJson
        )
        onSyncNeeded?.invoke()
    }

    private fun queueDelete(kind: String, recordId: String) {
        syncDb?.enqueueCurrent(kind, recordId, true, "{}")
        onSyncNeeded?.invoke()
    }

    private fun captureTodaySnapshot() {
        update { captureFinancialSnapshot(it) }
        _data.value.financialSnapshots.lastOrNull { it.dateIso == LocalDate.now().toString() }
            ?.let { queue(SyncMapper.financialSnapshotMutation(it)) }
    }

    private fun reapplyStageA(
        data: AppData,
        profiles: List<MerchantProfile> = data.merchantProfiles,
        rules: List<SmartTransactionRule> = data.smartTransactionRules
    ): AppData {
        val base = data.copy(merchantProfiles = profiles, smartTransactionRules = rules)
        val batch = applySmartRuleSet(
            transactions = base.transactions,
            rules = rules,
            profiles = profiles,
            subscriptions = base.subscriptionPreferences,
            data = base
        )
        return base.copy(
            transactions = batch.transactions,
            subscriptionPreferences = batch.subscriptionPreferences
        )
    }

    private fun queueChangedSubscriptionPreferences(
        before: List<SubscriptionPreference>,
        after: List<SubscriptionPreference>
    ) {
        val beforeByKey = before.associateBy { it.merchantKey }
        after.filter { beforeByKey[it.merchantKey] != it }
            .forEach { queue(SyncMapper.subscriptionPreferenceMutation(it)) }
    }

    fun addBill(bill: Bill) {
        update { it.copy(bills = it.bills + bill) }
        queue(SyncMapper.billMutation(bill))
    }

    fun updateBill(bill: Bill) {
        update { data ->
            val debts = bill.sourceDebtId?.let { debtId ->
                val due = runCatching { bill.dueDate() }.getOrNull()
                if (due == null) data.debts else data.debts.map { debt ->
                    if (debt.id == debtId) debt.copy(dueDateIso = due.toString(), dueDay = due.dayOfMonth) else debt
                }
            } ?: data.debts
            data.copy(
                bills = data.bills.map { if (it.id == bill.id) bill else it },
                debts = debts
            )
        }
        queue(SyncMapper.billMutation(bill))
        bill.sourceDebtId?.let { debtId ->
            _data.value.debts.firstOrNull { it.id == debtId }?.let { queue(SyncMapper.debtMutation(it)) }
        }
    }

    fun deleteBill(id: String) {
        val existing = _data.value.bills.firstOrNull { it.id == id }
        if (existing?.sourceDebtId != null) return
        update { it.copy(bills = it.bills.filterNot { bill -> bill.id == id }) }
        queueDelete("bill", id)
    }

    fun addPayday(payday: Payday) {
        update { it.copy(paydays = it.paydays + payday) }
        queue(SyncMapper.paydayMutation(payday))
    }

    fun updatePayday(payday: Payday) {
        update { data -> data.copy(paydays = data.paydays.map { if (it.id == payday.id) payday else it }) }
        queue(SyncMapper.paydayMutation(payday))
    }

    fun deletePayday(id: String) {
        update { it.copy(paydays = it.paydays.filterNot { payday -> payday.id == id }) }
        queueDelete("payday", id)
    }

    fun receivePayday(id: String) {
        update { applyPaydayContributions(it, id) }
        _data.value.paydays.firstOrNull { it.id == id }?.let { queue(SyncMapper.paydayMutation(it)) }
        _data.value.reservedFunds.forEach { queue(SyncMapper.reservedFundMutation(it)) }
        _data.value.savingsGoals.forEach { queue(SyncMapper.goalMutation(it)) }
    }

    fun addAccount(account: Account) {
        update { it.copy(accounts = it.accounts + account) }
        queue(SyncMapper.accountMutation(account))
        captureTodaySnapshot()
    }

    fun moveAccount(accountId: String, direction: Int) {
        update { data ->
            val ordered = data.accounts.sortedWith(compareBy<Account> { it.displayOrder }.thenBy { it.name }).toMutableList()
            val from = ordered.indexOfFirst { it.id == accountId }
            if (from < 0 || ordered.isEmpty()) return@update data
            val to = (from + direction).coerceIn(0, ordered.lastIndex)
            if (from == to) return@update data
            val moved = ordered.removeAt(from)
            ordered.add(to, moved)
            data.copy(accounts = ordered.mapIndexed { index, account -> account.copy(displayOrder = index) })
        }
        _data.value.accounts.filter { it.source == AccountSource.MANUAL }.forEach { queue(SyncMapper.accountMutation(it)) }
    }

    fun updateAccount(account: Account) {
        update { data -> data.copy(accounts = data.accounts.map { if (it.id == account.id) account else it }) }
        queue(SyncMapper.accountMutation(account))
        captureTodaySnapshot()
    }

    fun deleteAccount(id: String) {
        val existing = _data.value.accounts.firstOrNull { it.id == id }
        update { data ->
            data.copy(
                accounts = data.accounts.filterNot { it.id == id },
                bills = data.bills.map { if (it.accountId == id) it.copy(accountId = null) else it }
            )
        }
        if (existing?.source == AccountSource.MANUAL) queueDelete("manual_account", id)
        captureTodaySnapshot()
    }

    fun saveTransaction(value: FinanceTransaction) {
        update { data -> data.copy(transactions = upsert(data.transactions, value.id, value) { it.id }) }
        queue(SyncMapper.transactionMutation(value))
    }

    fun deleteTransaction(id: String) {
        val before = _data.value.transactionTombstones.mapTo(mutableSetOf()) { it.transactionId }
        update { data -> deleteFinanceTransaction(data, id) }
        queueDelete("transaction", id)
        _data.value.transactionTombstones
            .filterNot { it.transactionId in before }
            .forEach { queue(SyncMapper.transactionTombstoneMutation(it)) }
    }

    fun saveTransactionRule(value: TransactionRule) {
        update { data ->
            val rules = upsert(data.transactionRules, value.id, value) { it.id }
            data.copy(
                transactionRules = rules,
                transactions = applyTransactionRules(data.transactions, rules)
            )
        }
        queue(SyncMapper.transactionRuleMutation(value))
    }

    fun deleteTransactionRule(id: String) {
        update { data -> data.copy(transactionRules = data.transactionRules.filterNot { it.id == id }) }
        queueDelete("transaction_rule", id)
    }

    fun saveMerchantProfile(value: MerchantProfile) {
        val beforeSubscriptions = _data.value.subscriptionPreferences
        update { data ->
            val profiles = upsert(data.merchantProfiles, value.id, value) { it.id }
            reapplyStageA(data, profiles = profiles)
        }
        queue(SyncMapper.merchantProfileMutation(value))
        queueChangedSubscriptionPreferences(beforeSubscriptions, _data.value.subscriptionPreferences)
    }

    fun deleteMerchantProfile(id: String) {
        update { data ->
            val profiles = data.merchantProfiles.filterNot { it.id == id }
            val cleared = data.copy(
                transactions = data.transactions.map { transaction ->
                    if (transaction.merchantProfileId == id) transaction.copy(merchantProfileId = null) else transaction
                }
            )
            reapplyStageA(cleared, profiles = profiles)
        }
        queueDelete("merchant_profile", id)
    }

    fun saveSmartTransactionRule(value: SmartTransactionRule) {
        val beforeSubscriptions = _data.value.subscriptionPreferences
        update { data ->
            val rules = upsert(data.smartTransactionRules, value.id, value) { it.id }
            reapplyStageA(data, rules = rules)
        }
        queue(SyncMapper.smartTransactionRuleMutation(value))
        queueChangedSubscriptionPreferences(beforeSubscriptions, _data.value.subscriptionPreferences)
    }

    fun deleteSmartTransactionRule(id: String) {
        update { data ->
            val rules = data.smartTransactionRules.filterNot { it.id == id }
            val cleared = data.copy(
                transactions = data.transactions.map { transaction ->
                    if (transaction.appliedSmartRuleId == id) transaction.copy(appliedSmartRuleId = null) else transaction
                }
            )
            reapplyStageA(cleared, rules = rules)
        }
        queueDelete("smart_transaction_rule", id)
    }

    fun saveReviewResolution(value: ReviewResolution) {
        update { data ->
            data.copy(reviewResolutions = upsert(data.reviewResolutions, value.fingerprint, value) { it.fingerprint })
        }
        queue(SyncMapper.reviewResolutionMutation(value))
    }

    fun deleteReviewResolution(fingerprint: String) {
        update { data -> data.copy(reviewResolutions = data.reviewResolutions.filterNot { it.fingerprint == fingerprint }) }
        queueDelete("review_resolution", fingerprint)
    }

    fun syncPlaidTransactions(incoming: List<FinanceTransaction>) {
        val beforeSubscriptions = _data.value.subscriptionPreferences
        update { data ->
            val accountIds = data.accounts
                .filter { it.source == AccountSource.PLAID && !it.plaidAccountId.isNullOrBlank() }
                .associate { it.plaidAccountId!! to it.id }
            val mapped = incoming.map { row ->
                row.copy(accountId = accountIds[row.accountId] ?: row.accountId)
            }
            val processed = processIncomingTransactions(data, mapped)
            data.copy(
                transactions = mergePlaidTransactions(
                    existing = data.transactions,
                    incoming = processed.transactions,
                    deletedPlaidTransactionIds = transactionTombstoneIds(data)
                ),
                subscriptionPreferences = processed.subscriptionPreferences
            )
        }
        queueChangedSubscriptionPreferences(beforeSubscriptions, _data.value.subscriptionPreferences)
    }

    fun saveBudget(value: Budget) {
        update { data -> data.copy(budgets = upsert(data.budgets, value.id, value) { it.id }) }
        queue(SyncMapper.budgetMutation(value))
    }

    fun deleteBudget(id: String) {
        val relatedOverrides = _data.value.budgetTransactionOverrides.filter { it.budgetId == id }.map { it.id }
        val relatedAdjustments = _data.value.budgetAdjustments.filter { it.sourceBudgetId == id || it.destinationBudgetId == id }.map { it.id }
        update { data ->
            data.copy(
                budgets = data.budgets.filterNot { it.id == id },
                budgetTransactionOverrides = data.budgetTransactionOverrides.filterNot { it.budgetId == id },
                budgetAdjustments = data.budgetAdjustments.filterNot { it.sourceBudgetId == id || it.destinationBudgetId == id }
            )
        }
        queueDelete("budget", id)
        relatedOverrides.forEach { queueDelete("budget_override", it) }
        relatedAdjustments.forEach { queueDelete("budget_adjustment", it) }
    }

    fun setTransactionBudget(transactionId: String, budgetId: String?, referenceDate: LocalDate = LocalDate.now()) {
        val beforeIds = _data.value.budgetTransactionOverrides.mapTo(mutableSetOf()) { it.id }
        update { data -> com.baylee.billnest.model.assignTransactionToBudget(data, transactionId, budgetId, referenceDate) }
        _data.value.budgetTransactionOverrides
            .filterNot { it.id in beforeIds }
            .forEach { queue(SyncMapper.budgetOverrideMutation(it)) }
    }

    fun moveBudgetMoney(sourceBudgetId: String, destinationBudgetId: String, amount: Double, referenceDate: LocalDate = LocalDate.now()) {
        val beforeIds = _data.value.budgetAdjustments.mapTo(mutableSetOf()) { it.id }
        update { data -> com.baylee.billnest.model.moveBudgetMoney(data, sourceBudgetId, destinationBudgetId, amount, referenceDate) }
        _data.value.budgetAdjustments
            .filterNot { it.id in beforeIds }
            .forEach { queue(SyncMapper.budgetAdjustmentMutation(it)) }
    }

    fun saveDebt(value: Debt) {
        val explicitDue = value.dueDateIso
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val normalized = value.copy(
            dueDateIso = explicitDue?.toString(),
            dueDay = explicitDue?.dayOfMonth ?: value.dueDay.coerceIn(0, 31)
        )

        val before = _data.value
        val removedDuplicateIds = before.debts
            .filter {
                it.id != normalized.id &&
                    !normalized.plaidAccountId.isNullOrBlank() &&
                    it.plaidAccountId == normalized.plaidAccountId
            }
            .map { it.id }
        val affectedDebtIds = (removedDuplicateIds + normalized.id).toSet()
        val previousLinkedBills = before.bills.filter { it.sourceDebtId in affectedDebtIds }

        update { data ->
            reconcileDebtBills(
                data.copy(debts = upsertDebtRecord(data.debts, normalized))
            )
        }

        removedDuplicateIds.forEach { queueDelete("debt", it) }
        previousLinkedBills
            .filter { old -> _data.value.bills.none { it.id == old.id } }
            .forEach { queueDelete("bill", it.id) }

        queue(SyncMapper.debtMutation(normalized))
        _data.value.bills
            .firstOrNull { it.sourceDebtId == normalized.id }
            ?.let { queue(SyncMapper.billMutation(it)) }
        captureTodaySnapshot()
    }

    fun deleteDebt(id: String) {
        val linkedBill = _data.value.bills.firstOrNull { it.sourceDebtId == id }
        update { data ->
            reconcileDebtBills(data.copy(debts = data.debts.filterNot { row -> row.id == id }))
        }
        queueDelete("debt", id)
        linkedBill?.let { queueDelete("bill", it.id) }
        captureTodaySnapshot()
    }

    fun saveGoal(value: SavingsGoal) {
        update { data -> data.copy(savingsGoals = upsert(data.savingsGoals, value.id, value) { it.id }) }
        queue(SyncMapper.goalMutation(value))
    }

    fun deleteGoal(id: String) {
        update { it.copy(savingsGoals = it.savingsGoals.filterNot { row -> row.id == id }) }
        queueDelete("savings_goal", id)
    }

    fun saveReservedFund(value: ReservedFund) {
        val normalized = value.copy(amount = value.amount.coerceAtLeast(0.0))
        update { data -> data.copy(reservedFunds = upsert(data.reservedFunds, value.id, normalized) { it.id }) }
        queue(SyncMapper.reservedFundMutation(normalized))
    }

    fun deleteReservedFund(id: String) {
        update { it.copy(reservedFunds = it.reservedFunds.filterNot { row -> row.id == id }) }
        queueDelete("reserved_fund", id)
    }

    fun fundReserved(id: String, amount: Double) {
        update { data -> data.copy(reservedFunds = data.reservedFunds.map { if (it.id == id) it.copy(amount = (it.amount + amount).coerceAtLeast(0.0)) else it }) }
        _data.value.reservedFunds.firstOrNull { it.id == id }?.let { queue(SyncMapper.reservedFundMutation(it)) }
    }

    fun saveSubscriptionPreference(value: SubscriptionPreference) {
        update { data -> data.copy(subscriptionPreferences = upsert(data.subscriptionPreferences, value.merchantKey, value) { it.merchantKey }) }
        queue(SyncMapper.subscriptionPreferenceMutation(value))
    }

    fun deleteSubscriptionPreference(merchantKey: String) {
        update { data -> data.copy(subscriptionPreferences = data.subscriptionPreferences.filterNot { it.merchantKey == merchantKey }) }
        queueDelete("subscription_preference", merchantKey)
    }

    fun syncPlaidAccounts(incoming: List<Account>, retainMissing: Boolean = false) {
        val before = _data.value
        val merged = mergePlaidAccounts(before.accounts, incoming, retainMissing)
        val debts = mergePlaidCreditDebts(before.debts, merged)
        update {
            reconcileDebtBills(
                it.copy(
                    accounts = merged,
                    debts = debts,
                    plaidConnected = merged.any { account -> account.source == AccountSource.PLAID }
                )
            )
        }
        debts.filter { debt -> before.debts.firstOrNull { it.id == debt.id } != debt }.forEach { queue(SyncMapper.debtMutation(it)) }
        captureTodaySnapshot()
    }

    fun setBackendUrl(value: String) = update { it.copy(backendUrl = value.trim().ifBlank { BILLNEST_BACKEND_URL }) }
    fun setBackendApiKey(value: String) = update { it.copy(backendApiKey = value.trim()) }
    fun setManualBalance(value: Double) {
        update { it.copy(manualBalance = value) }
        captureTodaySnapshot()
    }
    fun setBalances(items: List<AccountBalance>, connected: Boolean = true) = update { it.copy(balances = items, plaidConnected = connected) }
    fun setBiometric(enabled: Boolean) = update { it.copy(biometricLock = enabled) }

    fun setReminderDays(days: List<Int>) {
        val normalized = days.distinct().sortedDescending()
        update { it.copy(reminderDays = normalized) }
        queue(SyncMapper.settingsMutation(SharedSettings(normalized)))
    }

    fun markPaid(id: String) {
        val linkedDebtId = _data.value.bills.firstOrNull { it.id == id }?.sourceDebtId
        update { data ->
            val paidBill = data.bills.firstOrNull { it.id == id }
            val bills = data.bills.map { bill ->
                if (bill.id != id) bill else {
                    val due = bill.dueDate()
                    val paid = (bill.paidDates + due.toString()).distinct()
                    if (bill.frequency == Frequency.ONE_TIME) {
                        bill.copy(paidDates = paid)
                    } else {
                        bill.copy(
                            dueDateIso = advance(due, bill.frequency).toString(),
                            paidDates = paid
                        )
                    }
                }
            }
            val updatedLinkedBill = linkedDebtId?.let { debtId ->
                bills.firstOrNull { it.sourceDebtId == debtId }
            }
            val debts = if (linkedDebtId != null && updatedLinkedBill != null) {
                val nextDue = runCatching { updatedLinkedBill.dueDate() }.getOrNull()
                if (nextDue == null) data.debts else data.debts.map { debt ->
                    if (debt.id == linkedDebtId) {
                        debt.copy(dueDateIso = nextDue.toString(), dueDay = nextDue.dayOfMonth)
                    } else {
                        debt
                    }
                }
            } else {
                data.debts
            }

            data.copy(
                bills = bills,
                debts = debts,
                reservedFunds = data.reservedFunds.map { fund ->
                    if (fund.billId == id && fund.consumeWhenBillPaid && paidBill != null) {
                        fund.copy(amount = (fund.amount - paidBill.amount).coerceAtLeast(0.0))
                    } else {
                        fund
                    }
                }
            )
        }
        _data.value.bills.firstOrNull { it.id == id }?.let { queue(SyncMapper.billMutation(it)) }
        linkedDebtId?.let { debtId ->
            _data.value.debts.firstOrNull { it.id == debtId }?.let { queue(SyncMapper.debtMutation(it)) }
        }
    }

    fun applyRemoteBill(bill: Bill?, deletedId: String?) {
        update { data ->
            when {
                bill != null -> {
                    val debts = bill.sourceDebtId?.let { debtId ->
                        val due = runCatching { bill.dueDate() }.getOrNull()
                        if (due == null) data.debts else data.debts.map { debt ->
                            if (debt.id == debtId) debt.copy(dueDateIso = due.toString(), dueDay = due.dayOfMonth) else debt
                        }
                    } ?: data.debts
                    data.copy(
                        bills = upsert(data.bills, bill.id, bill) { it.id },
                        debts = debts
                    )
                }
                !deletedId.isNullOrBlank() -> data.copy(bills = data.bills.filterNot { it.id == deletedId })
                else -> data
            }
        }
    }

    fun applyRemotePayday(payday: Payday?, deletedId: String?) {
        update { data ->
            when {
                payday != null -> data.copy(paydays = upsert(data.paydays, payday.id, payday) { it.id })
                !deletedId.isNullOrBlank() -> data.copy(paydays = data.paydays.filterNot { it.id == deletedId })
                else -> data
            }
        }
    }

    fun applyRemoteManualAccount(account: Account?, deletedId: String?) {
        update { data ->
            when {
                account != null -> {
                    val without = data.accounts.filterNot { it.id == account.id }
                    data.copy(accounts = without + account.copy(source = AccountSource.MANUAL))
                }
                !deletedId.isNullOrBlank() -> data.copy(
                    accounts = data.accounts.filterNot { it.id == deletedId },
                    bills = data.bills.map { if (it.accountId == deletedId) it.copy(accountId = null) else it }
                )
                else -> data
            }
        }
    }

    fun applyRemoteSharedSettings(settings: SharedSettings) {
        update { it.copy(reminderDays = settings.reminderDays.distinct().sortedDescending()) }
    }

    fun applyRemoteFinance(kind: String, payload: String?, deletedId: String?) {
        update { data ->
            when (kind) {
                "transaction" -> {
                    val updated = remoteList(data.transactions, payload?.let(SyncMapper::decodeTransaction), deletedId) { it.id }
                        .filterNot { it.id in transactionTombstoneIds(data) }
                    data.copy(transactions = updated)
                }
                "transaction_rule" -> {
                    val rules = remoteList(data.transactionRules, payload?.let(SyncMapper::decodeTransactionRule), deletedId) { it.id }
                    data.copy(transactionRules = rules, transactions = applyTransactionRules(data.transactions, rules))
                }
                "transaction_tombstone" -> {
                    val tombstones = remoteList(
                        data.transactionTombstones,
                        payload?.let(SyncMapper::decodeTransactionTombstone),
                        deletedId
                    ) { it.transactionId }
                    val activeIds = tombstones.mapTo(mutableSetOf()) { it.transactionId }
                    data.copy(
                        transactionTombstones = tombstones,
                        deletedPlaidTransactionIds = (data.deletedPlaidTransactionIds + activeIds).distinct(),
                        transactions = data.transactions.filterNot { it.id in activeIds }
                    )
                }
                "financial_snapshot" -> data.copy(
                    financialSnapshots = remoteList(
                        data.financialSnapshots,
                        payload?.let(SyncMapper::decodeFinancialSnapshot),
                        deletedId
                    ) { it.dateIso }.sortedBy { it.dateIso }
                )
                "budget" -> data.copy(budgets = remoteList(data.budgets, payload?.let(SyncMapper::decodeBudget), deletedId) { it.id })
                "budget_override" -> data.copy(budgetTransactionOverrides = remoteList(data.budgetTransactionOverrides, payload?.let(SyncMapper::decodeBudgetOverride), deletedId) { it.id })
                "budget_adjustment" -> data.copy(budgetAdjustments = remoteList(data.budgetAdjustments, payload?.let(SyncMapper::decodeBudgetAdjustment), deletedId) { it.id })
                "debt" -> reconcileDebtBills(
                    data.copy(
                        debts = remoteList(data.debts, payload?.let(SyncMapper::decodeDebt), deletedId) { it.id }
                    )
                )
                "savings_goal" -> data.copy(savingsGoals = remoteList(data.savingsGoals, payload?.let(SyncMapper::decodeGoal), deletedId) { it.id })
                "reserved_fund" -> data.copy(reservedFunds = remoteList(data.reservedFunds, payload?.let(SyncMapper::decodeReservedFund), deletedId) { it.id })
                "subscription_preference" -> data.copy(subscriptionPreferences = remoteList(data.subscriptionPreferences, payload?.let(SyncMapper::decodeSubscriptionPreference), deletedId) { it.merchantKey })
                "merchant_profile" -> {
                    val profiles = remoteList(data.merchantProfiles, payload?.let(SyncMapper::decodeMerchantProfile), deletedId) { it.id }
                    val cleared = if (!deletedId.isNullOrBlank()) {
                        data.copy(transactions = data.transactions.map { transaction ->
                            if (transaction.merchantProfileId == deletedId) transaction.copy(merchantProfileId = null) else transaction
                        })
                    } else data
                    reapplyStageA(cleared, profiles = profiles)
                }
                "smart_transaction_rule" -> {
                    val rules = remoteList(data.smartTransactionRules, payload?.let(SyncMapper::decodeSmartTransactionRule), deletedId) { it.id }
                    val cleared = if (!deletedId.isNullOrBlank()) {
                        data.copy(transactions = data.transactions.map { transaction ->
                            if (transaction.appliedSmartRuleId == deletedId) transaction.copy(appliedSmartRuleId = null) else transaction
                        })
                    } else data
                    reapplyStageA(cleared, rules = rules)
                }
                "review_resolution" -> data.copy(
                    reviewResolutions = remoteList(
                        data.reviewResolutions,
                        payload?.let(SyncMapper::decodeReviewResolution),
                        deletedId
                    ) { it.fingerprint }
                )
                else -> data
            }
        }
    }

    private fun <T> remoteList(items: List<T>, value: T?, deletedId: String?, idOf: (T) -> String): List<T> = when {
        value != null -> upsert(items, idOf(value), value, idOf)
        !deletedId.isNullOrBlank() -> items.filterNot { idOf(it) == deletedId }
        else -> items
    }

    private fun <T> upsert(items: List<T>, id: String, value: T, idOf: (T) -> String): List<T> {
        var replaced = false
        val mapped = items.map {
            if (idOf(it) == id) {
                replaced = true
                value
            } else it
        }
        return if (replaced) mapped else mapped + value
    }

    private fun advance(d: LocalDate, f: Frequency): LocalDate = when (f) {
        Frequency.ONE_TIME -> d
        Frequency.WEEKLY -> d.plusWeeks(1)
        Frequency.BIWEEKLY -> d.plusWeeks(2)
        Frequency.MONTHLY -> d.plusMonths(1)
        Frequency.YEARLY -> d.plusYears(1)
    }
}
