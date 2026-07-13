package com.ghostserializer.sync.sample.app

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

private const val MILLIS_PER_SECOND: Double = 1000.0

internal actual fun platformHttpClientEngine(): HttpClientEngine =
    Darwin.create {
        configureRequest {
            timeoutInterval = AppConstants.CLIENT_SOCKET_TIMEOUT_MS / MILLIS_PER_SECOND
        }
    }
