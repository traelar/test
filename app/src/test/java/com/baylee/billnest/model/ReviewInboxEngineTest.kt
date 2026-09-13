package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewInboxEngineTest {

    @Test
    fun flagsUncategorizedAndUnknownMerchantWithShaFingerprints() {
        val row = FinanceTransaction(
            id = "mystery",
            name = "MYSTERY MART 1042",
            amount = 42.50,
            dateIso = "2026-09-12",
            category = "Other",
            accountId = "checking",
            source = TransactionSource.PLAID
        )

        val items = generateReviewItems(AppData(transactions = listOf(row)))
        val uncategorized = items.single { it.type == ReviewType.UNCATEGORIZED }
        val merchant = items.single { it.type == ReviewType.UNKNOWN_MERCHANT }

        assertEquals(listOf(row.id), uncategorized.transactionIds)
        assertEquals(listOf(row.id), merchant.transactionIds)
        assertTrue(merchant.suggestedMerchantName.orEmpty().contains("Mystery", ignoreCase = true))
        assertTrue(items.all { it.fingerprint.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun fingerprintsAreStableAndChangedEvidenceCanReturnAfterResolution() {
        val tx = FinanceTransaction(
            id = "uncategorized",
            name = "Corner Store",
            amount = 32.0,
            dateIso = "2026-09-12",
            category = "Other"
        )
        val first = generateReviewItems(AppData(transactions = listOf(tx)))
            .first { it.type == ReviewType.UNCATEGORIZED }
        val sameAgain = generateReviewItems(AppData(transactions = listOf(tx)))
            .first { it.type == ReviewType.UNCATEGORIZED }

        assertEquals(first.fingerprint, sameAgain.fingerprint)

        val resolved = AppData(
            transactions = listOf(tx),
            reviewResolutions = listOf(ReviewResolution(first.fingerprint, ReviewDisposition.RESOLVED))
        )
        assertFalse(visibleReviewItems(resolved).any { it.fingerprint == first.fingerprint })

        val changed = resolved.copy(transactions = listOf(tx.copy(amount = 39.0)))
        assertTrue(visibleReviewItems(changed).any { it.type == ReviewType.UNCATEGORIZED })
        assertEquals(1, reviewAttentionCount(changed))
    }

    @Test
    fun detectsPossibleTransferWithinThreeDaysAndDuplicateWithinOneDay() {
        val checkingOut = FinanceTransaction(
            id = "checking-out",
            name = "Online transfer",
            amount = 250.0,
            dateIso = "2026-09-10",
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
        val duplicateB = duplicateA.copy(id = "dup-b", name = "COFFEE PLACE 456", dateIso = "2026-09-12")
        val data = AppData(transactions = listOf(checkingOut, savingsIn, duplicateA, duplicateB))

        val first = generateReviewItems(data)
        val reversed = generateReviewItems(data.copy(transactions = data.transactions.reversed()))

        assertEquals(first.map { it.fingerprint }.toSet(), reversed.map { it.fingerprint }.toSet())
        val transfer = first.single { it.type == ReviewType.POSSIBLE_TRANSFER }
        val duplicate = first.single { it.type == ReviewType.POTENTIAL_DUPLICATE }
        assertEquals(TransactionClassification.TRANSFER, transfer.suggestedClassification)
        assertEquals(setOf("dup-a", "dup-b"), duplicate.transactionIds.toSet())
    }

    @Test
    fun detectsPayrollIncomeAndRecurringSuggestionButHonorsPreference() {
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
        val data = AppData(transactions = recurring + payroll)
        val items = generateReviewItems(data)

        val income = items.single { it.type == ReviewType.POSSIBLE_INCOME }
        val recurringItem = items.single { it.type == ReviewType.POSSIBLE_RECURRING }
        assertEquals(listOf("payroll"), income.transactionIds)
        assertEquals(TransactionClassification.INCOME, income.suggestedClassification)
        assertEquals(setOf("stream-1", "stream-2", "stream-3"), recurringItem.transactionIds.toSet())

        val confirmed = data.copy(
            subscriptionPreferences = listOf(
                SubscriptionPreference(
                    merchantKey = subscriptionKey("Stream Co"),
                    name = "Stream Co",
                    status = SubscriptionStatus.CONFIRMED
                )
            )
        )
        assertFalse(generateReviewItems(confirmed).any { it.type == ReviewType.POSSIBLE_RECURRING })
    }

    @Test
    fun categoryConflictRequiresHistoricalMajorityAndManualOverrideWins() {
        val walmartHistory = listOf(
            FinanceTransaction(id = "w1", name = "Walmart 111", amount = 50.0, dateIso = "2026-09-01", category = "Groceries"),
            FinanceTransaction(id = "w2", name = "Walmart 222", amount = 60.0, dateIso = "2026-09-05", category = "Groceries"),
            FinanceTransaction(id = "w3", name = "Walmart 333", amount = 55.0, dateIso = "2026-09-08", category = "Groceries")
        )
        val conflict = FinanceTransaction(
            id = "w-current",
            name = "Walmart 444",
            amount = 80.0,
            dateIso = "2026-09-12",
            category = "Shopping"
        )
        val targetHistory = listOf(
            FinanceTransaction(id = "t1", name = "Target 111", amount = 40.0, dateIso = "2026-09-01", category = "Groceries"),
            FinanceTransaction(id = "t2", name = "Target 222", amount = 45.0, dateIso = "2026-09-05", category = "Groceries"),
            FinanceTransaction(id = "t3", name = "Target 333", amount = 42.0, dateIso = "2026-09-08", category = "Groceries")
        )
        val manual = FinanceTransaction(
            id = "t-current",
            name = "Target 444",
            amount = 75.0,
            dateIso = "2026-09-12",
            category = "Shopping",
            userClassificationOverride = true
        )

        val conflicts = generateReviewItems(
            AppData(transactions = walmartHistory + conflict + targetHistory + manual)
        ).filter { it.type == ReviewType.CATEGORY_CONFLICT }

        assertEquals(1, conflicts.size)
        assertEquals(listOf("w-current"), conflicts.single().transactionIds)
        assertEquals("Groceries", conflicts.single().suggestedCategory)
    }

    @Test
    fun unusualAmountRequiresThreePriorAndTwoTimesMedianPlusTwentyFive() {
        val history = listOf(52.0, 49.0, 51.0).mapIndexed { index, amount ->
            FinanceTransaction(
                id = "old-$index",
                name = "LOCAL GROCER",
                amount = amount,
                dateIso = "2026-08-${10 + index}",
                category = "Groceries",
                source = TransactionSource.PLAID
            )
        }
        val outlier = FinanceTransaction(
            id = "latest",
            name = "LOCAL GROCER",
            amount = 120.0,
            dateIso = "2026-09-12",
            category = "Groceries",
            source = TransactionSource.PLAID
        )

        val enough = generateReviewItems(AppData(transactions = history + outlier))
            .filter { it.type == ReviewType.UNUSUAL_AMOUNT }
        val notEnough = generateReviewItems(AppData(transactions = history.take(2) + outlier))
            .filter { it.type == ReviewType.UNUSUAL_AMOUNT }

        assertEquals(1, enough.size)
        assertEquals(listOf("latest"), enough.single().transactionIds)
        assertTrue(notEnough.isEmpty())
    }

    @Test
    fun flagsMissingAccountRoleAndDebtMetadata() {
        val account = Account(
            id = "needs-role",
            name = "Mystery Account",
            type = AccountType.CHECKING,
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

        val items = generateReviewItems(AppData(accounts = listOf(account), debts = listOf(debt)))

        assertNotNull(items.singleOrNull { it.type == ReviewType.ACCOUNT_METADATA_MISSING && it.accountId == account.id })
        assertNotNull(items.singleOrNull { it.type == ReviewType.DEBT_METADATA_MISSING && it.debtId == debt.id })
    }
}
