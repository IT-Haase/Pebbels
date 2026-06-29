/*
 * In-Memory-Log-Store für das Debug-Tab.
 *
 * Zwei Ebenen, bewusst getrennt:
 *
 *  - i()       — ausführliches Detail-Logging (BLE-Handshake, Descriptor-
 *                Writes, Scan-Hits …). Geht NUR nach logcat. Erscheint NICHT
 *                im Debug-Tab. Für tiefes Debugging per `adb logcat`.
 *  - event()   — die wenigen wirklich interessanten Meldungen: neuer Wert,
 *                Verbindung steht, Daten gesendet. Geht nach logcat UND in
 *                den Debug-Tab.
 *  - w() / e() — Warnungen und Fehler. Immer im Debug-Tab sichtbar.
 *
 * Damit bleibt der Debug-Tab übersichtlich (nur Events + Probleme), während
 * das volle Protokoll bei Bedarf weiterhin in logcat liegt.
 *
 * Retention:
 *  - Einträge älter als 24 h werden bei jedem Append verworfen — der Puffer
 *    wächst also nicht unbegrenzt.
 *  - Zusätzlich ein Hard-Cap (MAX_ENTRIES) gegen einen Fehler-Sturm, der in
 *    kurzer Zeit sehr viele Einträge erzeugen könnte.
 *  - Alles rein In-Memory: keine Datei auf der Platte, nichts zum Aufräumen.
 *
 * Synchronized append, weil log() aus beliebigen Threads kommt (Binder,
 * Writer-Handler, Main, IO-Pool …).
 */
package com.g7monitor.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {

    enum class Level { INFO, WARN, ERROR }

    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
    ) {
        /** Zeit als HH:mm:ss.SSS — Sekundengenauigkeit reicht nicht, weil
         *  bei dichten Events der Verlauf sonst nicht nachvollziehbar ist. */
        fun timeString(): String = TIME_FORMAT.format(Date(timestamp))
    }

    /** Hard-Cap gegen Runaway (Fehler-Sturm). Im Normalbetrieb greift die
     *  24-h-Retention lange vorher. */
    private const val MAX_ENTRIES = 2000

    /** Einträge älter als das werden beim Append verworfen. */
    private const val RETENTION_MS = 24L * 3600L * 1000L

    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMAN)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    private val lock = Any()

    /**
     * Detail-Logging — NUR logcat, NICHT im Debug-Tab.
     * Für den ausführlichen BLE-/Crypto-Protokollverlauf. Wer das sehen will,
     * nutzt `adb logcat`. Der Debug-Tab bleibt dadurch übersichtlich.
     */
    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    /**
     * Wichtiges Ereignis — logcat UND Debug-Tab.
     * Gedacht für die handvoll Meldungen, die der User wirklich sehen will:
     * "Neuer Wert: 100 mg/dL", "Verbindung OK", "Daten gesendet" …
     */
    fun event(tag: String, msg: String) {
        Log.i(tag, msg)
        append(Level.INFO, tag, msg)
    }

    /** Warnung — gelb hervorgehoben im Debug-Tab. */
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append(Level.WARN, tag, msg)
    }

    /** Warnung MIT Throwable — entspricht Log.w(tag, msg, t). Existiert
     *  damit Aufrufstellen, die zusätzlich eine Exception loggen, ohne
     *  Compile-Fehler bleiben. */
    fun w(tag: String, msg: String, t: Throwable?) {
        if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
        val full = if (t != null) "$msg — ${t.javaClass.simpleName}: ${t.message}" else msg
        append(Level.WARN, tag, full)
    }

    /** Fehler — rot dargestellt im Debug-Tab. */
    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        val full = if (t != null) "$msg — ${t.javaClass.simpleName}: ${t.message}" else msg
        append(Level.ERROR, tag, full)
    }

    /** Komplette Historie löschen — vom UI-Button im Debug-Tab gerufen. */
    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
        }
    }

    private fun append(level: Level, tag: String, msg: String) {
        val now = System.currentTimeMillis()
        val entry = LogEntry(now, level, tag, msg)
        synchronized(lock) {
            val cutoff = now - RETENTION_MS
            // Erst alles jenseits der 24-h-Grenze rauswerfen, dann anhängen.
            var next = _entries.value.filter { it.timestamp >= cutoff } + entry
            // Hard-Cap: bei einem Fehler-Sturm könnte die 24-h-Liste sehr
            // lang werden — die jüngsten MAX_ENTRIES behalten.
            if (next.size > MAX_ENTRIES) {
                next = next.subList(next.size - MAX_ENTRIES, next.size)
            }
            _entries.value = next
        }
    }
}
