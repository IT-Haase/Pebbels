package com.g7monitor.shared.ui

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

// iOS-Einstiegspunkt: hostet die geteilte Compose-UI in einem UIViewController.
fun MainViewController(): UIViewController = ComposeUIViewController { PebbelsApp() }
