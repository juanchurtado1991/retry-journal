package com.ghostserializer.sync.sample.app

import com.ghostserializer.sync.sample.shared.MutationAck
import com.ghostserializer.sync.sample.shared.MutationRequest
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.POST

/**
 * Proves GhostOfflineQueuePlugin works transparently under a Ktorfit-generated implementation,
 * not just handwritten HttpClient calls: Ktorfit's generated code still routes every call
 * through the same HttpClient (and therefore the same HttpSend interceptor chain) it was built
 * with — see SyncSetup for how this is wired to ghostSync.client.
 */
interface MutationApi {
    @POST("mutations")
    suspend fun createMutation(@Body request: MutationRequest): MutationAck
}
