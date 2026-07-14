package com.ghostserializer.sync.sample.ui.util

import com.ghostserializer.sync.sample.app.AppStrings
import com.ghostserializer.sync.sample.app.platformServerHost
import com.ghostserializer.sync.sample.shared.SampleApiConstants

internal fun healthUrl(): String =
    AppStrings.SERVER_URL_SCHEME + platformServerHost +
        AppStrings.SERVER_URL_PORT_SEPARATOR + SampleApiConstants.DEFAULT_PORT +
        SampleApiConstants.HEALTH_PATH
