package com.g7monitor.shared.platform

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

private val clockFmt = NSDateFormatter().apply { dateFormat = "dd.MM. HH:mm" }
actual fun formatLocalTime(ms: Long): String =
    clockFmt.stringFromDate(NSDate.dateWithTimeIntervalSince1970(ms / 1000.0))
