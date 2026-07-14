package com.ghostserializer.sync.sample.ui.model

import com.ghostserializer.sync.queue.QueueEntryId

internal data class QueueChipUiState(
    val id: QueueEntryId,
    val status: ChipStatus,
)
