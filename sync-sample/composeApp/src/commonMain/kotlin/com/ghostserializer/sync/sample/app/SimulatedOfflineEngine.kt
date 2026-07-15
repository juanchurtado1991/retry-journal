package com.ghostserializer.sync.sample.app

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.util.AttributeKey
import io.ktor.utils.io.errors.IOException

/**
 * A Ktor client plugin that throws [IOException] for every request when
 * [SimulatedConnectivityController.isSimulatedOffline] is true.
 *
 * **Installation order matters.** This plugin must be installed *before*
 * [com.ghostserializer.sync.client.GhostOfflineQueuePlugin] in the [HttpClient] block.
 * Ktor's [HttpSend] chain runs last-registered-first, so installing this first makes it the
 * *innermost* interceptor — the one that actually throws. [GhostOfflineQueuePlugin], installed
 * second (outermost), wraps the call in a try/catch, sees the [IOException], and persists the
 * request to the disk queue exactly as it would for a real network outage.
 */
internal object SimulatedOfflinePlugin :
    HttpClientPlugin<Unit, SimulatedOfflinePlugin> {

    override val key: AttributeKey<SimulatedOfflinePlugin> =
        AttributeKey("SimulatedOfflinePlugin")

    override fun prepare(block: Unit.() -> Unit): SimulatedOfflinePlugin = this

    override fun install(plugin: SimulatedOfflinePlugin, scope: HttpClient) {
        scope.plugin(HttpSend).intercept { request ->
            if (SimulatedConnectivityController.isSimulatedOffline) {
                throw IOException("Simulated connectivity is offline — request queued to disk.")
            }
            execute(request)
        }
    }
}
