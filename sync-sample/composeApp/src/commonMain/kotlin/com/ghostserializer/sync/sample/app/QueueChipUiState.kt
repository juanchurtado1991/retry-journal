package com.ghostserializer.sync.sample.app

import com.ghostserializer.sync.queue.QueueEntryId

/** One request represented as a small animated chip on screen — [ChipStatus.Pending] while it
 * sits in the queue, [ChipStatus.Delivered]/[ChipStatus.DeadLettered] for the brief moment after
 * `Sync now` actually resolves it (see [com.ghostserializer.sync.engine.FlushProgress]) before it's
 * removed from the displayed list. */
internal data class QueueChipUiState(val id: QueueEntryId, val status: ChipStatus)

internal enum class ChipStatus { Pending, Delivered, DeadLettered }
