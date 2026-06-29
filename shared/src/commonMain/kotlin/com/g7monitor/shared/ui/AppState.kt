package com.g7monitor.shared.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class GlucosePoint(val t: Long, val v: Int, val r: Float = 0f)

/** Eine verabreichte Medikamentengabe (Zeitstempel, Name, Dosis). */
data class MedEvent(val t: Long, val name: String, val dose: String)
/** Konfigurierbares Medikament mit Schnellwahl-Dosen. */
data class MedKind(val name: String, val doses: List<String>)

/**
 * Geteilter UI-Zustand (Compose-observable) — entspricht G7State + SettingsState
 * der Android-App. Wird vom Plattform-Treiber gefüttert (iOS: G7Ble,
 * Android: ViewModel) und von der gemeinsamen Compose-UI gelesen/geschrieben.
 */
object AppState {
    // Verbindung / Glukose
    var status by mutableStateOf("Bereit")
    var connected by mutableStateOf(false)
    var active by mutableStateOf(false)   // Vorgang läuft (Start→Stop): steuert den Start/Stop-Knopf
    var themeMode by mutableStateOf("")   // "" = System, "light", "dark"
    var live by mutableStateOf(false)
    var lastGlucose by mutableStateOf<Int?>(null)
    var lastGlucoseAt by mutableStateOf(0L)
    var rate by mutableStateOf<Double?>(null)
    var deviceName by mutableStateOf<String?>(null)
    var deviceAddress by mutableStateOf<String?>(null)
    var handshakePhase by mutableStateOf(-1)
    var pin by mutableStateOf("")
    var backfillCount by mutableStateOf(0)
    var sensorStartMs by mutableStateOf(0L)   // Sensor-Start (für Ablauf-Hinweis auf der Startseite); 0 = unbekannt
    val history = mutableStateListOf<GlucosePoint>()
    val log = mutableStateListOf<String>()
    fun pushLog(s: String) { log.add(s); if (log.size > 300) log.removeAt(0) }

    // Sprache ("", "de", "en", "es")
    var language by mutableStateOf("")

    // Statistik / TIR
    var windowHours by mutableStateOf(24)
    var statsRangeHours by mutableStateOf(24)
    var tirLow by mutableStateOf(60)
    var tirHigh by mutableStateOf(160)

    // Alarme
    var hypoEnabled by mutableStateOf(true)
    var hypoThreshold by mutableStateOf(50)
    var hyperEnabled by mutableStateOf(false)
    var hyperThreshold by mutableStateOf(200)
    var alarmRepeatMin by mutableStateOf(10)
    var alarmSound by mutableStateOf(true)
    var alarmVibrate by mutableStateOf(true)

    // Kalibrierung
    var calibOffset by mutableStateOf(0)

    // Cloud-Upload (Pebbels-Dashboard)
    var uploadEnabled by mutableStateOf(false)
    var cloudUuid by mutableStateOf("")
    fun dashboardUrl(): String = "https://sa1.de/pebbels/dash_board_pebbels.php?id=$cloudUuid"

    // --- Medikamente ---
    val medKinds = mutableStateListOf<MedKind>()      // pflegbare Liste (Name + Standard-Dosen)
    val medEvents = mutableStateListOf<MedEvent>()    // chronologisch geloggte Gaben
    fun seedMedKindsIfEmpty() {
        if (medKinds.isEmpty()) {
            // Neutrale Demo-Ereignisse (kein Medikament/keine Dosis) — für den App-Review
            // unbedenklich. Eigene Einträge legt der Nutzer selbst über „verwalten" an.
            medKinds.add(MedKind("Hauptfutter", emptyList()))
            medKinds.add(MedKind("Leckerlie", emptyList()))
        }
    }
    fun addMed(name: String, dose: String, atMs: Long) {
        if (name.isBlank()) return
        medEvents.add(MedEvent(atMs, name.trim(), dose.trim()))
        val s = medEvents.sortedBy { it.t }; medEvents.clear(); medEvents.addAll(s)
        Persistence.saveMeds()
    }
    fun removeMed(e: MedEvent) { medEvents.remove(e); Persistence.saveMeds() }
    fun replaceMedKinds(list: List<MedKind>) { medKinds.clear(); medKinds.addAll(list); Persistence.saveMedKinds() }

