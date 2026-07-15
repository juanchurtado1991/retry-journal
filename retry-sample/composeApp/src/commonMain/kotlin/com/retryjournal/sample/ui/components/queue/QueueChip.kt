package com.retryjournal.sample.ui.components.queue

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.retryjournal.sample.ui.model.ChipStatus
import com.retryjournal.sample.ui.model.QueueChipUiState
import com.retryjournal.sample.ui.theme.AppDimens

@Composable
internal fun QueueChip(
    chip: QueueChipUiState,
    modifier: Modifier = Modifier,
) {
    val targetColor = when (chip.status) {
        ChipStatus.Pending -> MaterialTheme.colorScheme.surfaceVariant
        ChipStatus.AwaitingLocalRemoval -> MaterialTheme.colorScheme.primaryContainer
        ChipStatus.HeadBlocked -> MaterialTheme.colorScheme.tertiary
        ChipStatus.Delivered -> MaterialTheme.colorScheme.primary
        ChipStatus.DeadLettered -> MaterialTheme.colorScheme.error
    }
    val color by animateColorAsState(targetColor)
    Box(
        modifier = modifier
            .size(AppDimens.QUEUE_CHIP_SIZE)
            .clip(CircleShape)
            .background(color),
    )
}
