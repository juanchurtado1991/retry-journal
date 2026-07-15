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
import com.ghostserializer.sync.sample.ui.theme.AppDimens

/**
 * Shown on mobile platforms (iOS/Android) where the demo can't start/stop the server.
 * Toggling it calls [SimulatedConnectivityController] to simulate offline mode in-process
 * so mutations queue locally and replay when you go back online.
 */
@Composable
internal fun ConnectivityToggleRow(
    isSimulatedOffline: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val dotColor = if (isSimulatedOffline) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val label = if (isSimulatedOffline) {
        AppStrings.CONNECTIVITY_OFFLINE_LABEL
    } else {
        AppStrings.CONNECTIVITY_ONLINE_LABEL
    }

    Card {
        Column(
            modifier = Modifier
                .padding(AppDimens.CARD_PADDING)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppDimens.CARD_INTERNAL_SPACING),
                ) {
                    Box(
                        modifier = Modifier
                            .size(AppDimens.STATUS_DOT_SIZE)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = !isSimulatedOffline,
                    onCheckedChange = { onToggle() },
                    enabled = enabled,
                )
            }
            Text(
                AppStrings.CONNECTIVITY_TOGGLE_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
