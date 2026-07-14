package com.ghostserializer.sync.queue

/** Outcome of reading a [DeliveryJournal] file — [CorruptPending] preserves recovery when CRC fails. */
internal sealed class DeliveryJournalReadResult {
    data object Absent : DeliveryJournalReadResult()

    data class Valid(val pending: PendingDelivery) : DeliveryJournalReadResult()

    /** Magic/header present but CRC or outcome invalid — skip HTTP and retry local removal. */
    data class CorruptPending(
        val sequenceId: Long,
        val outcome: String,
    ) : DeliveryJournalReadResult()
}
