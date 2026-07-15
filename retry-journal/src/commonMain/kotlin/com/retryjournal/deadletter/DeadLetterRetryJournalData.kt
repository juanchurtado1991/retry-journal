package com.retryjournal.deadletter

import com.retryjournal.queue.FrozenHttpHeaders

/** Payload recovered from a pending [DeadLetterQueue.retry] journal file. */
internal class DeadLetterRetryJournalData(
    val method: String,
    val url: String,
    val headers: FrozenHttpHeaders,
    val body: ByteArray,
)
