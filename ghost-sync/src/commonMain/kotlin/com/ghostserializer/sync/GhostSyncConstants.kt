package com.ghostserializer.sync

internal object GhostSyncConstants {
    const val DEFAULT_DEAD_LETTER_PATH_SUFFIX: String = ".deadletter"

    const val CLOSE_WHILE_FLUSH_IN_FLIGHT_MESSAGE: String =
        "GhostSync.close() called while flush() is still in flight on this instance. " +
            "Make sure every flush() call has completed before closing — otherwise closing " +
            "replayClient can cut a request out from under a flush() that's mid-replay."
}
