package com.retryjournal.sample.ui.model

import kotlin.time.Duration

/** One line in the on-screen activity feed — [elapsed] is time since app launch. */
internal data class ActivityLogEntry(
    val elapsed: Duration,
    val message: String,
    val kind: LogKind,
)
