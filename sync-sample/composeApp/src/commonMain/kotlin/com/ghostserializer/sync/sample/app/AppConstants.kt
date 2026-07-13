package com.ghostserializer.sync.sample.app

internal object AppConstants {
    const val SERVER_HOST: String = "10.0.2.2" // Android emulator's alias for the host machine's localhost.
    const val QUEUE_FILE_NAME: String = "ghost-sync-queue.bin"
    const val DEAD_LETTER_FILE_NAME: String = "ghost-sync-dead-letter.bin"

    const val DEFAULT_MUTATION_COUNT: Int = 1_000
    const val STRESS_TEST_MUTATION_COUNT: Int = 10_000

    /** Below the chaos server's ~15s offline-timeout branch, above its ~3s latency branch. */
    const val CLIENT_SOCKET_TIMEOUT_MS: Long = 6_000L

    const val SYNC_WORKER_NAME: String = "GhostSyncWorker"
    const val SYNC_WORKER_ID: String = "ghost-sync-periodic"
    const val SYNC_INTERVAL_MS: Long = 15 * 60 * 1000L
    const val SYNC_RETRY_DELAY_MS: Long = 60_000L
    const val SYNC_MAX_RETRY_ATTEMPTS: Int = 5
}
