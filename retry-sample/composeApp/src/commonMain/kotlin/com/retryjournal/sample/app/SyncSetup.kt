package com.retryjournal.sample.app

import com.ghost.serialization.ktor.ghost
import com.retryjournal.RetryJournalRuntime
import com.retryjournal.client.RetryJournalOfflineQueuePlugin
import com.retryjournal.deadletter.DeadLetterQueue
import com.retryjournal.engine.RetryJournalEngine
import com.retryjournal.queue.disk.DiskQueue
import com.retryjournal.sample.shared.SampleApiConstants
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
import com.ghost.serialization.Ghost

/**
 * Wires the library once per process. [runtime] is the app-layer entry point for `flush()` —
 * UI, workers, and connectivity callbacks should call it instead of [RetryJournalEngine.flush]
 * directly.
 */
internal object SyncSetup {

    init {
        Ghost.addRegistry(com.ghost.serialization.generated.GhostModuleRegistry_retry_journal.INSTANCE)
        Ghost.addRegistry(com.ghost.serialization.generated.GhostModuleRegistry_retry_journal_sample_shared.INSTANCE)
    }

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

    val syncEngine: RetryJournalEngine by lazy {
        RetryJournalEngine(diskQueue, deadLetterQueue)
    }

    val liveClient: HttpClient by lazy {
        HttpClient(platformHttpClientEngine()) {
            install(ContentNegotiation) { ghost() }
            install(RetryJournalOfflineQueuePlugin) { diskQueue = this@SyncSetup.diskQueue }
        }
    }

    val replayClient: HttpClient by lazy {
        HttpClient(platformHttpClientEngine()) {
            install(ContentNegotiation) { ghost() }
        }
    }

    val runtime: RetryJournalRuntime by lazy {
        RetryJournalRuntime.createForEngine(
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
