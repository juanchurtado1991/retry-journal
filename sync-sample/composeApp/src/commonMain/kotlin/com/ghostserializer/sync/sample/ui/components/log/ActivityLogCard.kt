package com.ghostserializer.sync.sample.ui.components.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.ghostserializer.sync.sample.app.AppStrings
import com.ghostserializer.sync.sample.ui.model.ActivityLogEntry
import com.ghostserializer.sync.sample.ui.theme.AppDimens

@Composable
internal fun ActivityLogCard(entries: List<ActivityLogEntry>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AppDimens.CARD_PADDING).fillMaxWidth()) {
            Text(
                AppStrings.ACTIVITY_LOG_TITLE,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(AppDimens.CARD_INTERNAL_SPACING))
            if (entries.isEmpty()) {
                ActivityLogEmptyHint()
            } else {
                LazyColumn(
                    modifier = Modifier.height(AppDimens.ACTIVITY_LOG_HEIGHT).fillMaxWidth(),
                ) {
                    items(entries) { entry -> ActivityLogRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun ActivityLogEmptyHint() {
    Text(
        AppStrings.ACTIVITY_LOG_EMPTY,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
