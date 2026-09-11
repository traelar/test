package com.baylee.billnest.model

import java.time.LocalDate
import java.util.UUID

enum class Frequency { ONE_TIME, WEEKLY, BIWEEKLY, MONTHLY, YEARLY }

val BillCategories = listOf(
    "Housing", "Utilities", "Phone/Internet", "Insurance", "Car", "Credit Card",
    "Subscriptions", "Medical", "Kids", "Groceries", "Other"
)

data class Bill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val amount: Double,
    val dueDateIso: String,
    val frequency: Frequency = Frequency.MONTHLY,
    val autopay: Boolean = false,
    val category: String = "Other",
    val notes: String = "",
    val variableAmount: Boolean = false,
    val paidDates: List<String> = emptyList()
) {
    fun dueDate(): LocalDate = LocalDate.parse(dueDateIso)
    fun isPaidFor(date: LocalDate = dueDate()): Boolean = paidDates.contains(date.toString())
}

data class Payday(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "Paycheck",
    val amount: Double,
    val nextDateIso: String,
    val frequency: Frequency = Frequency.BIWEEKLY
) {
    fun nextDate(): LocalDate = LocalDate.parse(nextDateIso)
}

data class AccountBalance(
    val accountId: String,
    val name: String,
    val mask: String = "",
    val current: Double,
    val available: Double? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class AppData(
    val bills: List<Bill> = emptyList(),
    val paydays: List<Payday> = emptyList(),
    val manualBalance: Double = 0.0,
    val balances: List<AccountBalance> = emptyList(),
    val plaidConnected: Boolean = false,
    val reminderDays: List<Int> = listOf(7, 3, 1, 0),
    val biometricLock: Boolean = false
)
