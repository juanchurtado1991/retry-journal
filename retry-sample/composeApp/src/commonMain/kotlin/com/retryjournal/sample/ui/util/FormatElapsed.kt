package com.retryjournal.sample.ui.util

import com.retryjournal.sample.app.AppConstants
import com.retryjournal.sample.app.AppStrings
import kotlin.time.Duration
import kotlin.time.DurationUnit

internal fun formatElapsed(elapsed: Duration): String =
    AppStrings.LOG_TIMESTAMP_PREFIX + elapsed.toString(
        DurationUnit.SECONDS,
        decimals = AppConstants.LOG_TIMESTAMP_DECIMALS,
    )
