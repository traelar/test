package com.baylee.billnest.model

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser

data class SyncRecordDraft(
    val kind: String,
    val recordId: String,
    val payloadJson: String
)

data class SyncMutation(
    val mutationId: String,
    val kind: String,
    val recordId: String,
    val baseVersion: Int,
    val deleted: Boolean,
    val payloadJson: String
)

data class SyncApplied(
    val mutationId: String,
    val kind: String,
    val recordId: String,
    val version: Int
)

data class SyncServerRecord(
    val kind: String,
    val recordId: String,
    val version: Int,
    val deleted: Boolean,
    val payloadJson: String,
    val updatedAt: String
)

data class SyncConflict(
    val mutationId: String,
    val kind: String,
    val recordId: String,
    val baseVersion: Int,
    val serverRecord: SyncServerRecord?
)

data class SyncChange(
    val eventId: Long,
    val kind: String,
    val recordId: String,
    val version: Int,
    val deleted: Boolean,
    val payloadJson: String,
    val updatedAt: String
)

data class SyncResponse(
    val cursor: Long,
    val applied: List<SyncApplied>,
    val conflicts: List<SyncConflict>,
    val changes: List<SyncChange>
)

data class SyncSummary(
    val appliedCount: Int,
    val conflictCount: Int,
    val changeCount: Int,
    val cursor: Long
)

data class SharedSettings(
    val reminderDays: List<Int> = listOf(7, 3, 1, 0)
)

object SyncMapper {
    private val gson = Gson()

    fun encodeBill(bill: Bill): String = gson.toJson(bill)
    fun decodeBill(payloadJson: String): Bill = gson.fromJson(payloadJson, Bill::class.java)

    fun encodePayday(payday: Payday): String = gson.toJson(payday)
    fun decodePayday(payloadJson: String): Payday = gson.fromJson(payloadJson, Payday::class.java)

    fun encodeAccount(account: Account): String = gson.toJson(account)
    fun decodeAccount(payloadJson: String): Account = gson.fromJson(payloadJson, Account::class.java)

    fun encodeSettings(settings: SharedSettings): String = gson.toJson(settings)
    fun decodeSettings(payloadJson: String): SharedSettings = gson.fromJson(payloadJson, SharedSettings::class.java)

    fun encodeTransaction(value: FinanceTransaction): String = gson.toJson(value)
    fun decodeTransaction(value: String): FinanceTransaction = gson.fromJson(value, FinanceTransaction::class.java)

    fun encodeTransactionRule(value: TransactionRule): String = gson.toJson(value)
    fun decodeTransactionRule(value: String): TransactionRule = gson.fromJson(value, TransactionRule::class.java)

    fun encodeTransactionTombstone(value: TransactionTombstone): String = gson.toJson(value)
    fun decodeTransactionTombstone(value: String): TransactionTombstone = gson.fromJson(value, TransactionTombstone::class.java)

    fun encodeFinancialSnapshot(value: FinancialSnapshot): String = gson.toJson(value)
    fun decodeFinancialSnapshot(value: String): FinancialSnapshot = gson.fromJson(value, FinancialSnapshot::class.java)

    fun encodeBudget(value: Budget): String = gson.toJson(value)
    fun decodeBudget(value: String): Budget {
        val root = JsonParser.parseString(value).asJsonObject
        normalizeBudgetJson(root)
        return gson.fromJson(root, Budget::class.java)
    }

    fun encodeBudgetOverride(value: BudgetTransactionOverride): String = gson.toJson(value)
    fun decodeBudgetOverride(value: String): BudgetTransactionOverride = gson.fromJson(value, BudgetTransactionOverride::class.java)

    fun encodeBudgetAdjustment(value: BudgetAdjustment): String = gson.toJson(value)
    fun decodeBudgetAdjustment(value: String): BudgetAdjustment = gson.fromJson(value, BudgetAdjustment::class.java)

