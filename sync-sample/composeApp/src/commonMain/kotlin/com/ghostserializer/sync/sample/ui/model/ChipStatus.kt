package com.ghostserializer.sync.sample.ui.model

internal enum class ChipStatus {
    /** FIFO head ready for HTTP on the next flush. */
    Pending,
    /** FIFO head has a delivery journal — flush finishes local removal only. */
    AwaitingLocalRemoval,
    /** Another process holds a replay claim on the FIFO head. */
    HeadBlocked,
    Delivered,
    DeadLettered,
}
