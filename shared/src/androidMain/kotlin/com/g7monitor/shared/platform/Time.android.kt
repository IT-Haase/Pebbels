package com.g7monitor.shared.platform

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

private val clockFmt = SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault())
actual fun formatLocalTime(ms: Long): String = clockFmt.format(Date(ms))
