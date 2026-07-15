package com.retryjournal.sample.ui.action

import com.retryjournal.engine.FlushProgress
import com.retryjournal.sample.app.AppConstants
import com.retryjournal.sample.ui.model.ChipStatus
import com.retryjournal.sample.ui.model.QueueChipUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal fun CoroutineScope.animateChipOnFlushProgress(
    progress: FlushProgress,
    currentChips: List<QueueChipUiState>,
    onChipsUpdated: (List<QueueChipUiState>) -> Unit,
) {
    val (id, status) = when (progress) {
        is FlushProgress.Delivered -> progress.id to ChipStatus.Delivered
        is FlushProgress.DeadLettered -> progress.id to ChipStatus.DeadLettered
    }
    if (!currentChips.any { it.id == id }) {
        return
    }
    val updated = currentChips.map { if (it.id == id) it.copy(status = status) else it }
    onChipsUpdated(updated)
    launch {
        delay(AppConstants.SYNC_ANIMATION_STEP_DELAY_MS.milliseconds)
        onChipsUpdated(updated.filterNot { it.id == id })
    }
}
