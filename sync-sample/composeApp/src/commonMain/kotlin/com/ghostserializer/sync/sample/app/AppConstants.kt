package com.ghostserializer.sync.sample.app

internal object AppConstants {
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

    /** Must match the identifier registered with BGTaskScheduler in iosApp's AppDelegate and
     * listed under BGTaskSchedulerPermittedIdentifiers in Info.plist — see sync-sample/iosApp/. */
    const val IOS_BACKGROUND_TASK_ID: String = "ghost_sync_task"

    /** A per-user, app-private directory under the desktop user's home — unlike Android's
     * `filesDir` or iOS's Documents directory, nothing guarantees this exists on first run. */
    const val DESKTOP_DATA_DIRECTORY_NAME: String = ".ghost-sync-sample"

    const val DESKTOP_WINDOW_TITLE: String = "Ghost Sync — Stress Test (Desktop)"

    const val SERVER_HEALTH_POLL_INTERVAL_MS: Long = 2_000L
    const val ACTIVITY_LOG_MAX_ENTRIES: Int = 50
    const val LOG_TIMESTAMP_DECIMALS: Int = 1
}
