package com.ghostserializer.sync.sample.app

import androidx.compose.ui.window.ComposeUIViewController
import com.ghostserializer.sync.sample.ui.App
import platform.UIKit.UIViewController

/** Called from Swift (`ComposeApp.MainViewControllerKt.MainViewController()`) — see iosApp/. */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
