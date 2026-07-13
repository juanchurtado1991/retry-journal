package com.ghostserializer.sync.sample.app

internal object AppConstants {
    const val QUEUE_FILE_NAME: String = "ghost-sync-queue.bin"
    const val DEAD_LETTER_FILE_NAME: String = "ghost-sync-dead-letter.bin"

    /** The "Send N JSON requests" button — small on purpose, so each request's fate (queued vs
     * delivered) is easy to follow by eye instead of just watching a big number change. */
    const val SIMPLE_SEND_COUNT: Int = 5

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

    const val DESKTOP_WINDOW_TITLE: String = "Ghost Sync — Demo (Desktop)"

    const val SERVER_HEALTH_POLL_INTERVAL_MS: Long = 2_000L
    const val ACTIVITY_LOG_MAX_ENTRIES: Int = 50
    const val LOG_TIMESTAMP_DECIMALS: Int = 1

    /** How many enqueue requests run concurrently — also wired into OkHttp's dispatcher
     * (see PlatformHttpClientEngine.*.kt) so it isn't the one silently throttling this back down. */
    const val ENQUEUE_CONCURRENCY: Int = 64

    /** Gives the embedded server's socket a moment to bind/unbind before the next health check,
     * so toggling it doesn't race a poll that started just before the toggle finished. */
    const val SERVER_TOGGLE_SETTLE_MS: Long = 150L

    /** Caps how many pending requests get their own animated chip on screen — past this, only the
     * numeric "Pending" count updates. Ten thousand chips would be neither legible nor cheap to
     * recompose; this is about showing individual requests moving through the queue, not a
     * progress bar. */
    const val MAX_VISUALIZED_QUEUE_ITEMS: Int = 20

    /** How long a chip holds its delivered/dead-lettered color before disappearing during `Sync
     * now` — only applied to chips actually on screen (at most [MAX_VISUALIZED_QUEUE_ITEMS]), so
     * a 10,000-entry flush isn't slowed down by an animation nobody's watching past the first 20. */
    const val SYNC_ANIMATION_STEP_DELAY_MS: Long = 400L
}
