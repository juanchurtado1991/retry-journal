package com.retryjournal.client

/**
 * Thrown by [RetryJournalOfflineQueuePlugin] in place of the original network failure once the request
 * has been safely persisted to disk. Lets the UI show a "saved for later" state instead of a
 * generic error — the request was not lost, it is just not sent yet.
 */
class OfflineQueuedException(url: String) :
    Exception(ClientConstants.OFFLINE_QUEUED_MESSAGE_PREFIX + url + ClientConstants.OFFLINE_QUEUED_MESSAGE_SUFFIX)
