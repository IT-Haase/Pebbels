package com.g7monitor.shared.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUUID
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionCriticalAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

// Auf true setzen, SOBALD Apple das Critical-Alerts-Entitlement freigegeben hat und die
// Capability in Xcode aktiviert ist. Vorher bleiben Alarme „zeitkritisch" (kein Rückschritt).
private const val CRITICAL_ENABLED = false

@OptIn(ExperimentalForeignApi::class)
actual fun ensureNotificationPermission() {
    // Ohne Freigabe kein criticalAlert anfragen (iOS würde es ohnehin ignorieren).
    val opts = if (CRITICAL_ENABLED)
        UNAuthorizationOptionAlert or UNAuthorizationOptionSound or
            UNAuthorizationOptionBadge or UNAuthorizationOptionCriticalAlert
    else
        UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
    UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(opts) { _, _ -> }
}

@OptIn(ExperimentalForeignApi::class)
actual fun fireNotification(title: String, body: String, sound: Boolean) {
    val content = UNMutableNotificationContent()
    content.setTitle(title)
    content.setBody(body)
    if (CRITICAL_ENABLED) {
        // Critical Alert: durchbricht Lautlos-Schalter + jeden Fokus, volle Lautstärke.
        content.setInterruptionLevel(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelCritical)
        if (sound) content.setSound(UNNotificationSound.defaultCriticalSound())
    } else {
        // Zeitkritisch: durchbricht Fokus/„Nicht stören", aber Lautlos-Schalter/Lautstärke gelten.
        content.setInterruptionLevel(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelTimeSensitive)
        if (sound) content.setSound(UNNotificationSound.defaultSound())
    }
    val req = UNNotificationRequest.requestWithIdentifier(NSUUID().UUIDString, content, null)
    UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(req, null)
}
