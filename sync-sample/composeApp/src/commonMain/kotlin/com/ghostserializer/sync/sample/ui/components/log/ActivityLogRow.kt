package com.ghostserializer.sync.sample.ui.components.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ghostserializer.sync.sample.ui.model.ActivityLogEntry
import com.ghostserializer.sync.sample.ui.model.LogKind
import com.ghostserializer.sync.sample.ui.theme.AppDimens
import com.ghostserializer.sync.sample.ui.util.formatElapsed

@Composable
internal fun ActivityLogRow(entry: ActivityLogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppDimens.LOG_ROW_VERTICAL_PADDING),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.CARD_INTERNAL_SPACING),
    ) {
        Text(
            formatElapsed(entry.elapsed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = logKindColor(entry.kind),
        )
    }
}

@Composable
private fun logKindColor(kind: LogKind): androidx.compose.ui.graphics.Color = when (kind) {
    LogKind.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    LogKind.Success -> MaterialTheme.colorScheme.primary
    LogKind.Error -> MaterialTheme.colorScheme.error
}
