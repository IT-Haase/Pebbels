package com.g7monitor.shared.platform

// Platzhalter: die Android-App hat ihren eigenen HypoAlarm. Sobald :app auf
// :shared umzieht, kommt hier die echte NotificationManager-Anbindung rein.
actual fun fireNotification(title: String, body: String, sound: Boolean) {}
actual fun ensureNotificationPermission() {}
