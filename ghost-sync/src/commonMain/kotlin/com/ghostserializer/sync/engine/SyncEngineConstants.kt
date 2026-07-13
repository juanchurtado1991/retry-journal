package com.ghostserializer.sync.engine

internal object SyncEngineConstants {
    const val CLIENT_ERROR_STATUS_LOWER_BOUND: Int = 400
    const val CLIENT_ERROR_STATUS_UPPER_BOUND: Int = 499

    /** Separates multiple values replayed for the same header — must match [com.ghostserializer.sync.client.ClientConstants.HEADER_MULTI_VALUE_SEPARATOR]. */
    const val HEADER_MULTI_VALUE_SEPARATOR: String = "\u001e"

    /** Transient client errors retried on the next [GhostSyncEngine.flush] instead of dead-lettered. */
    val RETRY_WORTHY_CLIENT_ERROR_STATUSES: Set<Int> = setOf(408, 429)
}
