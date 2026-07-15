package com.retryjournal.sample.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.retryjournal.sample.app.AppConstants
import com.retryjournal.sample.app.SyncSetup
import com.retryjournal.sample.ui.state.DemoScreenState
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun DemoScreenEffects(state: DemoScreenState) {
    LaunchedEffect(Unit) {
        state.onStartup()
    }

    DisposableEffect(Unit) {
        onDispose { SyncSetup.runtime.stop() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(AppConstants.SERVER_HEALTH_POLL_INTERVAL_MS.milliseconds)
            state.checkServerStatus()
            state.refreshCounts()
        }
    }
}
