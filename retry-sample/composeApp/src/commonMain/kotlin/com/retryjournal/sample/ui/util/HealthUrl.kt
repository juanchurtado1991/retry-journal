package com.retryjournal.sample.ui.util

import com.retryjournal.sample.app.AppStrings
import com.retryjournal.sample.app.platformServerHost
import com.retryjournal.sample.shared.SampleApiConstants

internal fun healthUrl(): String =
    AppStrings.SERVER_URL_SCHEME + platformServerHost +
        AppStrings.SERVER_URL_PORT_SEPARATOR + SampleApiConstants.DEFAULT_PORT +
        SampleApiConstants.HEALTH_PATH
