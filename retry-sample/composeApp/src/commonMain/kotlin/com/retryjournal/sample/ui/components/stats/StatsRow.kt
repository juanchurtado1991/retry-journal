package com.retryjournal.sample.ui.components.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retryjournal.sample.app.AppStrings
import com.retryjournal.sample.ui.theme.AppDimens

@Composable
internal fun StatsRow(
    pending: Int,
    deadLettered: Int,
    headStateLabel: String? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.STAT_CARD_SPACING)) {
        StatCard(
            title = AppStrings.STAT_PENDING_TITLE,
            value = pending,
            subtitle = headStateLabel,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            title = AppStrings.STAT_DEAD_LETTERED_TITLE,
            value = deadLettered,
            modifier = Modifier.weight(1f),
        )
    }
}
