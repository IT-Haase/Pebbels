/*
 * Pebbels Shared Core (KMP) — Wanduhr in ms seit Epoch (plattformspezifisch).
 * Android → System.currentTimeMillis(), iOS → NSDate.
 */
package com.g7monitor.shared.platform

expect fun currentTimeMillis(): Long

/** Lokale Zeit als "dd.MM. HH:mm" (für den Medikamenten-Log). */
expect fun formatLocalTime(ms: Long): String
