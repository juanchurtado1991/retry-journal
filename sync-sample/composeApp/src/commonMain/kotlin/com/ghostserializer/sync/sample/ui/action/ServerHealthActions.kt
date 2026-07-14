package com.ghostserializer.sync.sample.ui.action

import com.ghostserializer.sync.sample.app.SyncSetup
import com.ghostserializer.sync.sample.ui.model.ServerHealthStatus
import com.ghostserializer.sync.sample.ui.util.healthUrl
import io.ktor.client.request.get
import io.ktor.http.isSuccess

internal suspend fun probeServerHealth(): ServerHealthStatus {
    val online = runCatching { SyncSetup.liveClient.get(healthUrl()) }
        .fold(
            onSuccess = { it.status.isSuccess() },
            onFailure = { false },
        )
    SyncSetup.reportConnectivity(online)
    return if (online) ServerHealthStatus.Online else ServerHealthStatus.Offline
}
