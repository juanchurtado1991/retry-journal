package com.ghostserializer.sync.sample.ui.action

import com.ghostserializer.sync.queue.QueueEntryId
import com.ghostserializer.sync.sample.app.AppConstants
import com.ghostserializer.sync.sample.app.SyncSetup
import com.ghostserializer.sync.sample.ui.model.QueueChipUiState
import com.ghostserializer.sync.sample.ui.model.ChipStatus

internal suspend fun refreshQueueSnapshot(): Pair<Int, List<QueueChipUiState>> {
    val pending = SyncSetup.diskQueue.size()
    val pendingIds = ArrayList<QueueEntryId>(AppConstants.MAX_VISUALIZED_QUEUE_ITEMS,)
    SyncSetup.diskQueue.peekIds(AppConstants.MAX_VISUALIZED_QUEUE_ITEMS, pendingIds)
    val chips = pendingIds.map { QueueChipUiState(it, ChipStatus.Pending) }
    return pending to chips
}

internal suspend fun deadLetterCount(): Int = SyncSetup.deadLetterQueue.size()
