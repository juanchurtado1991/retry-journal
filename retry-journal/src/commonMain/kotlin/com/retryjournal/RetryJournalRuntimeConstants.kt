package com.retryjournal

internal object RetryJournalRuntimeConstants {
    const val RUNTIME_SHUTDOWN_MESSAGE: String = "RetryJournalRuntime is shut down"
    const val RUNTIME_ALREADY_SHUTDOWN_MESSAGE: String =
        "RetryJournalRuntime.shutdown() has already been called on this instance"
}
