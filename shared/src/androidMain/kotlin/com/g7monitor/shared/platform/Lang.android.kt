package com.g7monitor.shared.platform

actual fun systemLanguage(): String = java.util.Locale.getDefault().language
