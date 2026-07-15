package com.retryjournal.queue

/** Outcome of reading a per-sequence [DeliveryJournal] file. */
internal sealed class DeliveryJournalReadResult {
    data object Absent : DeliveryJournalReadResult()

    data class Valid(val outcome: String) : DeliveryJournalReadResult()

    /** CRC invalid — skip HTTP and retry local removal for [CorruptPending.sequenceId]. */
    data class CorruptPending(
        val sequenceId: Long,
        val outcome: String,
    ) : DeliveryJournalReadResult()
}
