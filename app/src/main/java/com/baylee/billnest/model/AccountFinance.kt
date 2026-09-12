package com.baylee.billnest.model

enum class AccountRole { SPENDING, SAVINGS, CREDIT_DEBT, OTHER }

data class AccountPreference(
    val accountKey: String,
    val displayOrder: Int,
    val role: AccountRole,
    val includeInTotal: Boolean,
    val includeInSpending: Boolean,
    val customName: String? = null
)

data class AccountMoneySummary(
    val totalMoney: Double,
    val spendingMoney: Double,
    val savingsMoney: Double
)

object AccountFinance {
    fun stableKey(account: Account): String =
        if (account.source == AccountSource.PLAID && !account.plaidAccountId.isNullOrBlank()) {
            "plaid:${account.plaidAccountId}"
        } else {
            "manual:${account.id}"
        }

    fun canonicalLocalId(account: Account): String =
        if (account.source == AccountSource.PLAID && !account.plaidAccountId.isNullOrBlank()) {
            "plaid:${account.plaidAccountId}"
        } else account.id

    fun defaultPreference(account: Account, displayOrder: Int): AccountPreference {
        val role = when (account.type) {
            AccountType.CHECKING, AccountType.CASH -> AccountRole.SPENDING
            AccountType.SAVINGS -> AccountRole.SAVINGS
            AccountType.OTHER -> AccountRole.OTHER
        }
        return AccountPreference(
            accountKey = stableKey(account),
            displayOrder = displayOrder,
            role = role,
            includeInTotal = true,
            includeInSpending = role == AccountRole.SPENDING
        )
    }

    fun preferenceFor(
        account: Account,
        preferences: List<AccountPreference>,
        fallbackOrder: Int
    ): AccountPreference = preferences.firstOrNull { it.accountKey == stableKey(account) }
        ?: defaultPreference(account, fallbackOrder)

    fun displayName(account: Account, preferences: List<AccountPreference>, fallbackOrder: Int): String =
        preferenceFor(account, preferences, fallbackOrder).customName?.takeIf { it.isNotBlank() } ?: account.name

    fun sortAccounts(accounts: List<Account>, preferences: List<AccountPreference>): List<Account> =
        accounts.withIndex()
            .sortedWith(
                compareBy<IndexedValue<Account>> {
                    preferenceFor(it.value, preferences, it.index).displayOrder
                }.thenBy { it.index }
            )
            .map { it.value }

    fun summarize(accounts: List<Account>, preferences: List<AccountPreference>): AccountMoneySummary {
        var total = 0.0
        var spending = 0.0
        var savings = 0.0
        accounts.forEachIndexed { index, account ->
            val preference = preferenceFor(account, preferences, index)
            if (preference.includeInTotal) total += account.balance
            if (preference.includeInSpending) spending += account.balance
            if (preference.role == AccountRole.SAVINGS && preference.includeInTotal) savings += account.balance
        }
        return AccountMoneySummary(totalMoney = total, spendingMoney = spending, savingsMoney = savings)
    }
}
