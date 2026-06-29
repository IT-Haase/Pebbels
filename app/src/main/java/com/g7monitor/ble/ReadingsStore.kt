/*
 * Glukose-Verlauf auf Disk — JSONL, append-only, Cutoff 28 Tage.
 *
 * Wir schreiben pro Reading eine Zeile à
 *     {"t":1713800000000,"g":124,"r":0.4}
 * (Millisekunden-Timestamp, mg/dL, Rate mg/dL/min). Beim App-Start wird die
 * Datei in eine In-Memory-Liste geladen und auf die letzten 28 Tage gekürzt —
 * das deckt zwei komplette Extended-Use-Sessions (je ~21 d) ab. So sieht der
 * User auch einen Sensor-Wechsel mit sauberem Übergang im Chart.
 *
 * Bewusst simpel: keine SQL-Datenbank, keine Protobufs — bei 288 Werten pro Tag
 * ist selbst 28 Tage Historie nur ~120 KB.
 */
package com.g7monitor.ble

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

class ReadingsStore(private val ctx: Context) {

    // sensor: "dexcom" | "aidex" — kennzeichnet, von welchem Sensor der Wert
    // stammt, damit beide getrennt als eigene Linie gezeichnet (und einzeln
    // gelöscht) werden können. Altdaten ohne Tag werden als "dexcom" geladen.
    data class Point(
        val timeMs: Long,
        val mgdl: Int,
        val rateMgdlPerMin: Float,
        val sensor: String = "dexcom",
    )

    private val tag = "ReadingsStore"
    private val file: File get() = File(ctx.filesDir, "g7_readings.jsonl")
    private val cutoffMs get() = System.currentTimeMillis() - RETENTION_MS

    /** Alle Werte der letzten 28 Tage laden. */
    fun load(): List<Point> {
        val f = file
        if (!f.exists()) return emptyList()
        return try {
            val out = ArrayList<Point>(512)
            f.forEachLine { line ->
                parseLine(line)?.let { if (it.timeMs >= cutoffMs) out.add(it) }
            }
            out.sortedBy { it.timeMs }
        } catch (t: Throwable) {
            Log.w(tag, "load() fehlgeschlagen", t); emptyList()
        }
    }

