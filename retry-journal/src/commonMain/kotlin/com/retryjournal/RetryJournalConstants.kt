package com.retryjournal

internal object RetryJournalConstants {
    const val DEFAULT_DEAD_LETTER_PATH_SUFFIX: String = ".deadletter"

    const val CLOSE_WHILE_REPLAY_IN_FLIGHT_MESSAGE: String =
        "RetryJournal.close() called while a replay session (flush()) is still " +
            "in flight on this instance. Make sure every replay call has completed before closing — " +
            "otherwise closing replayClient can cut a request out from under it."

    const val CLOSE_WHILE_CLIENT_REQUEST_IN_FLIGHT_MESSAGE: String =
        "RetryJournal.close() called while a request is still in flight on client. Make sure every " +
            "client request has completed before closing — otherwise closing client can cut a " +
            "connectivity-failure capture out from under it."
}
