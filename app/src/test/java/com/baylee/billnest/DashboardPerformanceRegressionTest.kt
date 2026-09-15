package com.baylee.billnest

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPerformanceRegressionTest {
    @Test
    fun dashboardDoesNotRenderReviewScannerCard() {
        val source = sequenceOf(
            File("app/src/main/java/com/baylee/billnest/FinanceActivity.kt"),
            File("src/main/java/com/baylee/billnest/FinanceActivity.kt")
        ).firstOrNull { it.isFile }?.readText()
            ?: error("FinanceActivity.kt not found from test working directory")

        assertTrue(
            "Dashboard should render the normal dashboard directly",
            source.contains("\"Dashboard\" -> DashboardV4(data, Modifier.padding(pad))")
        )
        assertFalse(
            "Dashboard must not invoke DashboardV5 because it calculates the full Review Inbox count on the UI thread",
            source.contains("\"Dashboard\" -> DashboardV5(")
        )
    }
}