    /** Einen Wert anhängen. Duplikate (gleiche timeMs) werden ignoriert — der
     *  Sensor kann beim Backfill dieselbe Minute erneut liefern. */
    fun append(p: Point, knownTimes: Set<Long>): Boolean {
        if (p.timeMs in knownTimes) return false
        return try {
            val line = """{"t":${p.timeMs},"g":${p.mgdl},"r":${"%.2f".format(p.rateMgdlPerMin).replace(',', '.')},"s":"${p.sensor}"}""" + "\n"
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(raf.length())
                raf.write(line.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (t: Throwable) {
            Log.w(tag, "append() fehlgeschlagen", t); false
        }
    }

    /** Alte Einträge wegwerfen (nur wenn > ~10 % der Datei veraltet sind,
     *  damit wir nicht bei jedem Wert die Datei neu schreiben). */
    fun compactIfNeeded() {
        val f = file
        if (!f.exists() || f.length() < 4096) return
        try {
            val kept = ArrayList<String>(512)
            var dropped = 0
            f.forEachLine { line ->
                val p = parseLine(line)
                if (p != null && p.timeMs >= cutoffMs) kept.add(line) else dropped++
            }
            if (dropped == 0) return
            if (dropped.toDouble() / (dropped + kept.size) < 0.1) return
            f.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
            Log.i(tag, "compact: $dropped alte Werte entfernt, ${kept.size} bleiben")
        } catch (t: Throwable) {
            Log.w(tag, "compactIfNeeded() fehlgeschlagen", t)
        }
    }

    fun clear() { try { file.delete() } catch (_: Throwable) {} }

    /** Nur die Werte EINES Sensors ("dexcom"/"aidex") löschen — die Datei wird
     *  ohne die betroffenen Zeilen neu geschrieben. Der andere Sensor bleibt
     *  unberührt. */
    fun clearSensor(sensor: String) {
        val f = file
        if (!f.exists()) return
        try {
            val kept = ArrayList<String>(512)
            f.forEachLine { line ->
                val p = parseLine(line) ?: return@forEachLine
                if (p.sensor != sensor) kept.add(line)
            }
            if (kept.isEmpty()) f.delete()
            else f.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
            Log.i(tag, "clearSensor($sensor): ${kept.size} Werte bleiben")
        } catch (t: Throwable) {
            Log.w(tag, "clearSensor() fehlgeschlagen", t)
        }
    }

    /**
     * Export: schreibt die komplette Glukose-Historie als JSONL in den Stream.
     * Eine Zeile pro Messwert — dasselbe Format wie die interne Datei, also
     * direkt wieder importierbar.
     * @return Anzahl exportierter Werte.
     */
    fun exportTo(out: OutputStream): Int {
        val f = file
        if (!f.exists()) return 0
        var count = 0
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            f.forEachLine { line ->
                if (parseLine(line) != null) {
                    w.write(line)
                    w.write("\n")
                    count++
                }
            }
        }
        return count
    }

    /**
     * Import: liest JSONL aus dem Stream und fügt die Werte in den Store ein.
     * Duplikate (gleiche timeMs, bereits in [knownTimes]) werden übersprungen.
     * @return Liste der NEU hinzugefügten Punkte (für die In-Memory-History).
     */
    fun importFrom(input: InputStream, knownTimes: MutableSet<Long>): List<Point> {
        val added = ArrayList<Point>()
        try {
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val p = parseLine(line)
                    if (p != null && p.timeMs !in knownTimes) {
                        // append() schreibt in die Datei und prüft selbst auf
                        // Duplikate; bei Erfolg den Punkt vormerken.
                        if (append(p, knownTimes)) {
                            knownTimes.add(p.timeMs)
                            added.add(p)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(tag, "importFrom() fehlgeschlagen", t)
        }
        return added
    }

    private fun parseLine(line: String): Point? {
        // Minimaler JSON-Parser für genau unser Format — keine Abhängigkeit zu
        // Gson/Moshi nötig.
        val t = extractLong(line, "\"t\":") ?: return null
        val g = extractLong(line, "\"g\":")?.toInt() ?: return null
        val r = extractDouble(line, "\"r\":") ?: 0.0
        // Sensor-Tag; fehlt er (Altdaten), gilt der Wert als "dexcom".
        val s = extractString(line, "\"s\":") ?: "dexcom"
        return Point(t, g, r.toFloat(), s)
    }

    private fun extractString(s: String, key: String): String? {
        val i = s.indexOf(key); if (i < 0) return null
        val q1 = s.indexOf('"', i + key.length); if (q1 < 0) return null
        val q2 = s.indexOf('"', q1 + 1); if (q2 < 0) return null
        return s.substring(q1 + 1, q2)
    }

    private fun extractLong(s: String, key: String): Long? {
        val i = s.indexOf(key); if (i < 0) return null
        var j = i + key.length
        while (j < s.length && (s[j] == ' ' || s[j] == '-' || s[j].isDigit())) j++
        return s.substring(i + key.length, j).trim().toLongOrNull()
    }

    private fun extractDouble(s: String, key: String): Double? {
        val i = s.indexOf(key); if (i < 0) return null
        var j = i + key.length
        while (j < s.length && (s[j] == ' ' || s[j] == '-' || s[j] == '.' || s[j].isDigit())) j++
        return s.substring(i + key.length, j).trim().toDoubleOrNull()
    }

    companion object {
        /** 28 Tage Aufbewahrung — deckt Extended-Use (bis ~21 d) plus einen
         *  angefangenen Nachfolgesensor ab. */
        private const val RETENTION_MS = 28L * 24L * 3600L * 1000L
    }
}
