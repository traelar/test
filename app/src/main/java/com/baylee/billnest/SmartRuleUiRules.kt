package com.baylee.billnest

import com.baylee.billnest.model.AppData
import com.baylee.billnest.model.RulePreviewRow
import com.baylee.billnest.model.SmartTransactionRule
import com.baylee.billnest.model.previewSmartRuleSet

fun smartRulePreviewRows(data: AppData, candidate: SmartTransactionRule): List<RulePreviewRow> =
    previewSmartRuleSet(
        transactions = data.transactions,
        rules = data.smartTransactionRules.filterNot { it.id == candidate.id } + candidate,
        profiles = data.merchantProfiles,
        data = data
    ).filter { it.winningRuleId == candidate.id }

fun smartRuleNeedsPreviewConfirmation(rows: List<RulePreviewRow>): Boolean = rows.isNotEmpty()