    fun encodeDebt(value: Debt): String = gson.toJson(value)
    fun decodeDebt(value: String): Debt = gson.fromJson(value, Debt::class.java)
    fun encodeGoal(value: SavingsGoal): String = gson.toJson(value)
    fun decodeGoal(value: String): SavingsGoal = gson.fromJson(value, SavingsGoal::class.java)
    fun encodeReservedFund(value: ReservedFund): String = gson.toJson(value)
    fun decodeReservedFund(value: String): ReservedFund = gson.fromJson(value, ReservedFund::class.java)
    fun encodeSubscriptionPreference(value: SubscriptionPreference): String = gson.toJson(value)
    fun decodeSubscriptionPreference(value: String): SubscriptionPreference = gson.fromJson(value, SubscriptionPreference::class.java)

    fun billMutation(bill: Bill): SyncRecordDraft = SyncRecordDraft("bill", bill.id, encodeBill(bill))
    fun paydayMutation(payday: Payday): SyncRecordDraft = SyncRecordDraft("payday", payday.id, encodePayday(payday))

    fun accountMutation(account: Account): SyncRecordDraft? =
        if (account.source == AccountSource.MANUAL) SyncRecordDraft("manual_account", account.id, encodeAccount(account)) else null

    fun settingsMutation(settings: SharedSettings): SyncRecordDraft = SyncRecordDraft("settings", "household", encodeSettings(settings))
    fun transactionMutation(value: FinanceTransaction) = SyncRecordDraft("transaction", value.id, encodeTransaction(value))
    fun transactionRuleMutation(value: TransactionRule) = SyncRecordDraft("transaction_rule", value.id, encodeTransactionRule(value))
    fun transactionTombstoneMutation(value: TransactionTombstone) = SyncRecordDraft("transaction_tombstone", value.transactionId, encodeTransactionTombstone(value))
    fun financialSnapshotMutation(value: FinancialSnapshot) = SyncRecordDraft("financial_snapshot", value.dateIso, encodeFinancialSnapshot(value))
    fun budgetMutation(value: Budget) = SyncRecordDraft("budget", value.id, encodeBudget(value))
    fun budgetOverrideMutation(value: BudgetTransactionOverride) = SyncRecordDraft("budget_override", value.id, encodeBudgetOverride(value))
    fun budgetAdjustmentMutation(value: BudgetAdjustment) = SyncRecordDraft("budget_adjustment", value.id, encodeBudgetAdjustment(value))
    fun debtMutation(value: Debt) = SyncRecordDraft("debt", value.id, encodeDebt(value))
    fun goalMutation(value: SavingsGoal) = SyncRecordDraft("savings_goal", value.id, encodeGoal(value))
    fun reservedFundMutation(value: ReservedFund) = SyncRecordDraft("reserved_fund", value.id, encodeReservedFund(value))
    fun subscriptionPreferenceMutation(value: SubscriptionPreference) = SyncRecordDraft("subscription_preference", value.merchantKey, encodeSubscriptionPreference(value))

    internal fun normalizeBudgetJson(root: com.google.gson.JsonObject) {
        listOf(
            "includedCategories", "excludedCategories", "includedMerchants", "excludedMerchants",
            "includedAccountIds", "excludedAccountIds"
        ).forEach { field ->
            if (!root.has(field) || root.get(field).isJsonNull) root.add(field, JsonArray())
        }
        if (!root.has("rolloverMode") || root.get("rolloverMode").isJsonNull) {
            root.addProperty("rolloverMode", if (root.get("rollover")?.asBoolean == true) "CARRY_UNUSED" else "RESET")
        }
        if (!root.has("warningPercent") || root.get("warningPercent").isJsonNull) root.addProperty("warningPercent", 90)
        if (!root.has("paceTracking") || root.get("paceTracking").isJsonNull) root.addProperty("paceTracking", true)
    }
}