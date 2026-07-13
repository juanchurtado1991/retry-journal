package com.ghostserializer.sync.sample.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

private val appStartMark = TimeSource.Monotonic.markNow()

/**
 * The demo screen. Where [MockServerController.isSupported] is true (desktop), the chaos server
 * runs in-process and the switch in [ServerStatusRow] turns it on/off directly — no second
 * terminal needed. Elsewhere (Android/iOS) it just reports whether a server you started yourself
 * (`./gradlew :sync-sample:server:run`) is reachable.
 *
 * "Show advanced options" holds the same offline-queueing demo through a Ktorfit-generated call
 * instead of a hand-written `HttpClient.post()` — see [SyncSetup.mutationApi] and [MutationApi] —
 * plus larger stress-test batch sizes, both kept out of the way of the simple flow above.
 */
@Composable
fun App() {
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            DemoScreen()
        }
    }
}

@Composable
private fun DemoScreen() {
    val scope = rememberCoroutineScope()
    var queueSize by remember { mutableStateOf(0) }
    var deadLetterSize by remember { mutableStateOf(0) }
    var isBusy by remember { mutableStateOf(false) }
    var ktorfitCallCount by remember { mutableStateOf(0) }
    var serverStatus by remember { mutableStateOf(ServerHealthStatus.Checking) }
    var showAdvanced by remember { mutableStateOf(false) }
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

    suspend fun checkServerStatus() {
        serverStatus = runCatching { SyncSetup.replayClient.get(healthUrl()) }
            .fold(
                onSuccess = { if (it.status.isSuccess()) ServerHealthStatus.Online else ServerHealthStatus.Offline },
                onFailure = { ServerHealthStatus.Offline },
            )
    }

    LaunchedEffect(Unit) {
        if (MockServerController.isSupported) {
            MockServerController.start()
        }
        refreshCounts()
        checkServerStatus()
        log(AppStrings.LOG_APP_READY)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(AppConstants.SERVER_HEALTH_POLL_INTERVAL_MS)
            checkServerStatus()
            refreshCounts()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AppDimens.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(AppDimens.SECTION_SPACING),
    ) {
        Header()

        ServerStatusRow(
            status = serverStatus,
            controllable = MockServerController.isSupported,
            enabled = !isBusy,
            onToggle = {
                scope.launch {
                    isBusy = true
                    if (serverStatus == ServerHealthStatus.Online) {
                        MockServerController.stop()
                        log(AppStrings.SERVER_TURNED_OFF_LOG)
                    } else {
                        MockServerController.start()
                        log(AppStrings.SERVER_TURNED_ON_LOG)
                    }
                    delay(AppConstants.SERVER_TOGGLE_SETTLE_MS)
                    checkServerStatus()
                    isBusy = false
                }
            },
        )

        StatsRow(pending = queueSize, deadLettered = deadLetterSize)

        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.BUTTON_SPACING)) {
            Button(
                enabled = !isBusy,
                onClick = {
                    scope.launch {
                        isBusy = true
                        log(AppStrings.LOG_SENDING_PREFIX + AppConstants.SIMPLE_SEND_COUNT + AppStrings.LOG_SENDING_SUFFIX)
                        enqueueMutations(AppConstants.SIMPLE_SEND_COUNT)
                        refreshCounts()
                        log(AppStrings.LOG_SENT_PREFIX + AppConstants.SIMPLE_SEND_COUNT + AppStrings.LOG_SENT_SUFFIX, LogKind.Success)
                        isBusy = false
                    }
                },
            ) { Text(AppStrings.SEND_BUTTON_PREFIX + AppConstants.SIMPLE_SEND_COUNT + AppStrings.SEND_BUTTON_SUFFIX) }

            Button(
                enabled = !isBusy,
                onClick = {
                    scope.launch {
                        isBusy = true
                        log(AppStrings.LOG_SYNCING)
                        val result = SyncSetup.syncEngine.flush(SyncSetup.replayClient)
                        refreshCounts()
                        log(
                            AppStrings.LOG_SYNCED_RESULT_PREFIX + result.delivered +
                                AppStrings.LOG_SYNCED_RESULT_DEAD_LETTERED + result.deadLettered +
                                AppStrings.LOG_SYNCED_RESULT_STOPPED_EARLY + result.stoppedEarly,
                            if (result.stoppedEarly) LogKind.Error else LogKind.Success,
                        )
                        isBusy = false
                    }
                },
            ) { Text(AppStrings.SYNC_BUTTON) }
        }

        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) AppStrings.ADVANCED_HIDE else AppStrings.ADVANCED_SHOW)
        }

        if (showAdvanced) {
            AdvancedSection(
                isBusy = isBusy,
                onStressTest = { count ->
                    scope.launch {
                        isBusy = true
                        log(AppStrings.LOG_SENDING_PREFIX + count + AppStrings.LOG_SENDING_SUFFIX)
                        enqueueMutations(count)
                        refreshCounts()
                        log(AppStrings.LOG_SENT_PREFIX + count + AppStrings.LOG_SENT_SUFFIX, LogKind.Success)
                        isBusy = false
                    }
                },
                onKtorfitSend = {
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
            )
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
private fun ServerStatusRow(
    status: ServerHealthStatus,
    controllable: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val (dotColor, label) = when (status) {
        ServerHealthStatus.Checking -> MaterialTheme.colorScheme.onSurfaceVariant to AppStrings.SERVER_CHECKING_LABEL
        ServerHealthStatus.Online -> MaterialTheme.colorScheme.primary to AppStrings.SERVER_ONLINE_LABEL
        ServerHealthStatus.Offline -> MaterialTheme.colorScheme.error to AppStrings.SERVER_OFFLINE_LABEL
    }
    Card {
        Column(modifier = Modifier.padding(AppDimens.CARD_PADDING).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppDimens.CARD_INTERNAL_SPACING),
                ) {
                    Box(modifier = Modifier.size(AppDimens.STATUS_DOT_SIZE).clip(CircleShape).background(dotColor))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
                if (controllable) {
                    Switch(checked = status == ServerHealthStatus.Online, onCheckedChange = { onToggle() }, enabled = enabled)
                }
            }
            if (!controllable) {
                Text(
                    AppStrings.SERVER_EXTERNAL_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
private fun AdvancedSection(isBusy: Boolean, onStressTest: (Int) -> Unit, onKtorfitSend: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AppDimens.CARD_PADDING).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppDimens.CARD_INTERNAL_SPACING),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.BUTTON_SPACING)) {
                Button(enabled = !isBusy, onClick = { onStressTest(AppConstants.DEFAULT_MUTATION_COUNT) }) {
                    Text(AppStrings.STRESS_TEST_BUTTON_PREFIX + AppConstants.DEFAULT_MUTATION_COUNT)
                }
                Button(enabled = !isBusy, onClick = { onStressTest(AppConstants.STRESS_TEST_MUTATION_COUNT) }) {
                    Text(AppStrings.STRESS_TEST_BUTTON_PREFIX + AppConstants.STRESS_TEST_MUTATION_COUNT)
                }
            }
            Button(enabled = !isBusy, onClick = onKtorfitSend) { Text(AppStrings.KTORFIT_DEMO_BUTTON) }
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

/**
 * Fires all [count] requests concurrently, bounded by [AppConstants.ENQUEUE_CONCURRENCY]
 * in-flight at once — not sequentially. The chaos server injects a multi-second delay on a
 * fraction of requests (see `ChaosConstants`); awaiting each request before starting the next
 * would make those delays stack up one after another instead of overlapping, turning a
 * 1,000-request enqueue into a multi-minute wait for no benefit.
 */
private suspend fun enqueueMutations(count: Int) {
    val serverUrl = AppStrings.SERVER_URL_SCHEME + platformServerHost +
        AppStrings.SERVER_URL_PORT_SEPARATOR + SampleApiConstants.DEFAULT_PORT + SampleApiConstants.MUTATIONS_PATH
    val concurrencyLimit = Semaphore(AppConstants.ENQUEUE_CONCURRENCY)

    coroutineScope {
        repeat(count) { index ->
            launch {
                concurrencyLimit.withPermit {
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
                    // A thrown OfflineQueuedException (or any other failure) is expected and
                    // already handled: the request has either been queued by
                    // GhostOfflineQueuePlugin or actually delivered.
                }
            }
        }
    }
}
