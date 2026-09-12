package com.baylee.billnest.model

/**
 * Accounts shown in the normal asset/account UI.
 * Credit accounts are liabilities and are represented through the Debt tab instead.
 */
fun visibleAssetAccounts(accounts: List<Account>): List<Account> =
    accounts.filterNot { it.type == AccountType.CREDIT || it.role == AccountRole.CREDIT }
