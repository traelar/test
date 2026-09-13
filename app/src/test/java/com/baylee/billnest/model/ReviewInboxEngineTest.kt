package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReviewInboxEngineTest {
    private val today = LocalDate.of(2026, 9, 13)

    @Test
    fun flagsUncategorizedAndUnknownMerchantTransactions() {
        val row = FinanceTransaction(
            id = "mystery",
            name = "MYSTERY MART 1042",
            amount = 42.50,
            dateIso = "2026-09-12",
            category = "Other",
            accountId = "checking",
            source = TransactionSource.PLAID
        )
        val items = generateReviewInbox(AppData(transactions = listOf(row)), referenceDate = today)

        val uncategorized = items.single { it.type == ReviewType.UNCATEGORIZED }
        val merchant = items.single { it.type == ReviewType.UNKNOWN_MERCHANT }
        assertEquals(listOf(row.id), uncategorized.transactionIds)
        assertEquals(listOf(row.id), merchant.transactionIds)
        assertTrue(merchant.suggestedMerchantName.orEmpty().contains("Mystery", ignoreCase = true))
        assertTrue(items.all { it.fingerprint.isNotBlank() })
    }

    @Test
    fun transferAndDuplicateFingerprintsAreStableAndResolvedItemsStayHidden() {
        val checkingOut = FinanceTransaction(
            id = "checking-out",
            name = "Online transfer",
            amount = 250.0,
            dateIso = "2026-09-12",
            accountId = "checking",
            category = "Other",
            source = TransactionSource.PLAID
        )
        val savingsIn = FinanceTransaction(
            id = "savings-in",
            name = "Online transfer",
            amount = -250.0,
            dateIso = "2026-09-13",
            accountId = "savings",
            category = "Other",
            source = TransactionSource.PLAID
        )
        val duplicateA = FinanceTransaction(
            id = "dup-a",
            name = "Coffee Place #123",
            amount = 8.25,
            dateIso = "2026-09-11",
            accountId = "checking",
            category = "Dining",
            source = TransactionSource.PLAID
        )
        val duplicateB = duplicateA.copy(id = "dup-b", name = "COFFEE PLACE 456")
        val data = AppData(transactions = listOf(checkingOut, savingsIn, duplicateA, duplicateB))

        val first = generateReviewInbox(data, referenceDate = today)
        val reversed = generateReviewInbox(data.copy(transactions = data.transactions.reversed()), referenceDate = today)
        val firstFingerprints = first.map { it.fingerprint }.toSet()
        val reversedFingerprints = reversed.map { it.fingerprint }.toSet()

        assertEquals(firstFingerprints, reversedFingerprints)
        val transfer = first.single { it.type == ReviewType.POSSIBLE_TRANSFER }
        val duplicate = first.single { it.type == ReviewType.POTENTIAL_DUPLICATE }
        assertEquals(TransactionClassification.TRANSFER, transfer.suggestedClassification)
        assertEquals(setOf("dup-a", "dup-b"), duplicate.transactionIds.toSet())

        val resolved = ReviewResolution(transfer.fingerprint, ReviewDisposition.RESOLVED)
        val afterResolve = generateReviewInbox(data, resolutions = listOf(resolved), referenceDate = today)
        assertFalse(afterResolve.any { it.fingerprint == transfer.fingerprint })
        assertTrue(afterResolve.any { it.fingerprint == duplicate.fingerprint })
    }

    @Test
    fun detectsPayrollIncomeAndRecurringMerchant() {
        val payroll = FinanceTransaction(
            id = "payroll",
            name = "ACME DIRECT DEPOSIT PAYROLL",
            amount = -1800.0,
            dateIso = "2026-09-12",
            accountId = "checking",
            category = "Other",
            source = TransactionSource.PLAID
        )
        val recurring = listOf(
            FinanceTransaction(id = "stream-1", name = "Stream Co", amount = 15.99, dateIso = "2026-07-10", category = "Subscriptions", source = TransactionSource.PLAID),
            FinanceTransaction(id = "stream-2", name = "Stream Co", amount = 15.99, dateIso = "2026-08-10", category = "Subscriptions", source = TransactionSource.PLAID),
            FinanceTransaction(id = "stream-3", name = "Stream Co", amount = 15.99, dateIso = "2026-09-10", category = "Subscriptions", source = TransactionSource.PLAID)
        )
        val items = generateReviewInbox(AppData(transactions = recurring + payroll), referenceDate = today)

        val income = items.single { it.type == ReviewType.POSSIBLE_INCOME }
        val recurringItem = items.single { it.type == ReviewType.POSSIBLE_RECURRING }
        assertEquals(listOf("payroll"), income.transactionIds)
        assertEquals(TransactionClassification.INCOME, income.suggestedClassification)
        assertEquals(setOf("stream-1", "stream-2", "stream-3"), recurringItem.transactionIds.toSet())
    }

    @Test
    fun categoryConflictUsesMerchantProfileButNeverOverridesManualClassification() {
        val profile = MerchantProfile(
            id = "walmart",
            displayName = "Walmart",
            aliases = listOf("Walmart Supercenter"),
            preferredCategory = "Groceries"
        )
        val automatic = FinanceTransaction(
            id = "automatic",
            name = "WALMART SUPERCENTER 4421",
            amount = 90.0,
            dateIso = "2026-09-12",
            category = "Shopping",
            merchantProfileId = profile.id,
            source = TransactionSource.PLAID
        )
        val manual = automatic.copy(
            id = "manual",
            userClassificationOverride = true
        )

        val items = generateReviewInbox(
            AppData(transactions = listOf(automatic, manual)),
            merchantProfiles = listOf(profile),
            referenceDate = today
        ).filter { it.type == ReviewType.CATEGORY_CONFLICT }

        assertEquals(1, items.size)
        assertEquals(listOf("automatic"), items.single().transactionIds)
        assertEquals("Groceries", items.single().suggestedCategory)
    }

    @Test
    fun unusualAmountRequiresStableHistoryBeforeFlagging() {
        val profile = MerchantProfile(id = "grocer", displayName = "Local Grocer", aliases = listOf("LOCAL GROCER"))
        val stableHistory = listOf(52.0, 49.0, 51.0, 50.0).mapIndexed { index, amount ->
            FinanceTransaction(
                id = "old-$index",
                name = "LOCAL GROCER",
                amount = amount,
                dateIso = "2026-08-${10 + index}",
                category = "Groceries",
                merchantProfileId = profile.id,
                source = TransactionSource.PLAID
            )
        }
        val outlier = FinanceTransaction(
            id = "latest",
            name = "LOCAL GROCER",
            amount = 180.0,
            dateIso = "2026-09-12",
            category = "Groceries",
            merchantProfileId = profile.id,
            source = TransactionSource.PLAID
        )

        val enough = generateReviewInbox(
            AppData(transactions = stableHistory + outlier),
            merchantProfiles = listOf(profile),
            referenceDate = today
        ).filter { it.type == ReviewType.UNUSUAL_AMOUNT }
        val notEnough = generateReviewInbox(
            AppData(transactions = stableHistory.take(2) + outlier),
            merchantProfiles = listOf(profile),
            referenceDate = today
        ).filter { it.type == ReviewType.UNUSUAL_AMOUNT }

        assertEquals(1, enough.size)
        assertEquals(listOf("latest"), enough.single().transactionIds)
        assertTrue(notEnough.isEmpty())
    }

    @Test
    fun flagsMissingAccountAndDebtMetadata() {
        val account = Account(
            id = "needs-role",
            name = "Mystery Account",
            type = AccountType.OTHER,
            role = AccountRole.OTHER,
            balance = 100.0
        )
        val debt = Debt(
            id = "needs-debt-details",
            name = "Credit Card",
            type = DebtType.CREDIT_CARD,
            balance = 900.0,
            apr = 0.0,
            minimumPayment = 0.0,
            creditLimit = 0.0
        )

        val items = generateReviewInbox(AppData(accounts = listOf(account), debts = listOf(debt)), referenceDate = today)

        assertNotNull(items.singleOrNull { it.type == ReviewType.ACCOUNT_METADATA_MISSING && it.accountId == account.id })
        assertNotNull(items.singleOrNull { it.type == ReviewType.DEBT_METADATA_MISSING && it.debtId == debt.id })
    }
}
