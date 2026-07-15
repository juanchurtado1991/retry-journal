package com.retryjournal.sample.server

import com.ghost.serialization.ktor.ghost
import com.ghost.serialization.ktor.respondGhost
import com.retryjournal.sample.shared.MutationAck
import com.retryjournal.sample.shared.MutationRequest
import com.retryjournal.sample.shared.SampleApiConstants
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

/**
 * No artificial failures — every request succeeds. Same routes as [chaosModule], minus the
 * rotation, so you can watch retry-journal replay a large batch (100+ queued requests) in a
 * single `flush()` without a 503/400/timeout interrupting it partway through.
 */
fun Application.normalModule() {
    install(ContentNegotiation) { ghost() }

    routing {
        get(SampleApiConstants.HEALTH_PATH) {
            call.respond(HttpStatusCode.OK, ChaosConstants.HEALTH_CHECK_RESPONSE_BODY)
        }

        post(SampleApiConstants.MUTATIONS_PATH) {
            val request = call.receive<MutationRequest>()
            call.respondGhost(MutationAck(request.id, System.currentTimeMillis()))
        }

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
