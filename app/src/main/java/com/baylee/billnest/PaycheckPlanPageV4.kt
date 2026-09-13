package com.baylee.billnest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.*
import com.baylee.billnest.ui.MainViewModel
import com.baylee.billnest.ui.theme.BillNestColors
import java.text.NumberFormat
import java.time.format.DateTimeFormatter

private val paycheckMoney = NumberFormat.getCurrencyInstance()
private fun pMoney(value: Double): String = paycheckMoney.format(value)

@Composable
private fun PaycheckCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BillNestColors.card),
        border = BorderStroke(1.dp, BillNestColors.border)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun PaycheckPlanPageV4(data: AppData, vm: MainViewModel, modifier: Modifier = Modifier) {
    val nextPayday = remember(data.paydays) {
        data.paydays.filter { runCatching { !it.nextDate().isBefore(java.time.LocalDate.now()) }.getOrDefault(false) }
            .minByOrNull { it.nextDate() }
    }
    val plan = remember(data) { runCatching { calculateNextPaycheckPlan(data) }.getOrNull() }

    var settings by remember(nextPayday?.id, nextPayday?.payCalculator) {
        mutableStateOf(nextPayday?.payCalculator ?: defaultBillNestPaycheckSettings())
    }
    val estimate = remember(settings) { calculatePaycheckEstimate(settings) }

    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Text("Paycheck Plan", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Estimate your next check from hours worked, then use the net amount in BillNest planning.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (nextPayday == null) {
            item { PaycheckCard { Text("Add a future payday under Income before using the paycheck calculator.") } }
        } else {
            item {
                PaycheckCard {
                    Text(nextPayday.label, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Next pay date ${nextPayday.nextDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))} • Every 2 weeks",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Hours & pay", style = MaterialTheme.typography.titleMedium)
                    MoneyField("Hourly rate", settings.hourlyRate) { settings = settings.copy(hourlyRate = it) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RateBox("1×", settings.hourlyRate, Modifier.weight(1f))
                        RateBox("1.5×", settings.hourlyRate * 1.5, Modifier.weight(1f))
                        RateBox("2×", settings.hourlyRate * 2.0, Modifier.weight(1f))
                    }
                    HoursField("Regular hours (1×)", settings.regularHours) { settings = settings.copy(regularHours = it) }
                    HoursField("Overtime hours (1.5×)", settings.overtimeHours) { settings = settings.copy(overtimeHours = it) }
                    HoursField("Double-time hours (2×)", settings.doubleTimeHours) { settings = settings.copy(doubleTimeHours = it) }
                }
            }

            item {
                PaycheckCard {
                    Text("Tax setup", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "2026 federal and Wisconsin withholding estimate. Current W-4s do not use the old allowance number, so choose your actual filing status once.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    FederalStatusPicker(settings.federalFilingStatus) {
                        settings = settings.copy(federalFilingStatus = it)
                    }
                    WisconsinStatusPicker(settings.wisconsinWithholdingStatus) {
                        settings = settings.copy(wisconsinWithholdingStatus = it)
                    }
                    IntField("Wisconsin exemptions", settings.wisconsinExemptions) {
                        settings = settings.copy(wisconsinExemptions = it.coerceAtLeast(0))
                    }
                    MoneyField("Pre-tax insurance", settings.preTaxInsurance) {
                        settings = settings.copy(preTaxInsurance = it)
                    }
                    MoneyField("Post-tax medical", settings.postTaxMedical) {
                        settings = settings.copy(postTaxMedical = it)
                    }
                    Text(
                        "Federal defaults: no dependent credits, no extra deductions, no other-income adjustment, and no extra withholding.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            item {
                PaycheckCard {
                    Text("Estimated paycheck", style = MaterialTheme.typography.titleMedium)
                    EstimateLine("Regular pay", estimate.regularPay)
                    EstimateLine("1.5× overtime", estimate.overtimePay)
                    EstimateLine("2× time", estimate.doubleTimePay)
                    HorizontalDivider(color = BillNestColors.border)
                    EstimateLine("Gross pay", estimate.grossPay, strong = true)
                    EstimateLine("Pre-tax insurance", -estimate.preTaxDeductions)
                    EstimateLine("Federal withholding", -estimate.federalWithholding)
                    EstimateLine("Wisconsin withholding", -estimate.wisconsinWithholding)
                    EstimateLine("Social Security", -estimate.socialSecurity)
                    EstimateLine("Medicare", -estimate.medicare)
                    EstimateLine("Post-tax medical", -estimate.postTaxDeductions)
                    HorizontalDivider(color = BillNestColors.border)
                    Text("Estimated net check", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (estimate.requiresTaxSetup) "Choose tax statuses" else pMoney(estimate.estimatedNetPay),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (estimate.requiresTaxSetup) BillNestColors.warning else BillNestColors.positive
                    )
                    Text(
                        "Estimate only. Actual employer payroll can differ because of benefit-plan tax treatment, year-to-date wage limits, rounding, or other deductions.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = {
                            val saved = settings.copy(payPeriodsPerYear = 26)
                            val savedEstimate = calculatePaycheckEstimate(saved)
                            vm.updatePayday(
                                nextPayday.copy(
                                    amount = if (savedEstimate.requiresTaxSetup || savedEstimate.grossPay <= 0.0) nextPayday.amount else savedEstimate.estimatedNetPay,
                                    frequency = Frequency.BIWEEKLY,
                                    payCalculator = saved
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (estimate.requiresTaxSetup) "Save settings" else "Use estimate for next paycheck")
                    }
                    if (estimate.requiresTaxSetup) {
                        Text(
                            "The saved payday amount will not be replaced until both federal and Wisconsin statuses are selected.",
                            color = BillNestColors.warning,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (plan != null) {
                item {
                    PaycheckCard {
                        Text("Planned allocations", style = MaterialTheme.typography.titleMedium)
                        EstimateLine("Paycheck used by plan", plan.income, strong = true)
                        EstimateLine("Bills before next paycheck", -plan.bills)
                        EstimateLine("Reserve contributions", -plan.reserves)
                        EstimateLine("Savings contributions", -plan.savings)
                        val plannedTransfers = recommendedSavingsOrder(data.savingsGoals).filter { goal ->
                            goal.paydayContribution > 0.0 &&
                                !goal.transferFromAccountId.isNullOrBlank() &&
                                !goal.transferToAccountId.isNullOrBlank()
                        }
                        plannedTransfers.forEach { goal ->
                            val from = data.accounts.firstOrNull { it.id == goal.transferFromAccountId }?.name ?: "Checking"
                            val to = data.accounts.firstOrNull { it.id == goal.transferToAccountId }?.name ?: "Savings"
                            Text(
                                goal.name + ": " + pMoney(goal.paydayContribution) + " planned " + from + " → " + to,
                                color = BillNestColors.info,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        EstimateLine("Debt minimums", -plan.debtMinimums)
                        EstimateLine("Variable budgets", -plan.variableBudgets)
                        HorizontalDivider(color = BillNestColors.border)
                        Text("Unassigned", style = MaterialTheme.typography.titleMedium)
                        Text(
                            pMoney(plan.unassigned),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.titleLarge,
                            color = if (plan.unassigned >= 0) BillNestColors.positive else BillNestColors.danger
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RateBox(label: String, amount: Double, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = BillNestColors.cardRaised)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text("${pMoney(amount)}/hr", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MoneyField(label: String, value: Double, onValue: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value == 0.0) "" else value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter { ch -> ch.isDigit() || ch == '.' }
            onValue(text.toDoubleOrNull() ?: 0.0)
        },
        label = { Text(label) },
        prefix = { Text("$") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun HoursField(label: String, value: Double, onValue: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value == 0.0) "" else value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter { ch -> ch.isDigit() || ch == '.' }
            onValue(text.toDoubleOrNull() ?: 0.0)
        },
        label = { Text(label) },
        suffix = { Text("hrs") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun IntField(label: String, value: Int, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter(Char::isDigit).take(2)
            onValue(text.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun FederalStatusPicker(value: FederalFilingStatus?, onValue: (FederalFilingStatus) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Federal filing status: ${federalLabel(value)}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            FederalFilingStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(federalLabel(status)) },
                    onClick = { onValue(status); expanded = false }
                )
            }
        }
    }
}

private fun federalLabel(value: FederalFilingStatus?): String = when (value) {
    FederalFilingStatus.SINGLE_OR_MARRIED_SEPARATELY -> "Single / Married filing separately"
    FederalFilingStatus.MARRIED_FILING_JOINTLY -> "Married filing jointly"
    FederalFilingStatus.HEAD_OF_HOUSEHOLD -> "Head of household"
    null -> "Choose"
}

@Composable
private fun WisconsinStatusPicker(value: WisconsinWithholdingStatus?, onValue: (WisconsinWithholdingStatus) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Wisconsin status: ${value?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Choose"}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            WisconsinWithholdingStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    onClick = { onValue(status); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun EstimateLine(label: String, amount: Double, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (amount < 0) "-${pMoney(-amount)}" else pMoney(amount),
            style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false
        )
    }
}
