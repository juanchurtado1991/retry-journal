package com.ghostserializer.sync.sample.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ghostserializer.sync.sample.app.FilePicker
import com.ghostserializer.sync.sample.app.MockServerController
import com.ghostserializer.sync.sample.ui.components.actions.DemoActionBar
import com.ghostserializer.sync.sample.ui.components.actions.FilePickerUnsupportedHint
import com.ghostserializer.sync.sample.ui.components.header.DemoHeader
import com.ghostserializer.sync.sample.ui.components.log.ActivityLogCard
import com.ghostserializer.sync.sample.ui.components.queue.QueueVisualization
import com.ghostserializer.sync.sample.ui.components.server.ServerStatusRow
import com.ghostserializer.sync.sample.ui.components.stats.StatsRow
import com.ghostserializer.sync.sample.ui.effects.DemoScreenEffects
import com.ghostserializer.sync.sample.ui.state.rememberDemoScreenState
import com.ghostserializer.sync.sample.ui.theme.AppDimens

/**
 * Demo screen — chaos server toggle, queue chips, and activity log. Request capture goes through
 * [com.ghostserializer.sync.sample.app.MutationApi] (Ktorfit); replay goes through
 * [com.ghostserializer.sync.sample.app.SyncSetup.runtime].
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

        StatsRow(pending = state.queueSize, deadLettered = state.deadLetterSize)

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
