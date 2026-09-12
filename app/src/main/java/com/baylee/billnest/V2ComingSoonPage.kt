package com.baylee.billnest

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun V2ComingSoonPage(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "This section is part of the BillNest v2 workspace and will be filled in without replacing your working finance data.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
