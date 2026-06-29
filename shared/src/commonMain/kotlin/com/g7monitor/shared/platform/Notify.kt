package com.g7monitor.shared.platform

/** Lokale Benachrichtigung (plattformspezifisch). iOS: UserNotifications. */
expect fun fireNotification(title: String, body: String, sound: Boolean)
expect fun ensureNotificationPermission()
