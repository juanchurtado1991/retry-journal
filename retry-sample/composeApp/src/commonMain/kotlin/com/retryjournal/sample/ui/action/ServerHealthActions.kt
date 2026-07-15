package com.retryjournal.sample.ui.action

import com.retryjournal.sample.app.SyncSetup
import com.retryjournal.sample.ui.model.ServerHealthStatus
import com.retryjournal.sample.ui.util.healthUrl
import io.ktor.client.request.get
import io.ktor.http.isSuccess

internal suspend fun probeServerHealth(): ServerHealthStatus {
    val online = runCatching { SyncSetup.replayClient.get(healthUrl()) }
        .fold(
            onSuccess = { it.status.isSuccess() },
            onFailure = { false },
        )
    SyncSetup.reportConnectivity(online)
    return if (online) ServerHealthStatus.Online else ServerHealthStatus.Offline
}

