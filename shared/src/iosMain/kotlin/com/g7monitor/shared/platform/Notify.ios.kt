package com.g7monitor.shared.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUUID
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

@OptIn(ExperimentalForeignApi::class)
actual fun ensureNotificationPermission() {
    UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
        UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
    ) { _, _ -> }
}

@OptIn(ExperimentalForeignApi::class)
actual fun fireNotification(title: String, body: String, sound: Boolean) {
    val content = UNMutableNotificationContent()
    content.setTitle(title)
    content.setBody(body)
    if (sound) content.setSound(UNNotificationSound.defaultSound())
    val req = UNNotificationRequest.requestWithIdentifier(NSUUID().UUIDString, content, null)
    UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(req, null)
}
