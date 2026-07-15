package com.retryjournal.sample.ui.model

internal data class QueueSnapshot(
    val pending: Int,
    val chips: List<QueueChipUiState>,
    val headStateLabel: String?,
)
