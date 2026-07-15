package com.retryjournal.sample.app

import androidx.compose.ui.window.ComposeUIViewController
import com.retryjournal.sample.ui.App
import platform.UIKit.UIViewController

/** Called from Swift (`ComposeApp.MainViewControllerKt.MainViewController()`) — see iosApp/. */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
