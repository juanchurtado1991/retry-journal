package com.ghostserializer.sync.sample.ui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.ghostserializer.sync.sample.app.AppConstants
import com.ghostserializer.sync.sample.app.SyncSetup
import com.ghostserializer.sync.sample.ui.state.DemoScreenState
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
