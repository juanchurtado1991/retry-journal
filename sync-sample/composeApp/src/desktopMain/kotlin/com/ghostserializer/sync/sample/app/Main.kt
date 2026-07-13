package com.ghostserializer.sync.sample.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = AppConstants.DESKTOP_WINDOW_TITLE) {
        App()
    }
}
