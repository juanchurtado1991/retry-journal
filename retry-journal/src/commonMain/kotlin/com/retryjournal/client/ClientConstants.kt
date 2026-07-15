package com.retryjournal.client

internal object ClientConstants {
    /** Separates multiple values for the same header name — ASCII Record Separator, never valid in HTTP header values. */
    const val HEADER_MULTI_VALUE_SEPARATOR: String = "\u001e"

    /** Shared instead of allocated per request that has no-body. */
    val EMPTY_BODY: ByteArray = ByteArray(0)

    const val HEADER_SCRATCH_INITIAL_CAPACITY: Int = 8
    const val PLUGIN_ATTRIBUTE_KEY_NAME: String = "RetryJournalOfflineQueuePlugin"
    const val OFFLINE_QUEUED_MESSAGE_PREFIX: String = "Request to "
    const val OFFLINE_QUEUED_MESSAGE_SUFFIX: String = " could not be sent and was queued for later delivery"
    const val BODY_CAPTURE_FAILED_MESSAGE: String = "Failed to capture request body for offline queueing"
    const val BODY_TYPE_UNSUPPORTED_MESSAGE_PREFIX: String = "Unsupported request body type for offline queueing: "

    const val BODY_TOO_LARGE_MESSAGE: String =
        "Request body exceeds the configured maxRecordFieldSize and cannot be queued for offline delivery"

    const val PLUGIN_DISK_QUEUE_MISSING: String =
        "RetryJournalOfflineQueuePlugin requires diskQueue to be set in the configuration block: " +
                "install(RetryJournalOfflineQueuePlugin) { diskQueue = myDiskQueue }"

    const val PLUGIN_CLOSED_MESSAGE: String = "RetryJournalOfflineQueuePlugin is closed"

    const val PLUGIN_CLOSE_WHILE_REQUEST_IN_FLIGHT_MESSAGE: String =
        "RetryJournalOfflineQueuePlugin.close() called while a request is still in flight on client. " +
            "Make sure every client request has completed before closing."
}
