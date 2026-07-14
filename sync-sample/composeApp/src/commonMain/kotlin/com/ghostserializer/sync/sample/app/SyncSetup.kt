package com.ghostserializer.sync.sample.app

import com.ghost.serialization.ktor.ghost
import com.ghostserializer.sync.GhostSyncRuntime
import com.ghostserializer.sync.client.GhostOfflineQueuePlugin
import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.engine.GhostSyncEngine
import com.ghostserializer.sync.queue.disk.DiskQueue
import com.ghostserializer.sync.sample.shared.SampleApiConstants
import de.jensklingenberg.ktorfit.Ktorfit
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.Path.Companion.toPath

/**
 * Wires the library once per process. [runtime] is the app-layer entry point for `flush()` —
 * UI, workers, and connectivity callbacks should call it instead of [GhostSyncEngine.flush]
 * directly.
 */
internal object SyncSetup {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val connectivityState = MutableStateFlow(false)
    val connectivity: StateFlow<Boolean> = connectivityState.asStateFlow()

    /** Feed from your platform network observer or, in this demo, the chaos-server health poll. */
    fun reportConnectivity(online: Boolean) {
        connectivityState.value = online
    }

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

    val liveClient: HttpClient by lazy {
        HttpClient(platformHttpClientEngine()) {
            install(ContentNegotiation) { ghost() }
            install(GhostOfflineQueuePlugin) { diskQueue = this@SyncSetup.diskQueue }
        }
    }

    val replayClient: HttpClient by lazy {
        HttpClient(platformHttpClientEngine()) {
            install(ContentNegotiation) { ghost() }
        }
    }

    val runtime: GhostSyncRuntime by lazy {
        GhostSyncRuntime.createForEngine(
            engine = syncEngine,
            replayClient = replayClient,
            scope = appScope,
            connectivity = connectivity,
        )
    }

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
