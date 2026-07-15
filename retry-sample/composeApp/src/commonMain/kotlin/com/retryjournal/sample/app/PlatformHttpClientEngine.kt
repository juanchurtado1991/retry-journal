package com.retryjournal.sample.app

import io.ktor.client.engine.HttpClientEngine

/** OkHttp on Android, Darwin on iOS — both surface a stalled chaos-server response as a genuine
 * [io.ktor.utils.io.errors.IOException] via a real socket timeout, unlike Ktor's own `HttpTimeout`
 * plugin (its `HttpRequestTimeoutException` is a `CancellationException`, not an `IOException`,
 * so `RetryJournalOfflineQueuePlugin` would never see it). */
internal expect fun platformHttpClientEngine(): HttpClientEngine
