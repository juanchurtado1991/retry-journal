package com.retryjournal.sample.app

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

internal actual fun platformHttpClientEngine(): HttpClientEngine =
    OkHttp.create {
        config {
            connectTimeout(
                AppConstants.CLIENT_SOCKET_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            readTimeout(
                AppConstants.CLIENT_SOCKET_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            dispatcher(
                Dispatcher().apply {
                    maxRequests = AppConstants.ENQUEUE_CONCURRENCY
                    maxRequestsPerHost = AppConstants.ENQUEUE_CONCURRENCY
                }
            )
        }
    }
