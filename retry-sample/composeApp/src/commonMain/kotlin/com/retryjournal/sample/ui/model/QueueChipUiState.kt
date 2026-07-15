package com.retryjournal.sample.ui.model

import com.retryjournal.queue.QueueEntryId

internal data class QueueChipUiState(
    val id: QueueEntryId,
    val status: ChipStatus,
)
