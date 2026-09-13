package com.baylee.billnest

import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationV30Test {
    @Test
    fun financeNavigationIncludesReviewAndAutomationDestinations() {
        val destinations = billNestDestinationsV30()
        assertTrue("Review Inbox should be directly reachable", "Review Inbox" in destinations)
        assertTrue("Merchants & Rules should be directly reachable", "Merchants & Rules" in destinations)
    }
}
