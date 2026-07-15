package com.retryjournal.sample.app

internal object AppStrings {
    const val SCREEN_TITLE: String = "RetryJournal — Demo"
    const val APP_SUBTITLE: String =
        "Turn the server off below to see requests queue up locally. Turn it back on and sync."

    const val SERVER_CHECKING_LABEL: String = "Checking…"
    const val SERVER_ONLINE_LABEL: String = "Server online"
    const val SERVER_OFFLINE_LABEL: String = "Server offline"
    const val SERVER_TURNED_ON_LOG: String = "Server turned on."
    const val SERVER_TURNED_OFF_LOG: String = "Server turned off."
    const val SERVER_EXTERNAL_HINT: String =
        "This platform can't control the server — run retry-sample:server yourself."

    const val CONNECTIVITY_ONLINE_LABEL: String = "Connectivity: Online"
    const val CONNECTIVITY_OFFLINE_LABEL: String = "Connectivity: Simulated Offline"
    const val CONNECTIVITY_TOGGLE_HINT: String =
        "Simulates losing connectivity inside the app — queued requests replay automatically when you go back online."
    const val CONNECTIVITY_TURNED_ONLINE_LOG: String = "Connectivity restored — triggering replay…"
    const val CONNECTIVITY_TURNED_OFFLINE_LOG: String = "Connectivity simulated offline — requests will queue."

    const val STAT_PENDING_TITLE: String = "Pending"
    const val STAT_DEAD_LETTERED_TITLE: String = "Dead-lettered"
    const val HEAD_STATE_AWAITING_REPLAY: String = "Head: awaiting replay"
    const val HEAD_STATE_AWAITING_LOCAL_REMOVAL: String = "Head: finishing local removal"
    const val HEAD_STATE_BLOCKED: String = "Head: blocked (another process)"

    const val SEND_BUTTON_PREFIX: String = "Send "
    const val SEND_BUTTON_SUFFIX: String = " JSON requests"
    const val SYNC_BUTTON: String = "Sync now"

    const val UPLOAD_BUTTON: String = "Upload a file"
    const val FILE_PICKER_TITLE: String = "Choose a file to upload"
    const val FILE_PICKER_UNSUPPORTED_HINT: String = "File picking isn't wired up on this platform in this demo."
    const val UPLOAD_FORM_FIELD_NAME: String = "file"
    const val UPLOAD_CONTENT_DISPOSITION_PREFIX: String = "filename=\""
    const val UPLOAD_CONTENT_DISPOSITION_SUFFIX: String = "\""
    const val LOG_UPLOADING_PREFIX: String = "Uploading "
    const val LOG_UPLOADING_SUFFIX: String = "…"
    const val LOG_UPLOAD_DELIVERED_PREFIX: String = "Delivered "
    const val LOG_UPLOAD_DELIVERED_SUFFIX: String = "."
    const val LOG_UPLOAD_QUEUED_PREFIX: String = "Queued "
    const val LOG_UPLOAD_QUEUED_SUFFIX: String = " — offline."

    const val ACTIVITY_LOG_TITLE: String = "Activity"
    const val ACTIVITY_LOG_EMPTY: String = "Nothing yet — try a button above."
    const val LOG_TIMESTAMP_PREFIX: String = "+"

    const val LOG_APP_READY: String = "Ready."
    const val LOG_SENDING_PREFIX: String = "Sending "
    const val LOG_SENDING_SUFFIX: String = " requests…"
    const val LOG_SENT_PREFIX: String = "Sent "
    const val LOG_SENT_SUFFIX: String = " requests."
    const val LOG_SYNCING: String = "Syncing…"
    const val LOG_SYNCED_RESULT_PREFIX: String = "Synced — delivered="
    const val LOG_SYNCED_RESULT_DEAD_LETTERED: String = " deadLettered="
    const val LOG_SYNCED_RESULT_STOPPED_EARLY: String = " stoppedEarly="

    const val MUTATION_ID_PREFIX: String = "mutation-"
    const val MUTATION_PAYLOAD_PREFIX: String = "demo-payload-"

    const val SERVER_URL_SCHEME: String = "http://"
    const val SERVER_URL_PORT_SEPARATOR: String = ":"
    const val SERVER_URL_TRAILING_SLASH: String = "/"

    const val ANDROID_APP_CONTEXT_NOT_INSTALLED: String =
        "AndroidAppContext.install() must run in Application.onCreate() first."
}
