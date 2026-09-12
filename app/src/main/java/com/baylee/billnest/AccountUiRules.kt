package com.baylee.billnest

import com.baylee.billnest.model.Account
import com.baylee.billnest.model.visibleAssetAccounts

/**
 * Keep liability/credit accounts out of the normal Accounts list while preserving
 * them in AppData for Plaid refreshes and Debt-tab synchronization.
 *
 * AccountsPage already sorts its List<Account> before rendering, so this more
 * specific package-level overload applies the asset-only rule at that boundary.
 */
fun List<Account>.sortedWith(comparator: Comparator<in Account>): List<Account> {
    val visible = visibleAssetAccounts(this).toMutableList()
    visible.sortWith(comparator)
    return visible
}
