package com.ghostserializer.sync.sample.ui.components.server

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.ghostserializer.sync.sample.app.AppStrings
import com.ghostserializer.sync.sample.ui.model.ServerHealthStatus
import com.ghostserializer.sync.sample.ui.theme.AppDimens

@Composable
internal fun ServerStatusRow(
    status: ServerHealthStatus,
    controllable: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val (dotColor, label) = serverStatusPresentation(status)
    Card {
        Column(modifier = Modifier.padding(AppDimens.CARD_PADDING).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppDimens.CARD_INTERNAL_SPACING),
                ) {
                    StatusDot(color = dotColor)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
                if (controllable) {
                    Switch(
                        checked = status == ServerHealthStatus.Online,
                        onCheckedChange = { onToggle() },
                        enabled = enabled,
                    )
                }
            }
            if (!controllable) {
                Text(
                    AppStrings.SERVER_EXTERNAL_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(AppDimens.STATUS_DOT_SIZE)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun serverStatusPresentation(
    status: ServerHealthStatus,
): Pair<androidx.compose.ui.graphics.Color, String> = when (status) {
    ServerHealthStatus.Checking ->
        MaterialTheme.colorScheme.onSurfaceVariant to AppStrings.SERVER_CHECKING_LABEL
    ServerHealthStatus.Online ->
        MaterialTheme.colorScheme.primary to AppStrings.SERVER_ONLINE_LABEL
    ServerHealthStatus.Offline ->
        MaterialTheme.colorScheme.error to AppStrings.SERVER_OFFLINE_LABEL
}
