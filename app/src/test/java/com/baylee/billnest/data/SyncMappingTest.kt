package com.baylee.billnest.data

import com.baylee.billnest.model.Account
import com.baylee.billnest.model.AccountSource
import com.baylee.billnest.model.Bill
import com.baylee.billnest.model.Budget
import com.baylee.billnest.model.BudgetAdjustment
import com.baylee.billnest.model.BudgetOverrideAction
import com.baylee.billnest.model.BudgetPeriod
import com.baylee.billnest.model.BudgetRolloverMode
import com.baylee.billnest.model.BudgetTransactionOverride
import com.baylee.billnest.model.Debt
import com.baylee.billnest.model.DebtType
import com.baylee.billnest.model.FinanceTransaction
import com.baylee.billnest.model.Payday
import com.baylee.billnest.model.ReservedFund
import com.baylee.billnest.model.SavingsGoal
import com.baylee.billnest.model.SubscriptionPreference
import com.baylee.billnest.model.SubscriptionStatus
import com.baylee.billnest.model.SyncMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncMappingTest {
    @Test
    fun billRoundTripsThroughSharedRecordPayload() {
        val bill = Bill(name = "Electric", amount = 121.44, dueDateIso = "2026-09-20")
        val encoded = SyncMapper.encodeBill(bill)
        val decoded = SyncMapper.decodeBill(encoded)
        assertEquals(bill, decoded)
    }

    @Test
    fun paydayRoundTripsThroughSharedRecordPayload() {
        val payday = Payday(label = "Paycheck", amount = 1800.0, nextDateIso = "2026-09-18")
        assertEquals(payday, SyncMapper.decodePayday(SyncMapper.encodePayday(payday)))
    }

    @Test
    fun plaidAccountsAreNotUploadedAsManualAccountRecords() {
        val plaid = Account(name = "Checking", source = AccountSource.PLAID, plaidAccountId = "p1")
        assertNull(SyncMapper.accountMutation(plaid))
    }

    @Test
    fun manualAccountsProduceSharedRecordDrafts() {
        val manual = Account(name = "Cash", source = AccountSource.MANUAL, balance = 75.0)
        val draft = SyncMapper.accountMutation(manual)
        assertEquals("manual_account", draft?.kind)
        assertEquals(manual.id, draft?.recordId)
        assertEquals(manual, SyncMapper.decodeAccount(draft!!.payloadJson))
    }

    @Test
    fun v2FinanceRecordsRoundTripThroughSharedPayloads() {
        val transaction = FinanceTransaction(name = "Groceries", amount = 42.0, dateIso = "2026-09-12")
        val budget = Budget(
            name = "Food",
            amount = 500.0,
            period = BudgetPeriod.PAYCHECK,
            includedCategories = listOf("Groceries", "Household"),
            excludedMerchants = listOf("Walmart Pharmacy"),
            paydayId = "payday-1",
            rolloverMode = BudgetRolloverMode.CARRY_UNUSED,
            warningPercent = 80
        )
        val debt = Debt(name = "Home", type = DebtType.MORTGAGE, balance = 100000.0)
        val goal = SavingsGoal(name = "Emergency", targetAmount = 3000.0)
        val reserve = ReservedFund(name = "Insurance", paydayContribution = 50.0)
        val subscription = SubscriptionPreference("video", "Video", SubscriptionStatus.CONFIRMED)
        assertEquals(transaction, SyncMapper.decodeTransaction(SyncMapper.encodeTransaction(transaction)))
        assertEquals(budget, SyncMapper.decodeBudget(SyncMapper.encodeBudget(budget)))
        assertEquals(debt, SyncMapper.decodeDebt(SyncMapper.encodeDebt(debt)))
        assertEquals(goal, SyncMapper.decodeGoal(SyncMapper.encodeGoal(goal)))
        assertEquals(reserve, SyncMapper.decodeReservedFund(SyncMapper.encodeReservedFund(reserve)))
        assertEquals(subscription, SyncMapper.decodeSubscriptionPreference(SyncMapper.encodeSubscriptionPreference(subscription)))
    }

    @Test
    fun legacyAlpha17BudgetPayloadGetsSafeSmartBudgetDefaults() {
        val decoded = SyncMapper.decodeBudget("""{
            "id":"legacy-budget",
            "name":"Groceries",
            "amount":500.0,
            "category":"Groceries",
            "period":"MONTHLY",
            "rollover":false
        }""".trimIndent())

        assertEquals(emptyList<String>(), decoded.includedCategories)
        assertEquals(emptyList<String>(), decoded.excludedCategories)
        assertEquals(emptyList<String>(), decoded.includedMerchants)
        assertEquals(emptyList<String>(), decoded.excludedMerchants)
        assertEquals(emptyList<String>(), decoded.includedAccountIds)
        assertEquals(emptyList<String>(), decoded.excludedAccountIds)
        assertEquals(BudgetRolloverMode.RESET, decoded.rolloverMode)
        assertEquals(90, decoded.warningPercent)
    }

    @Test
    fun budgetOverridesAndAdjustmentsRoundTripAndUseDedicatedKinds() {
        val override = BudgetTransactionOverride(
            id = "override-1",
            transactionId = "tx-1",
            budgetId = "budget-1",
            periodStartIso = "2026-09-01",
            periodEndIso = "2026-09-30",
            action = BudgetOverrideAction.ASSIGN
        )
        val adjustment = BudgetAdjustment(
            id = "adjustment-1",
            sourceBudgetId = "dining",
            destinationBudgetId = "groceries",
            amount = 50.0,
            periodStartIso = "2026-09-01"
        )

        assertEquals(override, SyncMapper.decodeBudgetOverride(SyncMapper.encodeBudgetOverride(override)))
        assertEquals(adjustment, SyncMapper.decodeBudgetAdjustment(SyncMapper.encodeBudgetAdjustment(adjustment)))
        assertEquals("budget_override", SyncMapper.budgetOverrideMutation(override).kind)
        assertEquals("budget_adjustment", SyncMapper.budgetAdjustmentMutation(adjustment).kind)
    }
}
