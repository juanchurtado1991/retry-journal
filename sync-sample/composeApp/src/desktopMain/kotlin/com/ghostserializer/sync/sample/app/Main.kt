package com.ghostserializer.sync.sample.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ghostserializer.sync.sample.ui.App
import com.ghostserializer.sync.sample.ui.theme.AppDimens

fun main() = application {
    val windowState = rememberWindowState(
        size = DpSize(AppDimens.DESKTOP_WINDOW_WIDTH, AppDimens.DESKTOP_WINDOW_HEIGHT),
    )
    Window(onCloseRequest = ::exitApplication, title = AppConstants.DESKTOP_WINDOW_TITLE, state = windowState) {
        App()
    }
}
