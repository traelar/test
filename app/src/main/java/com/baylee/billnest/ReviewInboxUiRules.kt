package com.baylee.billnest

import com.baylee.billnest.model.ReviewType

internal enum class ReviewUiAction {
    CATEGORIZE,
    MERCHANT,
    CONFIRM_TRANSFER,
    MARK_INCOME,
    TRACK_RECURRING,
    EDIT,
    DELETE_DUPLICATE,
    USE_CATEGORY,
    MAKE_RULE,
    KEEP_CURRENT,
    LOOKS_RIGHT,
    EDIT_ACCOUNT,
    EDIT_DEBT,
    DISMISS
}

fun reviewConfidenceLabel(confidence: Double): String = when {
    confidence >= 0.80 -> "High"
    confidence >= 0.50 -> "Medium"
    else -> "Low"
}

internal fun reviewActionRows(type: ReviewType): List<List<ReviewUiAction>> = when (type) {
    ReviewType.UNCATEGORIZED -> listOf(
        listOf(ReviewUiAction.CATEGORIZE, ReviewUiAction.EDIT),
        listOf(ReviewUiAction.DISMISS)
    )
    ReviewType.UNKNOWN_MERCHANT -> listOf(
        listOf(ReviewUiAction.MERCHANT, ReviewUiAction.DISMISS)
    )
    ReviewType.POSSIBLE_TRANSFER -> listOf(
        listOf(ReviewUiAction.CONFIRM_TRANSFER, ReviewUiAction.EDIT),
        listOf(ReviewUiAction.DISMISS)
    )
    ReviewType.POSSIBLE_INCOME -> listOf(
        listOf(ReviewUiAction.MARK_INCOME, ReviewUiAction.EDIT),
        listOf(ReviewUiAction.DISMISS)
    )
    ReviewType.POSSIBLE_RECURRING -> listOf(
        listOf(ReviewUiAction.TRACK_RECURRING, ReviewUiAction.DISMISS)
    )
    ReviewType.POTENTIAL_DUPLICATE -> listOf(
        listOf(ReviewUiAction.EDIT, ReviewUiAction.DELETE_DUPLICATE),
        listOf(ReviewUiAction.DISMISS)
    )
    ReviewType.CATEGORY_CONFLICT -> listOf(
        listOf(ReviewUiAction.USE_CATEGORY, ReviewUiAction.MAKE_RULE),
        listOf(ReviewUiAction.KEEP_CURRENT)
    )
    ReviewType.UNUSUAL_AMOUNT -> listOf(
        listOf(ReviewUiAction.EDIT, ReviewUiAction.LOOKS_RIGHT)
    )
    ReviewType.ACCOUNT_METADATA_MISSING -> listOf(
        listOf(ReviewUiAction.EDIT_ACCOUNT, ReviewUiAction.DISMISS)
    )
    ReviewType.DEBT_METADATA_MISSING -> listOf(
        listOf(ReviewUiAction.EDIT_DEBT, ReviewUiAction.DISMISS)
    )
}
