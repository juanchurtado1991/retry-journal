package com.ghostserializer.sync.sample.app

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

internal actual fun platformHttpClientEngine(): HttpClientEngine =
    OkHttp.create {
        config {
            connectTimeout(AppConstants.CLIENT_SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            readTimeout(AppConstants.CLIENT_SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }
