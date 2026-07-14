package com.ghostserializer.sync.client

internal object ClientConstants {
    /** Separates multiple values for the same header name — ASCII Record Separator, never valid in HTTP header values. */
    const val HEADER_MULTI_VALUE_SEPARATOR: String = "\u001e"

    /** Shared instead of allocated per request that has no-body. */
    val EMPTY_BODY: ByteArray = ByteArray(0)

    const val HEADER_SCRATCH_INITIAL_CAPACITY: Int = 8
    const val PLUGIN_ATTRIBUTE_KEY_NAME: String = "GhostOfflineQueuePlugin"
    const val OFFLINE_QUEUED_MESSAGE_PREFIX: String = "Request to "
    const val OFFLINE_QUEUED_MESSAGE_SUFFIX: String = " could not be sent and was queued for later delivery"
    const val BODY_CAPTURE_FAILED_MESSAGE: String = "Failed to capture request body for offline queueing"
    const val BODY_TYPE_UNSUPPORTED_MESSAGE_PREFIX: String = "Unsupported request body type for offline queueing: "
    const val PLUGIN_DISK_QUEUE_MISSING: String =
        "GhostOfflineQueuePlugin requires diskQueue to be set in the configuration block: " +
                "install(GhostOfflineQueuePlugin) { diskQueue = myDiskQueue }"
}