    // Aktionen (Plattform setzt diese)
    var onConnect: () -> Unit = {}
    var onDisconnect: () -> Unit = {}
    var onHardReset: () -> Unit = {}
    var onCalibrate: (Int) -> Unit = {}
    var onClearHistory: () -> Unit = {}
    var onAlarmTest: () -> Unit = {}
    var onOpenUrl: (String) -> Unit = {}
    var onShareUrl: (String) -> Unit = {}
    var onExport: () -> Unit = {}
    var onImport: () -> Unit = {}

    // --- vom Plattform-Treiber aufgerufen ---
    fun pushLive(mgdl: Int, rate: Double, atMs: Long) {
        this.lastGlucose = mgdl; this.rate = rate; this.lastGlucoseAt = atMs; this.live = true
        history.add(GlucosePoint(atMs, mgdl, rate.toFloat()))
        CloudUploader.maybeUpload()
    }
    fun pushBackfill(atMs: Long, v: Int, r: Float = 0f) { history.add(GlucosePoint(atMs, v, r)); backfillCount += 1 }
    /** Sensor-Startzeit setzen (von der Plattform) — nur bei echter Änderung + persistieren. */
    fun updateSensorStart(ms: Long) {
        if (ms > 0L && ms != sensorStartMs) { sensorStartMs = ms; Persistence.saveSensorStart() }
    }
    fun sortHistory() { val seen = HashSet<Long>(); val s = history.sortedBy { it.t }.filter { seen.add(it.t) }; history.clear(); history.addAll(s) }
    fun clearHistory() { history.clear() }

    /** Verlauf als JSON exportieren: [{"t":<ms>,"g":<mgdl>}, …] — inkl. Medikamente. */
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun medJson(e: MedEvent) = "{\"t\":${e.t},\"m\":\"${esc(e.name)}\",\"d\":\"${esc(e.dose)}\"}"
    fun exportJson(): String = buildString {
        append("[")
        var first = true
        history.forEach { p -> if (!first) append(","); first = false; append("{\"t\":").append(p.t).append(",\"g\":").append(p.v).append("}") }
        medEvents.forEach { e -> if (!first) append(","); first = false; append(medJson(e)) }
        append("]")
    }
    /** Für Android: Medikamente ans Engine-Backup anhängen (eigener Trenner, damit
     *  der JSONL-Import der Engine nicht stolpert). */
    fun medBackupSuffix(): String =
        if (medEvents.isEmpty()) "" else "\n#PEBBELS_MEDS\n" + medEvents.joinToString("\n") { medJson(it) }
    /** Medikamente aus einem Backup-Text einlesen (Format-egal, per Zeitstempel+Name dedupliziert). */
    fun importMeds(s: String): Int {
        val seen = HashSet<String>(); medEvents.forEach { seen.add("${it.t}|${it.name}") }
        var n = 0
        Regex("\\{\"t\":(\\d+),\"m\":\"((?:\\\\.|[^\"\\\\])*)\",\"d\":\"((?:\\\\.|[^\"\\\\])*)\"\\}").findAll(s).forEach { m ->
            val t = m.groupValues[1].toLongOrNull() ?: return@forEach
            val name = m.groupValues[2].replace("\\\\", "\\").replace("\\\"", "\"")
            val dose = m.groupValues[3].replace("\\\\", "\\").replace("\\\"", "\"")
            if (name.isNotBlank() && seen.add("$t|$name")) { medEvents.add(MedEvent(t, name, dose)); n++ }
        }
        if (n > 0) { val srt = medEvents.sortedBy { it.t }; medEvents.clear(); medEvents.addAll(srt); Persistence.saveMeds() }
        return n
    }
    /** Werte aus einem Export importieren (Duplikate per Zeitstempel überspringen). */
    fun importJson(s: String): Int {
        val seen = HashSet<Long>(); history.forEach { seen.add(it.t) }
        var n = 0
        Regex("\"t\":(\\d+),\"g\":(\\d+)").findAll(s).forEach { m ->
            val t = m.groupValues[1].toLongOrNull(); val g = m.groupValues[2].toIntOrNull()
            if (t != null && g != null && seen.add(t)) { history.add(GlucosePoint(t, g)); n++ }
        }
        sortHistory()
        return n
    }
}
