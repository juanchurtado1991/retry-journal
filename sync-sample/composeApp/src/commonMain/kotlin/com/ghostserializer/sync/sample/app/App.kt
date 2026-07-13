package com.ghostserializer.sync.sample.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.ghostserializer.sync.sample.shared.MutationRequest
import com.ghostserializer.sync.sample.shared.SampleApiConstants
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

private val appStartMark = TimeSource.Monotonic.markNow()

/**
 * The stress-test screen: enqueue thousands of mutations while the chaos server is unreachable
 * (offline queueing), then flush once it's back — see [ServerStatusBanner] for a live read on
 * whether the server is actually reachable right now, and [ActivityLogCard] for a running history
 * of everything the demo has done.
 *
 * This module talks to `sync-sample:server` running locally
 * (`./gradlew :sync-sample:server:run`).
 *
 * The "Ktorfit" button proves the same offline-queueing works transparently under a
 * Ktorfit-generated call, not just hand-written `HttpClient.post()` calls like [enqueueMutations]
 * below — see [SyncSetup.mutationApi] and [MutationApi].
 */
@Composable
fun App() {
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme) {
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
    var ktorfitCallCount by remember { mutableStateOf(0) }
    var serverStatus by remember { mutableStateOf(ServerHealthStatus.Checking) }
    val logEntries = remember { mutableStateListOf<ActivityLogEntry>() }

    fun log(message: String, kind: LogKind = LogKind.Info) {
        logEntries.add(0, ActivityLogEntry(appStartMark.elapsedNow(), message, kind))
        if (logEntries.size > AppConstants.ACTIVITY_LOG_MAX_ENTRIES) {
            logEntries.removeAt(logEntries.lastIndex)
        }
    }

    suspend fun refreshCounts() {
        queueSize = SyncSetup.diskQueue.size()
        deadLetterSize = SyncSetup.deadLetterQueue.peekAll().size
    }

    LaunchedEffect(Unit) {
        refreshCounts()
        log(AppStrings.LOG_APP_READY)
    }

    LaunchedEffect(Unit) {
        while (true) {
            serverStatus = runCatching { SyncSetup.replayClient.get(healthUrl()) }
                .fold(
                    onSuccess = { if (it.status.isSuccess()) ServerHealthStatus.Online else ServerHealthStatus.Offline },
                    onFailure = { ServerHealthStatus.Offline },
                )
            refreshCounts()
            delay(AppConstants.SERVER_HEALTH_POLL_INTERVAL_MS)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AppDimens.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(AppDimens.SECTION_SPACING),
    ) {
        Header()
        ServerStatusBanner(serverStatus)
        StatsRow(pending = queueSize, deadLettered = deadLetterSize)

        ActionCard(title = AppStrings.STEP1_TITLE, description = AppStrings.STEP1_DESCRIPTION) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.BUTTON_SPACING)) {
                Button(
                    enabled = !isBusy,
                    onClick = {
                        scope.launch {
                            isBusy = true
                            log(AppStrings.LOG_ENQUEUEING_PREFIX + AppConstants.DEFAULT_MUTATION_COUNT + AppStrings.LOG_ENQUEUEING_SUFFIX)
                            enqueueMutations(AppConstants.DEFAULT_MUTATION_COUNT)
                            refreshCounts()
                            log(AppStrings.LOG_ENQUEUED_PREFIX + AppConstants.DEFAULT_MUTATION_COUNT + AppStrings.LOG_ENQUEUED_SUFFIX, LogKind.Success)
                            isBusy = false
                        }
                    },
                ) { Text(AppStrings.ENQUEUE_BUTTON_PREFIX + AppConstants.DEFAULT_MUTATION_COUNT + AppStrings.ENQUEUE_BUTTON_SUFFIX) }

                Button(
                    enabled = !isBusy,
                    onClick = {
                        scope.launch {
                            isBusy = true
                            log(AppStrings.LOG_ENQUEUEING_PREFIX + AppConstants.STRESS_TEST_MUTATION_COUNT + AppStrings.LOG_ENQUEUEING_SUFFIX)
                            enqueueMutations(AppConstants.STRESS_TEST_MUTATION_COUNT)
                            refreshCounts()
                            log(AppStrings.LOG_ENQUEUED_PREFIX + AppConstants.STRESS_TEST_MUTATION_COUNT + AppStrings.LOG_ENQUEUED_SUFFIX, LogKind.Success)
                            isBusy = false
                        }
                    },
                ) { Text(AppStrings.STRESS_TEST_BUTTON_PREFIX + AppConstants.STRESS_TEST_MUTATION_COUNT) }
            }
        }

        ActionCard(title = AppStrings.STEP2_TITLE, description = AppStrings.STEP2_DESCRIPTION) {
            Button(
                enabled = !isBusy,
                onClick = {
                    scope.launch {
                        isBusy = true
                        log(AppStrings.LOG_FLUSHING)
                        val result = SyncSetup.syncEngine.flush(SyncSetup.replayClient)
                        refreshCounts()
                        log(
                            AppStrings.LOG_FLUSHED_RESULT_PREFIX + result.delivered +
                                AppStrings.LOG_FLUSHED_RESULT_DEAD_LETTERED + result.deadLettered +
                                AppStrings.LOG_FLUSHED_RESULT_STOPPED_EARLY + result.stoppedEarly,
                            if (result.stoppedEarly) LogKind.Error else LogKind.Success,
                        )
                        isBusy = false
                    }
                },
            ) { Text(AppStrings.FLUSH_BUTTON) }
        }

        ActionCard(title = AppStrings.STEP3_TITLE, description = AppStrings.STEP3_DESCRIPTION) {
            Button(
                enabled = !isBusy,
                onClick = {
                    scope.launch {
                        isBusy = true
                        ktorfitCallCount++
                        val callNumber = ktorfitCallCount
                        runCatching {
                            SyncSetup.mutationApi.createMutation(
                                MutationRequest(
                                    id = AppStrings.KTORFIT_DEMO_ID_PREFIX + callNumber,
                                    payload = AppStrings.KTORFIT_DEMO_PAYLOAD,
                                    createdAtMillis = callNumber.toLong(),
                                ),
                            )
                        }
                        // Same story as enqueueMutations(): a thrown OfflineQueuedException means
                        // GhostOfflineQueuePlugin already queued it, even though this call went
                        // through Ktorfit's generated _MutationApiImpl, not a raw HttpClient.post().
                        refreshCounts()
                        log(AppStrings.LOG_KTORFIT_SENT_PREFIX + callNumber, LogKind.Success)
                        isBusy = false
                    }
                },
            ) { Text(AppStrings.KTORFIT_DEMO_BUTTON) }
        }

        ActivityLogCard(logEntries)
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(AppDimens.TITLE_SUBTITLE_SPACING)) {
        Text(AppStrings.SCREEN_TITLE, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            AppStrings.APP_SUBTITLE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServerStatusBanner(status: ServerHealthStatus) {
    val (dotColor, message) = when (status) {
        ServerHealthStatus.Checking -> MaterialTheme.colorScheme.onSurfaceVariant to AppStrings.SERVER_CHECKING_MESSAGE
        ServerHealthStatus.Online -> MaterialTheme.colorScheme.primary to AppStrings.SERVER_ONLINE_MESSAGE
        ServerHealthStatus.Offline -> MaterialTheme.colorScheme.error to AppStrings.SERVER_OFFLINE_MESSAGE
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier.padding(AppDimens.CARD_PADDING).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppDimens.CARD_INTERNAL_SPACING),
        ) {
            Box(modifier = Modifier.size(AppDimens.STATUS_DOT_SIZE).clip(CircleShape).background(dotColor))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatsRow(pending: Int, deadLettered: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.STAT_CARD_SPACING)) {
        StatCard(title = AppStrings.STAT_PENDING_TITLE, value = pending, modifier = Modifier.weight(1f))
        StatCard(title = AppStrings.STAT_DEAD_LETTERED_TITLE, value = deadLettered, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(title: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(AppDimens.CARD_PADDING).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionCard(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AppDimens.CARD_PADDING).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppDimens.CARD_INTERNAL_SPACING),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ActivityLogCard(entries: List<ActivityLogEntry>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AppDimens.CARD_PADDING).fillMaxWidth()) {
            Text(AppStrings.ACTIVITY_LOG_TITLE, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(AppDimens.CARD_INTERNAL_SPACING))
            if (entries.isEmpty()) {
                Text(AppStrings.ACTIVITY_LOG_EMPTY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.height(AppDimens.ACTIVITY_LOG_HEIGHT).fillMaxWidth()) {
                    items(entries) { entry -> ActivityLogRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun ActivityLogRow(entry: ActivityLogEntry) {
    val messageColor = when (entry.kind) {
        LogKind.Info -> MaterialTheme.colorScheme.onSurfaceVariant
        LogKind.Success -> MaterialTheme.colorScheme.primary
        LogKind.Error -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppDimens.LOG_ROW_VERTICAL_PADDING),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.CARD_INTERNAL_SPACING),
    ) {
        Text(formatElapsed(entry.elapsed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(entry.message, style = MaterialTheme.typography.bodySmall, color = messageColor)
    }
}

private fun formatElapsed(elapsed: Duration): String =
    AppStrings.LOG_TIMESTAMP_PREFIX + elapsed.toString(DurationUnit.SECONDS, decimals = AppConstants.LOG_TIMESTAMP_DECIMALS)

private fun healthUrl(): String =
    AppStrings.SERVER_URL_SCHEME + platformServerHost +
        AppStrings.SERVER_URL_PORT_SEPARATOR + SampleApiConstants.DEFAULT_PORT + SampleApiConstants.HEALTH_PATH

private suspend fun enqueueMutations(count: Int) {
    val serverUrl = AppStrings.SERVER_URL_SCHEME + platformServerHost +
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
