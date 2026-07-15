package com.retryjournal.engine

internal object SyncEngineConstants {
    const val CLIENT_ERROR_STATUS_LOWER_BOUND: Int = 400
    const val CLIENT_ERROR_STATUS_UPPER_BOUND: Int = 499

    /** Separates multiple values replayed for the same header — must match [com.retryjournal.client.ClientConstants.HEADER_MULTI_VALUE_SEPARATOR]. */
    const val HEADER_MULTI_VALUE_SEPARATOR: String = "\u001e"

    /** Transient client errors retried on the next [RetryJournalEngine.flush] instead of dead-lettered. */
    val RETRY_WORTHY_CLIENT_ERROR_STATUSES: Set<Int> = setOf(408, 429)

    const val REPLAY_CLIENT_HAS_QUEUE_PLUGIN_MESSAGE: String =
        "RetryJournalEngine.flush() was passed an HttpClient with RetryJournalOfflineQueuePlugin installed. " +
            "A network failure during replay would be re-captured by the plugin and re-enqueued " +
            "while the original entry is still on the queue, duplicating it. Use a separate " +
            "HttpClient without the plugin for replay, or use RetryJournal.create(...) + " +
            "RetryJournal.flush(), which wires this correctly for you."

    const val ENGINE_CLOSED_MESSAGE: String = "RetryJournalEngine is closed"

    const val CLOSE_WHILE_REPLAY_IN_FLIGHT_MESSAGE: String =
        "RetryJournalEngine.close() called while a replay session is still in flight on this instance. " +
            "Make sure every flush() call has completed before closing."
}
