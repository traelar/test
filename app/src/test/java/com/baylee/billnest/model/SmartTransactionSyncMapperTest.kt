package com.baylee.billnest.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartTransactionSyncMapperTest {
    @Test
    fun stageARecordsRoundTripAndUseExpectedKinds() {
        val merchant = MerchantProfile(
            id = "m1",
            displayName = "Walmart",
            aliases = listOf("WM SUPERCENTER"),
            preferredCategory = "Groceries",
            confirmation = MerchantConfirmation.USER_CONFIRMED,
            lastSeenEpochMs = 100L,
            updatedAtEpochMs = 200L
        )
        val rule = SmartTransactionRule(
            id = "r1",
            name = "Walmart groceries",
            priority = 10,
            match = SmartRuleMatch(merchantProfileId = "m1", minAmount = 10.0),
            action = SmartRuleAction(category = "Groceries"),
            updatedAtEpochMs = 300L
        )
        val resolution = ReviewResolution(
            fingerprint = "fingerprint-1",
            disposition = ReviewDisposition.DISMISSED,
            resolvedAtEpochMs = 400L
        )

        assertEquals(merchant, SyncMapper.decodeMerchantProfile(SyncMapper.encodeMerchantProfile(merchant)))
        assertEquals(rule, SyncMapper.decodeSmartTransactionRule(SyncMapper.encodeSmartTransactionRule(rule)))
        assertEquals(resolution, SyncMapper.decodeReviewResolution(SyncMapper.encodeReviewResolution(resolution)))

        val merchantDraft = SyncMapper.merchantProfileMutation(merchant)
        val ruleDraft = SyncMapper.smartTransactionRuleMutation(rule)
        val resolutionDraft = SyncMapper.reviewResolutionMutation(resolution)

        assertEquals("merchant_profile", merchantDraft.kind)
        assertEquals(merchant.id, merchantDraft.recordId)
        assertEquals("smart_transaction_rule", ruleDraft.kind)
        assertEquals(rule.id, ruleDraft.recordId)
        assertEquals("review_resolution", resolutionDraft.kind)
        assertEquals(resolution.fingerprint, resolutionDraft.recordId)
    }
}
