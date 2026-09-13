package com.baylee.billnest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baylee.billnest.model.AppData
import com.baylee.billnest.model.reviewAttentionCount

/** Adds the Stage A attention entry point without changing DashboardV4 cash-flow math. */
@Composable
fun DashboardV5(
    data: AppData,
    modifier: Modifier = Modifier,
    onOpenReviewInbox: (() -> Unit)? = null
) {
    val reviewCount = remember(data) { reviewAttentionCount(data) }
    Column(modifier.fillMaxSize()) {
        if (reviewCount > 0 && onOpenReviewInbox != null) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Needs review", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "$reviewCount item${if (reviewCount == 1) "" else "s"} need a quick check.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onOpenReviewInbox) { Text("Review") }
                }
            }
        }
        DashboardV4(data = data, modifier = Modifier.weight(1f))
    }
}
