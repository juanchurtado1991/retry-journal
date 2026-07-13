package com.ghostserializer.sync.sample.server

import com.ghost.serialization.ktor.ghost
import com.ghost.serialization.ktor.respondGhost
import com.ghostserializer.sync.sample.shared.MutationAck
import com.ghostserializer.sync.sample.shared.MutationRequest
import com.ghostserializer.sync.sample.shared.SampleApiConstants
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Deliberately hostile Ktor server used to exercise [com.ghostserializer.sync.client.GhostOfflineQueuePlugin]
 * and [com.ghostserializer.sync.engine.GhostSyncEngine] under real chaos, per validation plan:
 * intermittent 503s, tolerable latency, a genuine 4xx (to exercise the dead-letter path), and a
 * delay long enough to blow past the sample client's socket timeout (to exercise offline queueing).
 */
fun Application.chaosModule() {
    val requestCount = AtomicInteger(0)

    install(ContentNegotiation) { ghost() }

    routing {
        get(SampleApiConstants.HEALTH_PATH) {
            call.respond(HttpStatusCode.OK, ChaosConstants.HEALTH_CHECK_RESPONSE_BODY)
        }

        post(SampleApiConstants.MUTATIONS_PATH) {
            val request = call.receive<MutationRequest>()
            val requestNumber = requestCount.incrementAndGet()

            when {
                requestNumber % ChaosConstants.EVERY_NTH_OFFLINE_TIMEOUT == 0 -> {
                    delay(ChaosConstants.OFFLINE_TIMEOUT_DELAY_MS.milliseconds)
                    call.respondGhost(MutationAck(request.id, System.currentTimeMillis()))
                }

                requestNumber % ChaosConstants.EVERY_NTH_BAD_REQUEST == 0 -> {
                    call.respond(HttpStatusCode.BadRequest)
                }

                requestNumber % ChaosConstants.EVERY_NTH_SERVICE_UNAVAILABLE == 0 -> {
                    call.respond(HttpStatusCode.ServiceUnavailable)
                }

                requestNumber % ChaosConstants.EVERY_NTH_LATENCY == 0 -> {
                    delay(ChaosConstants.LATENCY_DELAY_MS.milliseconds)
                    call.respondGhost(MutationAck(request.id, System.currentTimeMillis()))
                }

                else -> {
                    call.respondGhost(MutationAck(request.id, System.currentTimeMillis()))
                }
            }
        }

        // Deliberately simple compared to /mutations: no chaos rotation, always succeeds when
        // reachable. The offline/online server switch in the demo is what exercises the
        // interesting path (GhostOfflineQueuePlugin capturing a real multipart body correctly)
        // — this endpoint just needs to be a real destination for it to capture bytes for.
        post(SampleApiConstants.UPLOAD_PATH) {
            var receivedBytes = 0L
            val multipart = call.receiveMultipart()
            multipart.forEachPart { part ->
                if (part is PartData.FileItem) {
                    receivedBytes += part.streamProvider().readBytes().size
                }
                part.dispose()
            }
            call.respond(HttpStatusCode.OK, receivedBytes.toString())
        }
    }
}
