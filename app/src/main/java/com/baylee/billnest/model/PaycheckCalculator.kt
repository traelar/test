package com.baylee.billnest.model

import kotlin.math.max

enum class FederalFilingStatus {
    SINGLE_OR_MARRIED_SEPARATELY,
    MARRIED_FILING_JOINTLY,
    HEAD_OF_HOUSEHOLD
}

enum class WisconsinWithholdingStatus { SINGLE, MARRIED }

data class PaycheckCalculatorSettings(
    val hourlyRate: Double = 28.25,
    val regularHours: Double = 0.0,
    val overtimeHours: Double = 0.0,
    val doubleTimeHours: Double = 0.0,
    val preTaxInsurance: Double = 87.25,
    val postTaxMedical: Double = 9.44,
    val federalFilingStatus: FederalFilingStatus? = null,
    val wisconsinWithholdingStatus: WisconsinWithholdingStatus? = null,
    val wisconsinExemptions: Int = 0,
    val payPeriodsPerYear: Int = 26
)

data class PaycheckEstimate(
    val regularPay: Double,
    val overtimePay: Double,
    val doubleTimePay: Double,
    val grossPay: Double,
    val taxableWages: Double,
    val preTaxDeductions: Double,
    val federalWithholding: Double,
    val wisconsinWithholding: Double,
    val socialSecurity: Double,
    val medicare: Double,
    val postTaxDeductions: Double,
    val estimatedNetPay: Double,
    val requiresTaxSetup: Boolean
)

fun defaultBillNestPaycheckSettings(): PaycheckCalculatorSettings = PaycheckCalculatorSettings(
    hourlyRate = 28.25,
    preTaxInsurance = 87.25,
    postTaxMedical = 9.44,
    wisconsinExemptions = 0,
    payPeriodsPerYear = 26
)

fun calculatePaycheckEstimate(settings: PaycheckCalculatorSettings): PaycheckEstimate {
    val rate = settings.hourlyRate.coerceAtLeast(0.0)
    val regularPay = settings.regularHours.coerceAtLeast(0.0) * rate
    val overtimePay = settings.overtimeHours.coerceAtLeast(0.0) * rate * 1.5
    val doubleTimePay = settings.doubleTimeHours.coerceAtLeast(0.0) * rate * 2.0
    val gross = regularPay + overtimePay + doubleTimePay
    val preTax = settings.preTaxInsurance.coerceAtLeast(0.0).coerceAtMost(gross)
    val taxable = (gross - preTax).coerceAtLeast(0.0)
    val periods = settings.payPeriodsPerYear.coerceAtLeast(1)

    val federal = settings.federalFilingStatus?.let {
        federalWithholding2026(taxable, periods, it)
    } ?: 0.0
    val wisconsin = settings.wisconsinWithholdingStatus?.let {
        wisconsinWithholding2026(taxable, periods, it, settings.wisconsinExemptions)
    } ?: 0.0

    // Assumes the configured pre-tax medical/insurance deduction is a cafeteria-plan deduction
    // excluded from Social Security and Medicare wages. The UI labels this as an estimate.
    val socialSecurity = taxable * 0.062
    val medicare = taxable * 0.0145
    val postTax = settings.postTaxMedical.coerceAtLeast(0.0)
    val net = gross - preTax - federal - wisconsin - socialSecurity - medicare - postTax

    return PaycheckEstimate(
        regularPay = regularPay,
        overtimePay = overtimePay,
        doubleTimePay = doubleTimePay,
        grossPay = gross,
        taxableWages = taxable,
        preTaxDeductions = preTax,
        federalWithholding = federal,
        wisconsinWithholding = wisconsin,
        socialSecurity = socialSecurity,
        medicare = medicare,
        postTaxDeductions = postTax,
        estimatedNetPay = max(0.0, net),
        requiresTaxSetup = settings.federalFilingStatus == null || settings.wisconsinWithholdingStatus == null
    )
}

private data class TaxBracket(
    val floor: Double,
    val ceiling: Double?,
    val baseTax: Double,
    val rate: Double
)

