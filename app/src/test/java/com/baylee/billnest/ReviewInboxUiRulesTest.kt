package com.baylee.billnest

import com.baylee.billnest.model.ReviewType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewInboxUiRulesTest {
    @Test
    fun confidenceUsesHumanReadableBands() {
        assertEquals("High", reviewConfidenceLabel(0.90))
        assertEquals("Medium", reviewConfidenceLabel(0.65))
        assertEquals("Low", reviewConfidenceLabel(0.30))
    }

    @Test
    fun everyReviewTypeHasAnActionPlanAndRowsStayPhoneSafe() {
        ReviewType.entries.forEach { type ->
            val rows = reviewActionRows(type)
            assertTrue("$type needs actions", rows.flatten().isNotEmpty())
            assertTrue("$type action rows must be phone safe", rows.all { it.size <= 2 })
        }
    }
}
