package com.ghostserializer.sync.deadletter

import com.ghostserializer.sync.queue.FrozenHttpHeaders

/** Payload recovered from a pending [DeadLetterQueue.retry] journal file. */
internal class RetryJournalData(
    val method: String,
    val url: String,
    val headers: FrozenHttpHeaders,
    val body: ByteArray,
)
