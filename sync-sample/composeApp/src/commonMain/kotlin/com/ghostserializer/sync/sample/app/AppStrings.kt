package com.ghostserializer.sync.sample.app

internal object AppStrings {
    const val STATUS_READY: String = "Ready"
    const val SCREEN_TITLE: String = "Ghost Sync — Stress Test"
    const val PENDING_LABEL_PREFIX: String = "Pending in queue: "
    const val DEAD_LETTERED_LABEL_PREFIX: String = "Dead-lettered: "

    const val ENQUEUEING_PREFIX: String = "Enqueueing "
    const val ENQUEUEING_SUFFIX: String = " offline..."
    const val ENQUEUED_PREFIX: String = "Enqueued "
    const val ENQUEUED_SUFFIX: String = "."
    const val ENQUEUE_BUTTON_PREFIX: String = "Enqueue "
    const val ENQUEUE_BUTTON_SUFFIX: String = " offline"
    const val STRESS_TEST_BUTTON_PREFIX: String = "Stress test: "

    const val FLUSHING: String = "Flushing..."
    const val FLUSH_BUTTON: String = "Flush now"
    const val FLUSHED_RESULT_PREFIX: String = "Flushed — delivered="
    const val FLUSHED_RESULT_DEAD_LETTERED: String = " deadLettered="
    const val FLUSHED_RESULT_STOPPED_EARLY: String = " stoppedEarly="

    const val MUTATION_ID_PREFIX: String = "mutation-"
    const val MUTATION_PAYLOAD_PREFIX: String = "stress-test-payload-"

    const val SERVER_URL_SCHEME: String = "http://"
    const val SERVER_URL_PORT_SEPARATOR: String = ":"

    const val ANDROID_APP_CONTEXT_NOT_INSTALLED: String =
        "AndroidAppContext.install() must run in Application.onCreate() first."

    const val WORKER_RETRY_REASON_STOPPED_EARLY: String =
        "flush stopped early: a 5xx or network failure left work in the queue"
    const val WORKER_RETRY_REASON_THREW_PREFIX: String = "flush threw: "
    const val WORKER_SUCCESS_MESSAGE_PREFIX: String = "delivered="
    const val WORKER_SUCCESS_MESSAGE_DEAD_LETTERED: String = " deadLettered="
}
