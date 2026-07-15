package com.retryjournal.client

/**
 * Thrown when [RetryJournalOfflineQueuePlugin] cannot read the request body bytes that would have been
 * sent over the wire. The request is **not** queued — callers must treat this as a hard failure,
 * not as an offline queue success.
 */
class BodyCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)
