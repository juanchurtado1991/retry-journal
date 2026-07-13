package com.ghostserializer.sync.sample.app

internal object AppStrings {
    const val SCREEN_TITLE: String = "Ghost Sync — Stress Test"
    const val APP_SUBTITLE: String =
        "Offline-first HTTP sync, no database. Stop the server, enqueue requests, start it again, and watch them flush below."

    const val SERVER_CHECKING_MESSAGE: String = "Checking server…"
    const val SERVER_ONLINE_MESSAGE: String = "Server is reachable — requests will succeed live, not queue offline."
    const val SERVER_OFFLINE_MESSAGE: String = "Server is unreachable — perfect for testing offline queueing right now."

    const val STAT_PENDING_TITLE: String = "Pending in queue"
    const val STAT_DEAD_LETTERED_TITLE: String = "Dead-lettered"

    const val STEP1_TITLE: String = "1. Enqueue while offline"
    const val STEP1_DESCRIPTION: String =
        "Stop the server (or disconnect your network), then tap a button below. Every request " +
            "fails and lands safely in the on-disk queue instead of being lost."
    const val ENQUEUE_BUTTON_PREFIX: String = "Enqueue "
    const val ENQUEUE_BUTTON_SUFFIX: String = " offline"
    const val STRESS_TEST_BUTTON_PREFIX: String = "Stress test: "

    const val STEP2_TITLE: String = "2. Sync when back online"
    const val STEP2_DESCRIPTION: String =
        "Start the server again, then flush the queue. Every pending request is retried in " +
            "order; successes are removed, 4xx failures move to the dead-letter list."
    const val FLUSH_BUTTON: String = "Flush now"

    const val STEP3_TITLE: String = "Bonus: Ktorfit"
    const val STEP3_DESCRIPTION: String =
        "The same offline-queueing behavior, but through a Ktorfit-generated API call instead " +
            "of a hand-written HttpClient.post() — proves the plugin works no matter how the " +
            "request was built."
    const val KTORFIT_DEMO_BUTTON: String = "Ktorfit: enqueue 1"
    const val KTORFIT_DEMO_ID_PREFIX: String = "ktorfit-mutation-"
    const val KTORFIT_DEMO_PAYLOAD: String = "via Ktorfit"

    const val ACTIVITY_LOG_TITLE: String = "Activity"
    const val ACTIVITY_LOG_EMPTY: String = "Nothing yet — try a button above."
    const val LOG_TIMESTAMP_PREFIX: String = "+"

    const val LOG_APP_READY: String = "Ready."
    const val LOG_ENQUEUEING_PREFIX: String = "Enqueueing "
    const val LOG_ENQUEUEING_SUFFIX: String = " requests…"
    const val LOG_ENQUEUED_PREFIX: String = "Enqueued "
    const val LOG_ENQUEUED_SUFFIX: String = " requests."
    const val LOG_FLUSHING: String = "Flushing queue…"
    const val LOG_FLUSHED_RESULT_PREFIX: String = "Flushed — delivered="
    const val LOG_FLUSHED_RESULT_DEAD_LETTERED: String = " deadLettered="
    const val LOG_FLUSHED_RESULT_STOPPED_EARLY: String = " stoppedEarly="
    const val LOG_KTORFIT_SENT_PREFIX: String = "Ktorfit: sent mutation #"

    const val MUTATION_ID_PREFIX: String = "mutation-"
    const val MUTATION_PAYLOAD_PREFIX: String = "stress-test-payload-"

    const val SERVER_URL_SCHEME: String = "http://"
    const val SERVER_URL_PORT_SEPARATOR: String = ":"
    const val SERVER_URL_TRAILING_SLASH: String = "/"

    const val ANDROID_APP_CONTEXT_NOT_INSTALLED: String =
        "AndroidAppContext.install() must run in Application.onCreate() first."

    const val WORKER_RETRY_REASON_STOPPED_EARLY: String =
        "flush stopped early: a 5xx or network failure left work in the queue"
    const val WORKER_RETRY_REASON_THREW_PREFIX: String = "flush threw: "
    const val WORKER_SUCCESS_MESSAGE_PREFIX: String = "delivered="
    const val WORKER_SUCCESS_MESSAGE_DEAD_LETTERED: String = " deadLettered="
}
