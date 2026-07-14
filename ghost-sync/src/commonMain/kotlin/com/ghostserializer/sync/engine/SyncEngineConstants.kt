package com.ghostserializer.sync.engine

internal object SyncEngineConstants {
    const val CLIENT_ERROR_STATUS_LOWER_BOUND: Int = 400
    const val CLIENT_ERROR_STATUS_UPPER_BOUND: Int = 499

    /** Separates multiple values replayed for the same header — must match [com.ghostserializer.sync.client.ClientConstants.HEADER_MULTI_VALUE_SEPARATOR]. */
    const val HEADER_MULTI_VALUE_SEPARATOR: String = "\u001e"

    /** Transient client errors retried on the next [GhostSyncEngine.flush] instead of dead-lettered. */
    val RETRY_WORTHY_CLIENT_ERROR_STATUSES: Set<Int> = setOf(408, 429)

    const val REPLAY_CLIENT_HAS_QUEUE_PLUGIN_MESSAGE: String =
        "GhostSyncEngine.flush() was passed an HttpClient with GhostOfflineQueuePlugin installed. " +
            "A network failure during replay would be re-captured by the plugin and re-enqueued " +
            "while the original entry is still on the queue, duplicating it. Use a separate " +
            "HttpClient without the plugin for replay, or use GhostSync.create(...) + " +
            "GhostSync.flush(), which wires this correctly for you."

    const val ENGINE_CLOSED_MESSAGE: String = "GhostSyncEngine is closed"

    const val CLOSE_WHILE_REPLAY_IN_FLIGHT_MESSAGE: String =
        "GhostSyncEngine.close() called while a replay session is still in flight on this instance. " +
            "Make sure every flush()/getStatus()/getEntryAndStatus() call has completed before closing."
}
