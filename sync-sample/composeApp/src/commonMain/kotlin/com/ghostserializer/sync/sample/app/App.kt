package com.ghostserializer.sync.sample.app

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.LazyRow
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
import com.ghostserializer.sync.client.OfflineQueuedException
import com.ghostserializer.sync.engine.FlushProgress
import com.ghostserializer.sync.queue.QueueEntryId
import com.ghostserializer.sync.sample.shared.MutationRequest
import com.ghostserializer.sync.sample.shared.SampleApiConstants
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
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
 * [QueueVisualization] shows up to [AppConstants.MAX_VISUALIZED_QUEUE_ITEMS] pending requests as
 * small chips: gray while queued, briefly flashing green (delivered) or red (dead-lettered) as
 * `Sync now` actually resolves each one — driven by [com.ghostserializer.sync.engine.FlushProgress],
 * not a simulated animation.
 *
 * Every request here — the upload and the JSON mutation alike — goes through [MutationApi], a
 * Ktorfit-generated interface; see its own doc for why that's a demo choice, not something
 * `:ghost-sync` requires.
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
    var serverStatus by remember { mutableStateOf(ServerHealthStatus.Checking) }
    var queueChips by remember { mutableStateOf<List<QueueChipUiState>>(emptyList()) }
    val logEntries = remember { mutableStateListOf<ActivityLogEntry>() }

    fun log(message: String, kind: LogKind = LogKind.Info) {
        logEntries.add(0, ActivityLogEntry(appStartMark.elapsedNow(), message, kind))
        if (logEntries.size > AppConstants.ACTIVITY_LOG_MAX_ENTRIES) {
            logEntries.removeAt(logEntries.lastIndex)
        }
    }

    suspend fun refreshCounts() {
        queueSize = SyncSetup.diskQueue.size()
        deadLetterSize = SyncSetup.deadLetterQueue.size()
        val pendingIds = ArrayList<QueueEntryId>(AppConstants.MAX_VISUALIZED_QUEUE_ITEMS)
        SyncSetup.diskQueue.peekIds(AppConstants.MAX_VISUALIZED_QUEUE_ITEMS, pendingIds)
        queueChips = pendingIds.map { QueueChipUiState(it, ChipStatus.Pending) }
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

        QueueVisualization(queueChips)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.BUTTON_SPACING)) {
                Button(
                    enabled = !isBusy && FilePicker.isSupported,
                    onClick = {
                        scope.launch {
                            isBusy = true
                            val picked = FilePicker.pickFile()
                            if (picked != null) {
                                log(AppStrings.LOG_UPLOADING_PREFIX + picked.name + AppStrings.LOG_UPLOADING_SUFFIX)
                                val (message, kind) = uploadFile(picked)
                                refreshCounts()
                                log(message, kind)
                            }
                            isBusy = false
                        }
                    },
                ) { Text(AppStrings.UPLOAD_BUTTON) }

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
            }

            Button(
                enabled = !isBusy,
                onClick = {
                    scope.launch {
                        isBusy = true
                        log(AppStrings.LOG_SYNCING)
                        val result = SyncSetup.syncEngine.flush(SyncSetup.replayClient) { progress ->
                            val (id, status) = when (progress) {
                                is FlushProgress.Delivered -> progress.id to ChipStatus.Delivered
                                is FlushProgress.DeadLettered -> progress.id to ChipStatus.DeadLettered
                            }
                            // Only chips actually on screen (capped at MAX_VISUALIZED_QUEUE_ITEMS)
                            // animate at all. Updating the status here is instant — flush() moves
                            // on to the next entry immediately, at full real speed. The brief
                            // colored pause before the chip disappears is scheduled on its own
                            // fire-and-forget coroutine below, not awaited here, so the demo's
                            // animation never throttles the actual sync.
                            if (queueChips.any { it.id == id }) {
                                queueChips = queueChips.map { if (it.id == id) it.copy(status = status) else it }
                                scope.launch {
                                    delay(AppConstants.SYNC_ANIMATION_STEP_DELAY_MS)
                                    queueChips = queueChips.filterNot { it.id == id }
                                }
                            }
                        }
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

        if (!FilePicker.isSupported) {
            Text(
                AppStrings.FILE_PICKER_UNSUPPORTED_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** One chip per pending request (capped, see [AppConstants.MAX_VISUALIZED_QUEUE_ITEMS]) — gray
 * while queued, flashing green/red as [FlushProgress] resolves it during `Sync now`, then gone. */
@Composable
private fun QueueVisualization(chips: List<QueueChipUiState>) {
    if (chips.isEmpty()) {
        return
    }
    LazyRow(
        modifier = Modifier.height(AppDimens.QUEUE_CHIP_SIZE),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.QUEUE_CHIP_SPACING),
    ) {
        items(chips, key = { it.id.sequenceId }) { chip ->
            QueueChip(chip, modifier = Modifier.animateItem())
        }
    }
}

@Composable
private fun QueueChip(chip: QueueChipUiState, modifier: Modifier = Modifier) {
    val targetColor = when (chip.status) {
        ChipStatus.Pending -> MaterialTheme.colorScheme.surfaceVariant
        ChipStatus.Delivered -> MaterialTheme.colorScheme.primary
        ChipStatus.DeadLettered -> MaterialTheme.colorScheme.error
    }
    val color by animateColorAsState(targetColor)
    Box(modifier = modifier.size(AppDimens.QUEUE_CHIP_SIZE).clip(CircleShape).background(color))
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

/** Posts a real multipart file upload through [SyncSetup.mutationApi] (Ktorfit) — proves
 * [com.ghostserializer.sync.client.GhostOfflineQueuePlugin] captures a
 * [io.ktor.http.content.OutgoingContent.WriteChannelContent] body (what a multipart upload
 * actually is) correctly, not just a typed DTO, no matter which layer built the request.
 * [OfflineQueuedException] means the server was unreachable and the file is now sitting in the
 * queue, not lost. */
private suspend fun uploadFile(file: PickedFile): Pair<String, LogKind> = try {
    SyncSetup.mutationApi.uploadFile(
        formData {
            append(
                AppStrings.UPLOAD_FORM_FIELD_NAME,
                file.bytes,
                Headers.build {
                    append(
                        HttpHeaders.ContentDisposition,
                        AppStrings.UPLOAD_CONTENT_DISPOSITION_PREFIX + file.name + AppStrings.UPLOAD_CONTENT_DISPOSITION_SUFFIX,
                    )
                },
            )
        },
    )
    (AppStrings.LOG_UPLOAD_DELIVERED_PREFIX + file.name + AppStrings.LOG_UPLOAD_DELIVERED_SUFFIX) to LogKind.Success
} catch (e: OfflineQueuedException) {
    (AppStrings.LOG_UPLOAD_QUEUED_PREFIX + file.name + AppStrings.LOG_UPLOAD_QUEUED_SUFFIX) to LogKind.Info
}

/**
 * Fires all [count] requests concurrently through [SyncSetup.mutationApi] (Ktorfit), bounded by
 * [AppConstants.ENQUEUE_CONCURRENCY] in-flight at once — not sequentially. The chaos server
 * injects a multi-second delay on a fraction of requests (see `ChaosConstants`); awaiting each
 * request before starting the next would make those delays stack up one after another instead of
 * overlapping.
 */
private suspend fun enqueueMutations(count: Int) {
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
                    runCatching { SyncSetup.mutationApi.createMutation(request) }
                    // A thrown OfflineQueuedException (or any other failure) is expected and
                    // already handled: the request has either been queued by
                    // GhostOfflineQueuePlugin or actually delivered.
                }
            }
        }
    }
}
