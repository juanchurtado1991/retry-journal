package com.retryjournal.sample.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.time.TimeSource

@Composable
internal fun rememberDemoScreenState(): DemoScreenState {
    val scope = rememberCoroutineScope()
    val appStartMark = remember { TimeSource.Monotonic.markNow() }
    return remember(scope, appStartMark) { DemoScreenState(scope, appStartMark) }
}
