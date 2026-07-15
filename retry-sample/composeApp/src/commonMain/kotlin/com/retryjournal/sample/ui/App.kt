package com.retryjournal.sample.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retryjournal.sample.ui.screen.DemoScreen
import com.retryjournal.sample.ui.theme.AppTheme

/** Root composable for all platforms — theme shell only; screen lives under [ui.screen]. */
@Composable
fun App() {
    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            DemoScreen()
        }
    }
}
