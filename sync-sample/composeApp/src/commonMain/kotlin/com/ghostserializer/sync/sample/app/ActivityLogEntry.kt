package com.ghostserializer.sync.sample.app

import kotlin.time.Duration

/** One line in the on-screen activity feed — [elapsed] is time since the app launched, not a
 * wall-clock timestamp, so the demo doesn't need a date/time-formatting dependency. */
internal data class ActivityLogEntry(
    val elapsed: Duration,
    val message: String,
    val kind: LogKind,
)

internal enum class LogKind { Info, Success, Error }
