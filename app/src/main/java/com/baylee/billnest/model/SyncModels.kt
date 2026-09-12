package com.baylee.billnest.model

import com.google.gson.Gson

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

    fun encodeBill(value: Bill): String = gson.toJson(value)
    fun decodeBill(json: String): Bill = gson.fromJson(json, Bill::class.java)
    fun encodePayday(value: Payday): String = gson.toJson(value)
    fun decodePayday(json: String): Payday = gson.fromJson(json, Payday::class.java)
    fun encodeAccount(value: Account): String = gson.toJson(value)
    fun decodeAccount(json: String): Account = gson.fromJson(json, Account::class.java)
    fun encodeAccountPreference(value: AccountPreference): String = gson.toJson(value)
    fun decodeAccountPreference(json: String): AccountPreference = gson.fromJson(json, AccountPreference::class.java)
    fun encodeTransaction(value: FinanceTransaction): String = gson.toJson(value)
    fun decodeTransaction(json: String): FinanceTransaction = gson.fromJson(json, FinanceTransaction::class.java)
    fun encodeBudget(value: Budget): String = gson.toJson(value)
    fun decodeBudget(json: String): Budget = gson.fromJson(json, Budget::class.java)
    fun encodeDebt(value: Debt): String = gson.toJson(value)
    fun decodeDebt(json: String): Debt = gson.fromJson(json, Debt::class.java)
    fun encodeGoal(value: SavingsGoal): String = gson.toJson(value)
    fun decodeGoal(json: String): SavingsGoal = gson.fromJson(json, SavingsGoal::class.java)
    fun encodeReservedFund(value: ReservedFund): String = gson.toJson(value)
    fun decodeReservedFund(json: String): ReservedFund = gson.fromJson(json, ReservedFund::class.java)
    fun encodeBillMatch(value: BillTransactionMatch): String = gson.toJson(value)
    fun decodeBillMatch(json: String): BillTransactionMatch = gson.fromJson(json, BillTransactionMatch::class.java)
    fun encodeSubscriptionOverride(value: SubscriptionOverride): String = gson.toJson(value)
    fun decodeSubscriptionOverride(json: String): SubscriptionOverride = gson.fromJson(json, SubscriptionOverride::class.java)
    fun encodeSettings(value: SharedSettings): String = gson.toJson(value)
    fun decodeSettings(json: String): SharedSettings = gson.fromJson(json, SharedSettings::class.java)

    fun billMutation(value: Bill) = SyncRecordDraft("bill", value.id, encodeBill(value))
    fun paydayMutation(value: Payday) = SyncRecordDraft("payday", value.id, encodePayday(value))
    fun accountMutation(value: Account): SyncRecordDraft? =
        if (value.source == AccountSource.MANUAL) SyncRecordDraft("manual_account", value.id, encodeAccount(value)) else null
    fun accountPreferenceMutation(value: AccountPreference) =
        SyncRecordDraft("account_preference", value.accountKey, encodeAccountPreference(value))
    fun manualTransactionMutation(value: FinanceTransaction) =
        SyncRecordDraft("manual_transaction", value.id, encodeTransaction(value))
    fun budgetMutation(value: Budget) = SyncRecordDraft("budget", value.id, encodeBudget(value))
    fun debtMutation(value: Debt) = SyncRecordDraft("debt", value.id, encodeDebt(value))
    fun goalMutation(value: SavingsGoal) = SyncRecordDraft("goal", value.id, encodeGoal(value))
    fun reservedFundMutation(value: ReservedFund) = SyncRecordDraft("reserved_fund", value.id, encodeReservedFund(value))
    fun billMatchMutation(value: BillTransactionMatch) = SyncRecordDraft("bill_match", value.id, encodeBillMatch(value))
    fun subscriptionOverrideMutation(value: SubscriptionOverride) =
        SyncRecordDraft("subscription_override", value.merchantKey, encodeSubscriptionOverride(value))
    fun settingsMutation(value: SharedSettings) = SyncRecordDraft("settings", "household", encodeSettings(value))
}
