package com.ghostserializer.sync.sample.app

import com.ghost.serialization.ktor.ghost
import com.ghostserializer.sync.client.GhostOfflineQueuePlugin
import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.engine.GhostSyncEngine
import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.sample.shared.SampleApiConstants
import de.jensklingenberg.ktorfit.Ktorfit
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import okio.Path.Companion.toPath

/** Wires the library together once per process. Everything here is a lazily-created singleton. */
internal object SyncSetup {

    val diskQueue: DiskQueue by lazy {
        DiskQueue(queuePath(AppConstants.QUEUE_FILE_NAME))
    }

    private val deadLetterStorage: DiskQueue by lazy {
        DiskQueue(queuePath(AppConstants.DEAD_LETTER_FILE_NAME))
    }

    val deadLetterQueue: DeadLetterQueue by lazy {
        DeadLetterQueue(mainQueue = diskQueue, storage = deadLetterStorage)
    }

    val syncEngine: GhostSyncEngine by lazy {
        GhostSyncEngine(diskQueue, deadLetterQueue)
    }

    /** Installed on every app-facing request; queues the request on a connectivity failure. */
    val liveClient: HttpClient by lazy {
        HttpClient(platformHttpClientEngine()) {
            install(ContentNegotiation) { ghost() }
            install(GhostOfflineQueuePlugin) { diskQueue = this@SyncSetup.diskQueue }
        }
    }

    /**
     * Used only by [GhostSyncEngine.flush]. Deliberately does **not** install
     * [GhostOfflineQueuePlugin] — see that function's KDoc for why replaying through it would
     * silently duplicate a still-failing entry.
     */
    val replayClient: HttpClient by lazy {
        HttpClient(platformHttpClientEngine()) {
            install(ContentNegotiation) { ghost() }
        }
    }

    /**
     * Proves [GhostOfflineQueuePlugin] works transparently under Ktorfit-generated calls, not
     * just hand-written `HttpClient` requests: Ktorfit is built on [liveClient] here, so every
     * `_MutationApiImpl`-generated call still goes through the same `HttpSend` interceptor chain
     * — see [MutationApi].
     */
    private val ktorfit: Ktorfit by lazy {
        Ktorfit.Builder()
            .httpClient(liveClient)
            .baseUrl(
                AppStrings.SERVER_URL_SCHEME + platformServerHost +
                    AppStrings.SERVER_URL_PORT_SEPARATOR + SampleApiConstants.DEFAULT_PORT +
                    AppStrings.SERVER_URL_TRAILING_SLASH,
            )
            .build()
    }

    val mutationApi: MutationApi by lazy { ktorfit.createMutationApi() }

    private fun queuePath(fileName: String) = (platformDataDirectory() + "/" + fileName).toPath()
}
