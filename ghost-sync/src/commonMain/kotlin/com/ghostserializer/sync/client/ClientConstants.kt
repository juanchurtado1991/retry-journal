package com.ghostserializer.sync.client

internal object ClientConstants {
    const val PLUGIN_ATTRIBUTE_KEY_NAME: String = "GhostOfflineQueuePlugin"
    const val OFFLINE_QUEUED_MESSAGE_PREFIX: String = "Request to "
    const val OFFLINE_QUEUED_MESSAGE_SUFFIX: String = " could not be sent and was queued for later delivery"

    /** Shared instead of allocated per request that has no-body. */
    val EMPTY_BODY: ByteArray = ByteArray(0)

    /** Room for the Content-Type entry this plugin may add on top of the builder's own headers,
     * sized in up front instead of letting the map grow and rehash once it's already full. */
    const val HEADER_MAP_SLACK: Int = 1
}
