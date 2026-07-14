package com.ghostserializer.sync.sample.ui.util

import com.ghostserializer.sync.sample.app.AppConstants
import com.ghostserializer.sync.sample.app.AppStrings
import kotlin.time.Duration
import kotlin.time.DurationUnit

internal fun formatElapsed(elapsed: Duration): String =
    AppStrings.LOG_TIMESTAMP_PREFIX + elapsed.toString(
        DurationUnit.SECONDS,
        decimals = AppConstants.LOG_TIMESTAMP_DECIMALS,
    )
