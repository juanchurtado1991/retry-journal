package com.ghostserializer.sync.sample.app

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.util.InternalAPI
import io.ktor.utils.io.errors.IOException

/**
 * Engine wrapper that throws [IOException] for every request when
 * [SimulatedConnectivityController.isSimulatedOffline] is true.
 *
 * Because this wraps the real engine (i.e. sits *inside* the Ktor plugin pipeline),
 * [GhostOfflineQueuePlugin] — which intercepts *around* the engine call — catches the
 * exception and persists the request to the disk queue exactly as it would for a real
 * network outage.
 */
@OptIn(InternalAPI::class)
internal class SimulatedOfflineEngine(
    private val delegate: HttpClientEngine,
) : HttpClientEngine by delegate {

    @InternalAPI
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        if (SimulatedConnectivityController.isSimulatedOffline) {
            throw IOException("Simulated connectivity is offline — request queued to disk.")
        }
        return delegate.execute(data)
    }
}
