package com.ghostserializer.sync.sample.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Called from Swift (`ComposeApp.MainViewControllerKt.MainViewController()`) — see iosApp/. */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
