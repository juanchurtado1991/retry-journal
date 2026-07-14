package com.ghostserializer.sync.sample.ui.components.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ghostserializer.sync.sample.app.AppStrings
import com.ghostserializer.sync.sample.ui.theme.AppDimens

@Composable
internal fun StatsRow(pending: Int, deadLettered: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.STAT_CARD_SPACING)) {
        StatCard(
            title = AppStrings.STAT_PENDING_TITLE,
            value = pending,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            title = AppStrings.STAT_DEAD_LETTERED_TITLE,
            value = deadLettered,
            modifier = Modifier.weight(1f),
        )
    }
}
