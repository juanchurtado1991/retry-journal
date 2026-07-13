package com.ghostserializer.sync.sample.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ghostserializer.sync.sample.shared.MutationRequest
import com.ghostserializer.sync.sample.shared.SampleApiConstants
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.launch

/**
 * The stress-test screen from the Fase 6 validation plan: enqueue thousands of mutations while
 * the chaos server is unreachable (offline queueing), then flush once it's back.
 *
 * This module talks to `sync-sample:server` running locally. To actually exercise the offline
 * path, make sure the server is **not** running (or the device is in airplane mode) before
 * pressing "Enqueue offline" — every POST will fail with a real connection error, which is
 * exactly what `GhostOfflineQueuePlugin` is meant to catch. Start the server
 * (`./gradlew :sync-sample:server:run`) before pressing "Flush now".
 *
 * The "Ktorfit" button proves the same offline-queueing works transparently under a
 * Ktorfit-generated call, not just hand-written `HttpClient.post()` calls like [enqueueMutations]
 * below — see [SyncSetup.mutationApi] and [MutationApi].
 */
@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            StressTestScreen()
        }
    }
}

@Composable
private fun StressTestScreen() {
    val scope = rememberCoroutineScope()
    var queueSize by remember { mutableStateOf(0) }
    var deadLetterSize by remember { mutableStateOf(0) }
    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf(AppStrings.STATUS_READY) }
    var ktorfitCallCount by remember { mutableStateOf(0) }

    suspend fun refreshCounts() {
        queueSize = SyncSetup.diskQueue.size()
        deadLetterSize = SyncSetup.deadLetterQueue.peekAll().size
    }

    LaunchedEffect(Unit) { refreshCounts() }

    Column(
        modifier = Modifier.fillMaxSize().padding(AppDimens.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(AppDimens.SECTION_SPACING),
    ) {
        Text(AppStrings.SCREEN_TITLE, style = MaterialTheme.typography.headlineSmall)
        Text(AppStrings.PENDING_LABEL_PREFIX + queueSize)
        Text(AppStrings.DEAD_LETTERED_LABEL_PREFIX + deadLetterSize)
        Text(statusMessage)

        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.BUTTON_SPACING)) {
            Button(
                enabled = !isBusy,
                onClick = {
                    scope.launch {
                        isBusy = true
                        statusMessage = AppStrings.ENQUEUEING_PREFIX + AppConstants.DEFAULT_MUTATION_COUNT + AppStrings.ENQUEUEING_SUFFIX
                        enqueueMutations(AppConstants.DEFAULT_MUTATION_COUNT)
                        refreshCounts()
                        statusMessage = AppStrings.ENQUEUED_PREFIX + AppConstants.DEFAULT_MUTATION_COUNT + AppStrings.ENQUEUED_SUFFIX
                        isBusy = false
                    }
                },
            ) { Text(AppStrings.ENQUEUE_BUTTON_PREFIX + AppConstants.DEFAULT_MUTATION_COUNT + AppStrings.ENQUEUE_BUTTON_SUFFIX) }

            Button(
                enabled = !isBusy,
                onClick = {
                    scope.launch {
                        isBusy = true
                        statusMessage = AppStrings.ENQUEUEING_PREFIX + AppConstants.STRESS_TEST_MUTATION_COUNT + AppStrings.ENQUEUEING_SUFFIX
                        enqueueMutations(AppConstants.STRESS_TEST_MUTATION_COUNT)
                        refreshCounts()
                        statusMessage = AppStrings.ENQUEUED_PREFIX + AppConstants.STRESS_TEST_MUTATION_COUNT + AppStrings.ENQUEUED_SUFFIX
                        isBusy = false
                    }
                },
            ) { Text(AppStrings.STRESS_TEST_BUTTON_PREFIX + AppConstants.STRESS_TEST_MUTATION_COUNT) }
        }

        Button(
            enabled = !isBusy,
            onClick = {
                scope.launch {
                    isBusy = true
                    statusMessage = AppStrings.FLUSHING
                    val result = SyncSetup.syncEngine.flush(SyncSetup.replayClient)
                    refreshCounts()
                    statusMessage = AppStrings.FLUSHED_RESULT_PREFIX + result.delivered +
                        AppStrings.FLUSHED_RESULT_DEAD_LETTERED + result.deadLettered +
                        AppStrings.FLUSHED_RESULT_STOPPED_EARLY + result.stoppedEarly
                    isBusy = false
                }
            },
        ) { Text(AppStrings.FLUSH_BUTTON) }

        Button(
            enabled = !isBusy,
            onClick = {
                scope.launch {
                    isBusy = true
                    ktorfitCallCount++
                    runCatching {
                        SyncSetup.mutationApi.createMutation(
                            MutationRequest(
                                id = AppStrings.KTORFIT_DEMO_ID_PREFIX + ktorfitCallCount,
                                payload = AppStrings.KTORFIT_DEMO_PAYLOAD,
                                createdAtMillis = ktorfitCallCount.toLong(),
                            ),
                        )
                    }
                    // Same story as enqueueMutations(): a thrown OfflineQueuedException means
                    // GhostOfflineQueuePlugin already queued it, even though this call went
                    // through Ktorfit's generated _MutationApiImpl, not a raw HttpClient.post().
                    refreshCounts()
                    isBusy = false
                }
            },
        ) { Text(AppStrings.KTORFIT_DEMO_BUTTON) }
    }
}

private suspend fun enqueueMutations(count: Int) {
    val serverUrl = AppStrings.SERVER_URL_SCHEME + AppConstants.SERVER_HOST +
        AppStrings.SERVER_URL_PORT_SEPARATOR + SampleApiConstants.DEFAULT_PORT + SampleApiConstants.MUTATIONS_PATH

    repeat(count) { index ->
        val request = MutationRequest(
            id = AppStrings.MUTATION_ID_PREFIX + index,
            payload = AppStrings.MUTATION_PAYLOAD_PREFIX + index,
            createdAtMillis = index.toLong(),
        )
        runCatching {
            SyncSetup.liveClient.post(serverUrl) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
        // A thrown OfflineQueuedException (or any other failure) is expected and already handled:
        // the request has either been queued by GhostOfflineQueuePlugin or actually delivered.
    }
}
