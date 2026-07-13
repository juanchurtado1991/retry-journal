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
 * This module talks to `sample:server` running locally. To actually exercise the offline path,
 * make sure the server is **not** running (or the device is in airplane mode) before pressing
 * "Enqueue offline" — every POST will fail with a real connection error, which is exactly what
 * `GhostOfflineQueuePlugin` is meant to catch. Start the server (`./gradlew :sample:server:run`)
 * before pressing "Flush now".
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
    var statusMessage by remember { mutableStateOf("Ready") }

    suspend fun refreshCounts() {
        queueSize = SyncSetup.diskQueue.size()
        deadLetterSize = SyncSetup.deadLetterQueue.peekAll().size
    }

    LaunchedEffect(Unit) { refreshCounts() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ghost Sync — Stress Test", style = MaterialTheme.typography.headlineSmall)
        Text("Pending in queue: $queueSize")
        Text("Dead-lettered: $deadLetterSize")
        Text(statusMessage)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !isBusy,
                onClick = {
                    scope.launch {
                        isBusy = true
                        statusMessage = "Enqueueing ${AppConstants.DEFAULT_MUTATION_COUNT} offline..."
                        enqueueMutations(AppConstants.DEFAULT_MUTATION_COUNT)
                        refreshCounts()
                        statusMessage = "Enqueued ${AppConstants.DEFAULT_MUTATION_COUNT}."
                        isBusy = false
                    }
                },
            ) { Text("Enqueue ${AppConstants.DEFAULT_MUTATION_COUNT} offline") }

            Button(
                enabled = !isBusy,
                onClick = {
                    scope.launch {
                        isBusy = true
                        statusMessage = "Enqueueing ${AppConstants.STRESS_TEST_MUTATION_COUNT} offline..."
                        enqueueMutations(AppConstants.STRESS_TEST_MUTATION_COUNT)
                        refreshCounts()
                        statusMessage = "Enqueued ${AppConstants.STRESS_TEST_MUTATION_COUNT}."
                        isBusy = false
                    }
                },
            ) { Text("Stress test: ${AppConstants.STRESS_TEST_MUTATION_COUNT}") }
        }

        Button(
            enabled = !isBusy,
            onClick = {
                scope.launch {
                    isBusy = true
                    statusMessage = "Flushing..."
                    val result = SyncSetup.syncEngine.flush(SyncSetup.replayClient)
                    refreshCounts()
                    statusMessage = "Flushed — delivered=${result.delivered} " +
                        "deadLettered=${result.deadLettered} stoppedEarly=${result.stoppedEarly}"
                    isBusy = false
                }
            },
        ) { Text("Flush now") }
    }
}

private suspend fun enqueueMutations(count: Int) {
    val serverUrl = "http://${AppConstants.SERVER_HOST}:${SampleApiConstants.DEFAULT_PORT}${SampleApiConstants.MUTATIONS_PATH}"

    repeat(count) { index ->
        val request = MutationRequest(
            id = "mutation-$index",
            payload = "stress-test-payload-$index",
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
