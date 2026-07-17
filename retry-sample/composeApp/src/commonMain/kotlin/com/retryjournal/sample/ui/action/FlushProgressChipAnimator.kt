package com.retryjournal.sample.ui.action

import com.retryjournal.engine.FlushProgress
import com.retryjournal.sample.app.AppConstants
import com.retryjournal.sample.ui.model.ChipStatus
import com.retryjournal.sample.ui.model.QueueChipUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * [updateChips] applies a transform against whatever the chip list *currently* is when it runs,
 * rather than taking a fixed replacement list — a multi-item flush fires several
 * [FlushProgress] callbacks within milliseconds of each other (`RetryJournalEngine.flush` buffers
 * them and replays back-to-back after the network work finishes), so this call's own delayed
 * removal below must not clobber another chip's status change that happened during its 400ms
 * delay with a stale snapshot from before that change existed.
 */
internal fun CoroutineScope.animateChipOnFlushProgress(
    progress: FlushProgress,
    currentChips: List<QueueChipUiState>,
    updateChips: ((List<QueueChipUiState>) -> List<QueueChipUiState>) -> Unit,
) {
    val (id, status) = when (progress) {
        is FlushProgress.Delivered -> progress.id to ChipStatus.Delivered
        is FlushProgress.DeadLettered -> progress.id to ChipStatus.DeadLettered
    }
    if (!currentChips.any { it.id == id }) {
        return
    }
    updateChips { chips -> chips.map { if (it.id == id) it.copy(status = status) else it } }
    launch {
        delay(AppConstants.SYNC_ANIMATION_STEP_DELAY_MS.milliseconds)
        updateChips { chips -> chips.filterNot { it.id == id } }
    }
}
