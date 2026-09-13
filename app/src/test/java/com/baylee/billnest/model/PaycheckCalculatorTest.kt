package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaycheckCalculatorTest {
    @Test
    fun calculatesRegularOvertimeDoubleTimeAndKnownDeductions() {
        val result = calculatePaycheckEstimate(
            settings = PaycheckCalculatorSettings(
                hourlyRate = 28.25,
                regularHours = 80.0,
                overtimeHours = 8.0,
                doubleTimeHours = 4.0,
                preTaxInsurance = 87.25,
                postTaxMedical = 9.44,
                federalFilingStatus = FederalFilingStatus.SINGLE_OR_MARRIED_SEPARATELY,
                wisconsinWithholdingStatus = WisconsinWithholdingStatus.SINGLE,
                wisconsinExemptions = 0
            )
        )

        assertEquals(2260.0, result.regularPay, 0.001)
        assertEquals(339.0, result.overtimePay, 0.001)
        assertEquals(226.0, result.doubleTimePay, 0.001)
        assertEquals(2825.0, result.grossPay, 0.001)
        assertEquals(2737.75, result.taxableWages, 0.001)
        assertEquals(87.25, result.preTaxDeductions, 0.001)
        assertEquals(9.44, result.postTaxDeductions, 0.001)
        assertEquals(169.7405, result.socialSecurity, 0.001)
        assertEquals(39.697375, result.medicare, 0.001)
        assertEquals(335.458846, result.federalWithholding, 0.01)
        assertEquals(132.674116, result.wisconsinWithholding, 0.01)
        assertEquals(2050.739161, result.estimatedNetPay, 0.01)
    }

    @Test
    fun requiresFederalAndWisconsinStatusesBeforeUsingEstimateAsPaydayAmount() {
        val incomplete = calculatePaycheckEstimate(
            PaycheckCalculatorSettings(
                hourlyRate = 28.25,
                regularHours = 80.0,
                wisconsinExemptions = 0
            )
        )
        assertTrue(incomplete.requiresTaxSetup)

        val complete = calculatePaycheckEstimate(
            PaycheckCalculatorSettings(
                hourlyRate = 28.25,
                regularHours = 80.0,
                federalFilingStatus = FederalFilingStatus.HEAD_OF_HOUSEHOLD,
                wisconsinWithholdingStatus = WisconsinWithholdingStatus.SINGLE,
                wisconsinExemptions = 0
            )
        )
        assertTrue(!complete.requiresTaxSetup)
        assertTrue(complete.estimatedNetPay > 0.0)
    }

    @Test
    fun userDefaultsMatchConfiguredJob() {
        val defaults = defaultBillNestPaycheckSettings()
        assertEquals(28.25, defaults.hourlyRate, 0.001)
        assertEquals(87.25, defaults.preTaxInsurance, 0.001)
        assertEquals(9.44, defaults.postTaxMedical, 0.001)
        assertEquals(0, defaults.wisconsinExemptions)
        assertEquals(26, defaults.payPeriodsPerYear)
    }
}
