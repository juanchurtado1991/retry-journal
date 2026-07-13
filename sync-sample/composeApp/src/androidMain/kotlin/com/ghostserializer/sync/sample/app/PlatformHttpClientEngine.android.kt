package com.ghostserializer.sync.sample.app

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
            // OkHttp's own default (5 requests per host) would otherwise be the real cap on
            // enqueueMutations()'s concurrency, regardless of its own Semaphore permit count.
            dispatcher(
                Dispatcher().apply {
                    maxRequests = AppConstants.ENQUEUE_CONCURRENCY
                    maxRequestsPerHost = AppConstants.ENQUEUE_CONCURRENCY
                }
            )
        }
    }
