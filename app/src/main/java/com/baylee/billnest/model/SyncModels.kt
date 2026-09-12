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

    fun encodeBill(bill: Bill): String = gson.toJson(bill)
    fun decodeBill(payloadJson: String): Bill = gson.fromJson(payloadJson, Bill::class.java)

    fun encodePayday(payday: Payday): String = gson.toJson(payday)
    fun decodePayday(payloadJson: String): Payday = gson.fromJson(payloadJson, Payday::class.java)

    fun encodeAccount(account: Account): String = gson.toJson(account)
    fun decodeAccount(payloadJson: String): Account = gson.fromJson(payloadJson, Account::class.java)

    fun encodeAccountPreference(preference: AccountPreference): String = gson.toJson(preference)
    fun decodeAccountPreference(payloadJson: String): AccountPreference =
        gson.fromJson(payloadJson, AccountPreference::class.java)

    fun encodeSettings(settings: SharedSettings): String = gson.toJson(settings)
    fun decodeSettings(payloadJson: String): SharedSettings = gson.fromJson(payloadJson, SharedSettings::class.java)

    fun billMutation(bill: Bill): SyncRecordDraft =
        SyncRecordDraft("bill", bill.id, encodeBill(bill))

    fun paydayMutation(payday: Payday): SyncRecordDraft =
        SyncRecordDraft("payday", payday.id, encodePayday(payday))

    fun accountMutation(account: Account): SyncRecordDraft? =
        if (account.source == AccountSource.MANUAL) {
            SyncRecordDraft("manual_account", account.id, encodeAccount(account))
        } else {
            null
        }

    fun accountPreferenceMutation(preference: AccountPreference): SyncRecordDraft =
        SyncRecordDraft("account_preference", preference.accountKey, encodeAccountPreference(preference))

    fun settingsMutation(settings: SharedSettings): SyncRecordDraft =
        SyncRecordDraft("settings", "household", encodeSettings(settings))
}
