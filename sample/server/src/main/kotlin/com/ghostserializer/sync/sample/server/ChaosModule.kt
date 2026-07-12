package com.ghostserializer.sync.sample.server

import com.ghost.serialization.ktor.ghost
import com.ghost.serialization.ktor.respondGhost
import com.ghostserializer.sync.sample.shared.MutationAck
import com.ghostserializer.sync.sample.shared.MutationRequest
import com.ghostserializer.sync.sample.shared.SampleApiConstants
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deliberately hostile Ktor server used to exercise [com.ghostserializer.sync.client.GhostOfflineQueuePlugin]
 * and [com.ghostserializer.sync.engine.GhostSyncEngine] under real chaos, per the Fase 6 validation plan:
 * intermittent 503s, tolerable latency, a genuine 4xx (to exercise the dead-letter path), and a
 * delay long enough to blow past the sample client's socket timeout (to exercise offline queueing).
 */
fun Application.chaosModule() {
    val requestCount = AtomicInteger(0)

    install(ContentNegotiation) { ghost() }

    routing {
        get(SampleApiConstants.HEALTH_PATH) {
            call.respond(HttpStatusCode.OK, "ok")
        }

        post(SampleApiConstants.MUTATIONS_PATH) {
            val request = call.receive<MutationRequest>()
            val requestNumber = requestCount.incrementAndGet()

            when {
                requestNumber % ChaosConstants.EVERY_NTH_OFFLINE_TIMEOUT == 0 -> {
                    delay(ChaosConstants.OFFLINE_TIMEOUT_DELAY_MS)
                    call.respondGhost(MutationAck(request.id, System.currentTimeMillis()))
                }

                requestNumber % ChaosConstants.EVERY_NTH_BAD_REQUEST == 0 -> {
                    call.respond(HttpStatusCode.BadRequest)
                }

                requestNumber % ChaosConstants.EVERY_NTH_SERVICE_UNAVAILABLE == 0 -> {
                    call.respond(HttpStatusCode.ServiceUnavailable)
                }

                requestNumber % ChaosConstants.EVERY_NTH_LATENCY == 0 -> {
                    delay(ChaosConstants.LATENCY_DELAY_MS)
                    call.respondGhost(MutationAck(request.id, System.currentTimeMillis()))
                }

                else -> {
                    call.respondGhost(MutationAck(request.id, System.currentTimeMillis()))
                }
            }
        }
    }
}
