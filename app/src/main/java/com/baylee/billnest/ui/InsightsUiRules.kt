package com.baylee.billnest.ui

fun insightsBudgetEmptyState(hasBudgets: Boolean): String =
    if (hasBudgets) {
        "No current budget alerts. Your active budgets are within their warning rules."
    } else {
        "No budgets set up yet. Add a budget to start receiving spending alerts."
    }
