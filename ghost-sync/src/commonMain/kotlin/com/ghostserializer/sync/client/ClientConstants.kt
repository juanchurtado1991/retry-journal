package com.ghostserializer.sync.client

internal object ClientConstants {
    const val PLUGIN_ATTRIBUTE_KEY_NAME: String = "GhostOfflineQueuePlugin"
    const val OFFLINE_QUEUED_MESSAGE_PREFIX: String = "Request to "
    const val OFFLINE_QUEUED_MESSAGE_SUFFIX: String = " could not be sent and was queued for later delivery"

    /** Shared instead of allocated per request that has no body. */
    val EMPTY_BODY: ByteArray = ByteArray(0)
}
