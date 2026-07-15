package com.retryjournal.sample.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retryjournal.sample.app.FilePicker
import com.retryjournal.sample.app.MockServerController
import com.retryjournal.sample.ui.components.actions.DemoActionBar
import com.retryjournal.sample.ui.components.actions.FilePickerUnsupportedHint
import com.retryjournal.sample.ui.components.header.DemoHeader
import com.retryjournal.sample.ui.components.log.ActivityLogCard
import com.retryjournal.sample.ui.components.queue.QueueVisualization
import com.retryjournal.sample.ui.components.server.ServerStatusRow
import com.retryjournal.sample.ui.components.stats.StatsRow
import com.retryjournal.sample.ui.effects.DemoScreenEffects
import com.retryjournal.sample.ui.state.rememberDemoScreenState
import com.retryjournal.sample.ui.theme.AppDimens

/**
 * Demo screen — chaos server toggle, queue chips, and activity log. Request capture goes through
 * [com.retryjournal.sample.app.MutationApi] (Ktorfit); replay goes through
 * [com.retryjournal.sample.app.SyncSetup.runtime].
 */
@Composable
internal fun DemoScreen() {
    val state = rememberDemoScreenState()
    DemoScreenEffects(state)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppDimens.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(AppDimens.SECTION_SPACING),
    ) {
        DemoHeader()

        ServerStatusRow(
            status = state.serverStatus,
            controllable = MockServerController.isSupported,
            enabled = !state.isBusy,
            onToggle = state::onServerToggleClick,
        )

        StatsRow(
            pending = state.queueSize,
            deadLettered = state.deadLetterSize,
            headStateLabel = state.headStateLabel,
        )

        QueueVisualization(chips = state.queueChips)

        DemoActionBar(
            uploadEnabled = !state.isBusy && FilePicker.isSupported,
            sendEnabled = !state.isBusy,
            syncEnabled = !state.isBusy,
            onUploadClick = state::onUploadClick,
            onSendClick = state::onSendMutationsClick,
            onSyncClick = state::onSyncClick,
        )

        FilePickerUnsupportedHint()

        ActivityLogCard(entries = state.logEntries)
    }
}
