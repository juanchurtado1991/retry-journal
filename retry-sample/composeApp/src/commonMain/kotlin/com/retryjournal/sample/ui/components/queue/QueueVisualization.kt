package com.retryjournal.sample.ui.components.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retryjournal.sample.ui.model.QueueChipUiState
import com.retryjournal.sample.ui.theme.AppDimens

@Composable
internal fun QueueVisualization(chips: List<QueueChipUiState>) {
    if (chips.isEmpty()) {
        return
    }
    LazyRow(
        modifier = Modifier.height(AppDimens.QUEUE_CHIP_SIZE),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.QUEUE_CHIP_SPACING),
    ) {
        items(chips, key = { it.id.sequenceId }) { chip ->
            QueueChip(chip, modifier = Modifier.animateItem())
        }
    }
}
