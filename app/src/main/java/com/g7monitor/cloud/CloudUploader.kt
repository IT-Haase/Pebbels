/*
 * CloudUploader — schickt die letzten Glukose-Werte an das Pebbels-Dashboard.
 *
 * Zweck:
 *   Damit der User die Werte auch unterwegs sehen kann (ohne BLE-Reichweite
 *   zum Sensor), lädt die App regelmäßig einen Ausschnitt der History auf
 *   einen Webserver. Dort liegt ein PHP-Dashboard, das die Kurve anzeigt.
 *
 * Design:
 *   - Reines HttpURLConnection, keine extra Dependency (OkHttp o. ä.).
 *   - JSON wird von Hand gebaut — gleiches pragmatisches Muster wie im
 *     ReadingsStore. Bei < 100 Punkten ist das völlig ausreichend und
 *     erspart eine Gson/Moshi-Abhängigkeit.
 *   - Komplett synchron/blockierend gedacht: der Aufrufer (G7Repository,
 *     getriggert vom Foreground-Service) ruft upload() auf einem
 *     Hintergrund-Thread / Dispatchers.IO auf.
 *   - Keine Exceptions nach außen — das Ergebnis kommt als UploadResult
 *     zurück, damit die UI einen sauberen Status anzeigen kann.
 */
package com.g7monitor.cloud

import com.g7monitor.ble.ReadingsStore
import com.g7monitor.util.DebugLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object CloudUploader {

    private const val TAG = "CloudUploader"

    /** Ergebnis eines Upload-Versuchs — wird vom Repository in den State
     *  gespiegelt, damit die Einstellungen-UI ihn anzeigen kann. */
    data class UploadResult(
        val ok: Boolean,
        val message: String,
        val sentPoints: Int,
    )

    /**
     * Lädt die übergebenen Punkte hoch.
     *
     * @param url       Voller Endpunkt, z. B. https://sa1.de/pebbels/ingest.php
     * @param uuid      Eindeutige Installations-ID — zugleich Zugriffsschlüssel.
     * @param device    Geräte-Name (erscheint im Dashboard).
     * @param tirLow    Untere Grenze des grünen Normbereichs (für das Web-Chart).
     * @param tirHigh   Obere Grenze des grünen Normbereichs.
     * @param points    Bereits auf das gewünschte Zeitfenster gefilterte Liste.
     */
    fun upload(
        url: String,
        uuid: String,
        device: String,
        tirLow: Int,
        tirHigh: Int,
        points: List<ReadingsStore.Point>,
    ): UploadResult {
        if (url.isBlank()) {
            return UploadResult(false, "keine URL konfiguriert", 0)
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return UploadResult(false, "URL muss mit http:// oder https:// beginnen", 0)
        }
        if (uuid.length != 36) {
            return UploadResult(false, "keine gültige UUID", 0)
        }

        val body = buildJson(uuid, device, tirLow, tirHigh, points)

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            // Antwort lesen (auch der Fehler-Stream, falls vorhanden) — hilft
            // beim Debuggen, wenn der Server z. B. "API-Key falsch" meldet.
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            } ?: ""

            if (code in 200..299) {
                // Erfolg — als sichtbares Event in den Debug-Tab.
                DebugLog.event(TAG, "Daten gesendet: ${points.size} Werte")
                UploadResult(true, "OK — ${points.size} Werte gesendet", points.size)
            } else {
                // Server hat abgelehnt — Fehler MIT konkretem Grund in den Tab.
                val reason = shortReason(resp)
                DebugLog.w(TAG, "Upload abgelehnt: HTTP $code ${reason.ifEmpty { "" }}".trim())
                UploadResult(false, "Server: HTTP $code $reason".trim(), points.size)
            }
        } catch (t: Throwable) {
            // Netzwerkfehler (kein Internet, Timeout, DNS …) — in den Tab.
            val why = t.message ?: t.javaClass.simpleName
            DebugLog.w(TAG, "Upload fehlgeschlagen: $why")
            UploadResult(false, "Netzwerkfehler: $why", points.size)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** Versucht aus der JSON-Antwort des Servers den "error"-Text zu ziehen,
     *  damit die UI etwas Lesbares zeigt statt nur der HTTP-Nummer. */
    private fun shortReason(resp: String): String {
        val key = "\"error\":\""
        val i = resp.indexOf(key)
        if (i < 0) return ""
        val j = resp.indexOf('"', i + key.length)
        if (j < 0) return ""
        return "(${resp.substring(i + key.length, j)})"
    }

    /** Baut den JSON-Body. Punkte-Format identisch zu ReadingsStore:
     *  {"t":<ms>,"g":<mgdl>,"r":<rate>}. */
    private fun buildJson(
        uuid: String,
        device: String,
        tirLow: Int,
        tirHigh: Int,
        points: List<ReadingsStore.Point>,
    ): String {
        val sb = StringBuilder(96 + points.size * 40)
        sb.append('{')
        sb.append("\"uuid\":\"").append(escape(uuid)).append("\",")
        sb.append("\"device\":\"").append(escape(device)).append("\",")
        sb.append("\"sent_at\":").append(System.currentTimeMillis()).append(',')
        sb.append("\"tir_low\":").append(tirLow).append(',')
        sb.append("\"tir_high\":").append(tirHigh).append(',')
        sb.append("\"points\":[")
        for ((idx, p) in points.withIndex()) {
            if (idx > 0) sb.append(',')
            sb.append("{\"t\":").append(p.timeMs)
                .append(",\"g\":").append(p.mgdl)
                .append(",\"r\":").append(fmtRate(p.rateMgdlPerMin))
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Rate als JSON-Zahl mit Punkt als Dezimaltrenner — unabhängig vom
     *  Geräte-Locale (sonst käme bei DE-Locale ein Komma rein → kaputtes JSON). */
    private fun fmtRate(r: Float): String =
        String.format(java.util.Locale.US, "%.2f", r)

    /** Minimales JSON-String-Escaping für die wenigen Felder, die wir senden. */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"'  -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
