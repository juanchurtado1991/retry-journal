package com.retryjournal.sample.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.retryjournal.engine.FlushResult
import com.retryjournal.sample.app.AppConstants
import com.retryjournal.sample.app.AppStrings
import com.retryjournal.sample.app.FilePicker
import com.retryjournal.sample.app.MockServerController
import com.retryjournal.sample.app.SyncSetup
import com.retryjournal.sample.ui.action.animateChipOnFlushProgress
import com.retryjournal.sample.ui.action.deadLetterCount
import com.retryjournal.sample.ui.action.enqueueSampleMutations
import com.retryjournal.sample.ui.action.probeServerHealth
import com.retryjournal.sample.ui.action.refreshQueueSnapshot
import com.retryjournal.sample.ui.action.uploadPickedFile
import com.retryjournal.sample.ui.model.ActivityLogEntry
import com.retryjournal.sample.ui.model.LogKind
import com.retryjournal.sample.ui.model.QueueChipUiState
import com.retryjournal.sample.ui.model.ServerHealthStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class DemoScreenState(
    private val scope: CoroutineScope,
    private val appStartMark: TimeSource.Monotonic.ValueTimeMark,
) {
    var queueSize by mutableStateOf(0)
        private set

    var deadLetterSize by mutableStateOf(0)
        private set

    var isBusy by mutableStateOf(false)
        private set

    var serverStatus by mutableStateOf(ServerHealthStatus.Checking)
        private set

    var queueChips by mutableStateOf<List<QueueChipUiState>>(emptyList())
        private set

    var headStateLabel by mutableStateOf<String?>(null)
        private set

    val logEntries = mutableStateListOf<ActivityLogEntry>()

    fun log(message: String, kind: LogKind = LogKind.Info) {
        logEntries.add(0, ActivityLogEntry(appStartMark.elapsedNow(), message, kind))
        if (logEntries.size > AppConstants.ACTIVITY_LOG_MAX_ENTRIES) {
            logEntries.removeAt(logEntries.lastIndex)
        }
    }

    suspend fun refreshCounts() {
        val snapshot = refreshQueueSnapshot()
        queueSize = snapshot.pending
        deadLetterSize = deadLetterCount()
        queueChips = snapshot.chips
        headStateLabel = snapshot.headStateLabel
    }

    suspend fun checkServerStatus() {
        serverStatus = probeServerHealth()
    }

    suspend fun onStartup() {
        if (MockServerController.isSupported) {
            MockServerController.start()
        }
        SyncSetup.runtime.start(autoFlushOnOnline = true)
        refreshCounts()
        checkServerStatus()
        log(AppStrings.LOG_APP_READY)
    }

    fun onUploadClick() {
        scope.launch { runUploadFlow() }
    }

    fun onSendMutationsClick() {
        scope.launch { runSendMutationsFlow() }
    }

    fun onSyncClick() {
        scope.launch { runSyncFlow() }
    }

    fun onServerToggleClick() {
        scope.launch { runServerToggleFlow() }
    }

    private suspend fun runUploadFlow() {
        isBusy = true
        val picked = FilePicker.pickFile()
        if (picked != null) {
            log(AppStrings.LOG_UPLOADING_PREFIX + picked.name + AppStrings.LOG_UPLOADING_SUFFIX)
            val (message, kind) = uploadPickedFile(picked)
            refreshCounts()
            log(message, kind)
        }
        isBusy = false
    }

    private suspend fun runSendMutationsFlow() {
        isBusy = true
        log(
            AppStrings.LOG_SENDING_PREFIX + AppConstants.SIMPLE_SEND_COUNT + AppStrings.LOG_SENDING_SUFFIX,
        )
        val diag = enqueueSampleMutations(AppConstants.SIMPLE_SEND_COUNT)
        refreshCounts()
        log(
            AppStrings.LOG_SENT_PREFIX + AppConstants.SIMPLE_SEND_COUNT + AppStrings.LOG_SENT_SUFFIX +
                " [$diag]",
            LogKind.Success,
        )
        // Refresh again after a short delay so the RetryJournalOfflineQueuePlugin's async disk-write
        // has settled and the queue visualization reflects the true state.
        delay(AppConstants.SERVER_TOGGLE_SETTLE_MS.milliseconds)
        refreshCounts()
        isBusy = false
    }

    private suspend fun runSyncFlow() {
        isBusy = true
        log(AppStrings.LOG_SYNCING)
        val result = SyncSetup.runtime.flush { progress ->
            scope.animateChipOnFlushProgress(progress, queueChips) { transform -> queueChips = transform(queueChips) }
        }
        refreshCounts()
        logSyncResult(result)
        isBusy = false
    }

    private suspend fun runServerToggleFlow() {
        isBusy = true
        if (serverStatus == ServerHealthStatus.Online) {
            MockServerController.stop()
            log(AppStrings.SERVER_TURNED_OFF_LOG)
        } else {
            MockServerController.start()
            log(AppStrings.SERVER_TURNED_ON_LOG)
        }
        delay(AppConstants.SERVER_TOGGLE_SETTLE_MS.milliseconds)
        checkServerStatus()
        isBusy = false
    }

    private fun logSyncResult(result: FlushResult) {
        log(
            AppStrings.LOG_SYNCED_RESULT_PREFIX + result.delivered +
                AppStrings.LOG_SYNCED_RESULT_DEAD_LETTERED + result.deadLettered +
                AppStrings.LOG_SYNCED_RESULT_STOPPED_EARLY + result.stoppedEarly,
            if (result.stoppedEarly) LogKind.Error else LogKind.Success,
        )
    }
}
