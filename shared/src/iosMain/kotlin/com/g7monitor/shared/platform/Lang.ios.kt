package com.g7monitor.shared.platform

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

actual fun systemLanguage(): String {
    val first = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "de"
    return first.take(2)
}
