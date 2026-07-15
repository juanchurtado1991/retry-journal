package com.retryjournal.sample.app

import com.retryjournal.sample.shared.MutationAck
import com.retryjournal.sample.shared.MutationRequest
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.Headers
import de.jensklingenberg.ktorfit.http.Multipart
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.Part
import io.ktor.http.content.PartData

/**
 * Every network call this demo makes — JSON mutations and file uploads alike — goes through this
 * Ktorfit-generated interface. That's a choice made for this sample, not a requirement of
 * `:retry-journal`: [RetryJournalOfflineQueuePlugin][com.retryjournal.client.RetryJournalOfflineQueuePlugin]
 * is installed on a plain [io.ktor.client.HttpClient] (see [SyncSetup.liveClient]) and doesn't
 * know Ktorfit exists — it intercepts at the `HttpSend` phase, which every request reaches no
 * matter how it was built. Ktorfit's generated `_MutationApiImpl` is itself just calling that same
 * `HttpClient` under the hood, so routing everything through it here proves the plugin doesn't
 * care — a handwritten `HttpClient.post(...)` (see the root README's Quick start) is captured and
 * queued exactly the same way.
 */
interface MutationApi {
    @Headers("Content-Type: application/json")
    @POST("mutations")
    suspend fun createMutation(@Body request: MutationRequest): MutationAck

    /** `@Part`'s value may be a `String` or a `List<PartData>` — a `List<PartData>` is exactly
     * what Ktor's own `formData { }` builder already returns, so the caller builds the multipart
     * body the same way it would for a raw `HttpClient` call and just hands it to this instead. */
    @Multipart
    @POST("uploads")
    suspend fun uploadFile(@Part("file") file: List<PartData>): String
}
