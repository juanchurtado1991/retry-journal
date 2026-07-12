package com.ghostserializer.sync.client

/**
 * Thrown by [GhostOfflineQueuePlugin] in place of the original network failure once the request
 * has been safely persisted to disk. Lets the UI show a "saved for later" state instead of a
 * generic error — the request was not lost, it is just not sent yet.
 */
class OfflineQueuedException(url: String) :
    Exception("Request to $url could not be sent and was queued for later delivery")
