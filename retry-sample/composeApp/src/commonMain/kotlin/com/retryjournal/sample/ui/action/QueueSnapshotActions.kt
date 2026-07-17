package com.retryjournal.sample.ui.action

import com.retryjournal.engine.QueueHeadState
import com.retryjournal.queue.QueueEntryId
import com.retryjournal.sample.app.AppConstants
import com.retryjournal.sample.app.AppStrings
import com.retryjournal.sample.app.SyncSetup
import com.retryjournal.sample.ui.model.ChipStatus
import com.retryjournal.sample.ui.model.QueueChipUiState
import com.retryjournal.sample.ui.model.QueueSnapshot

internal suspend fun refreshQueueSnapshot(): QueueSnapshot {
    val pending = SyncSetup.diskQueue.size()
    val headState = SyncSetup.syncEngine.getHeadState()
    val pendingIds = ArrayList<QueueEntryId>(AppConstants.MAX_VISUALIZED_QUEUE_ITEMS)
    SyncSetup.diskQueue.peekIds(AppConstants.MAX_VISUALIZED_QUEUE_ITEMS, pendingIds)
    val chips = pendingIds.mapIndexed { index, id ->
        QueueChipUiState(
            id = id,
            status = chipStatusForEntry(headState, id, isFifoHead = index == 0),
        )
    }
    return QueueSnapshot(
        pending = pending,
        chips = chips,
        headStateLabel = headStateLabel(headState),
    )
}

internal suspend fun deadLetterCount(): Int = SyncSetup.deadLetterQueue.size()

private fun headStateLabel(headState: QueueHeadState): String? = when (headState) {
    QueueHeadState.Empty -> null
    QueueHeadState.Blocked -> AppStrings.HEAD_STATE_BLOCKED
    is QueueHeadState.AwaitingReplay -> AppStrings.HEAD_STATE_AWAITING_REPLAY
    is QueueHeadState.AwaitingLocalRemoval -> AppStrings.HEAD_STATE_AWAITING_LOCAL_REMOVAL
}

private fun chipStatusForEntry(
    headState: QueueHeadState,
    entryId: QueueEntryId,
    isFifoHead: Boolean,
): ChipStatus {
    if (!isFifoHead) {
        return ChipStatus.Pending
    }
    return when (headState) {
        QueueHeadState.Empty -> ChipStatus.Pending
        QueueHeadState.Blocked -> ChipStatus.HeadBlocked
        is QueueHeadState.AwaitingReplay -> ChipStatus.Pending
        is QueueHeadState.AwaitingLocalRemoval ->
            if (headState.entry.id == entryId) ChipStatus.AwaitingLocalRemoval else ChipStatus.Pending
    }
}