private fun federalWithholding2026(
    taxablePay: Double,
    periods: Int,
    status: FederalFilingStatus
): Double {
    val annual = taxablePay * periods
    val brackets = when (status) {
        FederalFilingStatus.MARRIED_FILING_JOINTLY -> listOf(
            TaxBracket(0.0, 19_300.0, 0.0, 0.0),
            TaxBracket(19_300.0, 44_100.0, 0.0, 0.10),
            TaxBracket(44_100.0, 120_100.0, 2_480.0, 0.12),
            TaxBracket(120_100.0, 230_700.0, 11_600.0, 0.22),
            TaxBracket(230_700.0, 422_850.0, 35_932.0, 0.24),
            TaxBracket(422_850.0, 531_750.0, 82_048.0, 0.32),
            TaxBracket(531_750.0, 788_000.0, 116_896.0, 0.35),
            TaxBracket(788_000.0, null, 206_583.50, 0.37)
        )
        FederalFilingStatus.SINGLE_OR_MARRIED_SEPARATELY -> listOf(
            TaxBracket(0.0, 7_500.0, 0.0, 0.0),
            TaxBracket(7_500.0, 19_900.0, 0.0, 0.10),
            TaxBracket(19_900.0, 57_900.0, 1_240.0, 0.12),
            TaxBracket(57_900.0, 113_200.0, 5_800.0, 0.22),
            TaxBracket(113_200.0, 209_275.0, 17_966.0, 0.24),
            TaxBracket(209_275.0, 263_725.0, 41_024.0, 0.32),
            TaxBracket(263_725.0, 648_100.0, 58_448.0, 0.35),
            TaxBracket(648_100.0, null, 192_979.25, 0.37)
        )
        FederalFilingStatus.HEAD_OF_HOUSEHOLD -> listOf(
            TaxBracket(0.0, 15_550.0, 0.0, 0.0),
            TaxBracket(15_550.0, 33_250.0, 0.0, 0.10),
            TaxBracket(33_250.0, 83_000.0, 1_770.0, 0.12),
            TaxBracket(83_000.0, 121_250.0, 7_740.0, 0.22),
            TaxBracket(121_250.0, 217_300.0, 16_155.0, 0.24),
            TaxBracket(217_300.0, 271_750.0, 39_207.0, 0.32),
            TaxBracket(271_750.0, 656_150.0, 56_631.0, 0.35),
            TaxBracket(656_150.0, null, 191_171.0, 0.37)
        )
    }
    val bracket = brackets.first { annual >= it.floor && (it.ceiling == null || annual < it.ceiling) }
    val annualWithholding = bracket.baseTax + (annual - bracket.floor) * bracket.rate
    return annualWithholding.coerceAtLeast(0.0) / periods
}

private fun wisconsinWithholding2026(
    taxablePay: Double,
    periods: Int,
    status: WisconsinWithholdingStatus,
    exemptions: Int
): Double {
    val annualGross = taxablePay * periods
    val deduction = when (status) {
        WisconsinWithholdingStatus.SINGLE -> when {
            annualGross < 17_780.0 -> 6_702.0
            annualGross >= 73_630.0 -> 0.0
            else -> 6_702.0 - 0.12 * (annualGross - 17_780.0)
        }
        WisconsinWithholdingStatus.MARRIED -> when {
            annualGross < 25_727.0 -> 9_461.0
            annualGross >= 73_032.0 -> 0.0
            else -> 9_461.0 - 0.20 * (annualGross - 25_727.0)
        }
    }.coerceAtLeast(0.0)
    val annualNet = (annualGross - deduction - exemptions.coerceAtLeast(0) * 400.0).coerceAtLeast(0.0)
    val annualTax = when {
        annualNet <= 12_760.0 -> annualNet * 0.0354
        annualNet <= 25_520.0 -> 451.70 + (annualNet - 12_760.0) * 0.0465
        annualNet <= 280_950.0 -> 1_045.04 + (annualNet - 25_520.0) * 0.0530
        else -> 14_582.83 + (annualNet - 280_950.0) * 0.0765
    }
    return annualTax.coerceAtLeast(0.0) / periods
}
