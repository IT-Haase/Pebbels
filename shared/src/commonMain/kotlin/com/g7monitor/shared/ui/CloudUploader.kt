package com.g7monitor.shared.ui

import com.g7monitor.shared.platform.currentTimeMillis
import com.g7monitor.shared.platform.httpPostJson

/** Lädt die letzten Werte zum Pebbels-Dashboard (gleiches JSON-Format wie die
 *  Android-App: uuid/device/tir/points). Reines String-JSON, HTTP per expect. */
object CloudUploader {
    private const val INGEST_URL = "https://sa1.de/pebbels/ingest.php"
    private var lastMs = 0L
    var enabled = true   // auf Android aus: dort lädt die Engine hoch

    fun uploadNow() { lastMs = 0L; maybeUpload() }   // sofort senden (z. B. beim Einschalten)

    fun maybeUpload() {
        if (!enabled || !AppState.uploadEnabled) return
        val uuid = AppState.cloudUuid
        if (uuid.length != 36) return
        val now = currentTimeMillis()
        if (now - lastMs < 60_000L) return          // höchstens 1×/Minute
        val pts = AppState.history.takeLast(288)     // letzte ~24 h
        if (pts.isEmpty()) return
        lastMs = now
        val body = buildJson(uuid, AppState.deviceName ?: "Pebbels", AppState.tirLow, AppState.tirHigh, now, pts)
        httpPostJson(INGEST_URL, body) { ok, info ->
            AppState.pushLog(if (ok) "Cloud: ${pts.size} Werte gesendet" else "Cloud: $info")
        }
    }

    private fun buildJson(uuid: String, device: String, tirLow: Int, tirHigh: Int, sentAt: Long, pts: List<GlucosePoint>): String {
        val sb = StringBuilder(96 + pts.size * 32)
        sb.append('{')
        sb.append("\"uuid\":\"").append(esc(uuid)).append("\",")
        sb.append("\"device\":\"").append(esc(device)).append("\",")
        sb.append("\"sent_at\":").append(sentAt).append(',')
        sb.append("\"tir_low\":").append(tirLow).append(',')
        sb.append("\"tir_high\":").append(tirHigh).append(',')
        sb.append("\"points\":[")
        for ((i, p) in pts.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"t\":").append(p.t).append(",\"g\":").append(p.v).append(",\"r\":").append(fmt2(p.r)).append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Rate mit Punkt als Dezimaltrenner (locale-unabhängig), 2 Nachkommastellen. */
    private fun fmt2(f: Float): String {
        val neg = f < 0f
        val x = (kotlin.math.abs(f) * 100f + 0.5f).toInt()
        val s = "${x / 100}.${(x % 100).toString().padStart(2, '0')}"
        return if (neg && x != 0) "-$s" else s
    }
    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n")
            '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }
}
