package com.g7monitor.shared.ui

import com.g7monitor.shared.platform.persistRead
import com.g7monitor.shared.platform.persistWrite

/** Speichert/lädt Einstellungen + Verlauf über den plattformspezifischen
 *  String-Speicher (iOS: Datei). Einfaches Zeilenformat — robust + lesbar. */
object Persistence {

    fun load() {
        var ver = 0
        persistRead("settings")?.split("\n")?.forEach { line ->
            val i = line.indexOf('=')
            if (i < 0) return@forEach
            val k = line.substring(0, i); val v = line.substring(i + 1)
            when (k) {
                "ver" -> ver = v.toIntOrNull() ?: 0
                "language" -> AppState.language = v
                "themeMode" -> AppState.themeMode = v
                "windowHours" -> v.toIntOrNull()?.let { AppState.windowHours = it }
                "statsRangeHours" -> v.toIntOrNull()?.let { AppState.statsRangeHours = it }
                "tirLow" -> v.toIntOrNull()?.let { AppState.tirLow = it }
                "tirHigh" -> v.toIntOrNull()?.let { AppState.tirHigh = it }
                "hypoEnabled" -> AppState.hypoEnabled = v == "true"
                "hypoThreshold" -> v.toIntOrNull()?.let { AppState.hypoThreshold = it }
                "hyperEnabled" -> AppState.hyperEnabled = v == "true"
                "hyperThreshold" -> v.toIntOrNull()?.let { AppState.hyperThreshold = it }
                "alarmRepeatMin" -> v.toIntOrNull()?.let { AppState.alarmRepeatMin = it }
                "alarmSound" -> AppState.alarmSound = v == "true"
                "alarmVibrate" -> AppState.alarmVibrate = v == "true"
                "calibOffset" -> v.toIntOrNull()?.let { AppState.calibOffset = it }
                "uploadEnabled" -> AppState.uploadEnabled = v == "true"
                "cloudUuid" -> if (v.isNotEmpty()) AppState.cloudUuid = v
                "pin" -> AppState.pin = v
                "sensorType" -> if (v.isNotEmpty()) AppState.sensorType = v
                "aidexSerial" -> AppState.aidexSerial = v
            }
        }
        if (ver < 2) {   // einmalige Migration auf neue Standardwerte (Hypo 50, Hyper 200, TIR 60–160)
            AppState.tirLow = 60; AppState.tirHigh = 160
            AppState.hypoThreshold = 50; AppState.hyperThreshold = 200
        }
        persistRead("history")?.let { raw ->
            val pts = raw.split("\n").mapNotNull { line ->
                val p = line.split(",")
                val t = p.getOrNull(0)?.toLongOrNull(); val mv = p.getOrNull(1)?.toIntOrNull()
                val r = p.getOrNull(2)?.toFloatOrNull() ?: 0f
                if (t != null && mv != null) GlucosePoint(t, mv, r) else null
            }
            AppState.history.addAll(pts)   // eine State-Mutation statt tausende
        }
    }

    fun saveSettings() {
        persistWrite("settings", buildString {
            append("ver=2\n")
            append("language=${AppState.language}\n")
            append("themeMode=${AppState.themeMode}\n")
            append("windowHours=${AppState.windowHours}\n")
            append("statsRangeHours=${AppState.statsRangeHours}\n")
            append("tirLow=${AppState.tirLow}\n")
            append("tirHigh=${AppState.tirHigh}\n")
            append("hypoEnabled=${AppState.hypoEnabled}\n")
            append("hypoThreshold=${AppState.hypoThreshold}\n")
            append("hyperEnabled=${AppState.hyperEnabled}\n")
            append("hyperThreshold=${AppState.hyperThreshold}\n")
            append("alarmRepeatMin=${AppState.alarmRepeatMin}\n")
            append("alarmSound=${AppState.alarmSound}\n")
            append("alarmVibrate=${AppState.alarmVibrate}\n")
            append("calibOffset=${AppState.calibOffset}\n")
            append("uploadEnabled=${AppState.uploadEnabled}\n")
            append("cloudUuid=${AppState.cloudUuid}\n")
            append("pin=${AppState.pin}\n")
            append("sensorType=${AppState.sensorType}\n")
            append("aidexSerial=${AppState.aidexSerial}\n")
        })
    }

    fun saveHistory() {
        persistWrite("history", AppState.history.joinToString("\n") { "${it.t},${it.v},${it.r}" })
    }

    /** Medikamente (Gaben + pflegbare Liste) laden — getrennt von Settings/Verlauf,
     *  läuft so auf iOS und Android gleich. */
    fun loadMeds() {
        persistRead("medEvents")?.let { raw ->
            val evs = raw.split("\n").mapNotNull { line ->
                val p = line.split("\t")
                val t = p.getOrNull(0)?.toLongOrNull()
                if (t != null && p.size >= 3) MedEvent(t, p[1], p[2]) else null
            }
            AppState.medEvents.clear(); AppState.medEvents.addAll(evs.sortedBy { it.t })
        }
        persistRead("medKinds")?.let { raw ->
            val kinds = raw.split("\n").mapNotNull { line ->
                val p = line.split("\t")
                val name = p.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val doses = p.getOrNull(1)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                MedKind(name, doses)
            }
            if (kinds.isNotEmpty()) { AppState.medKinds.clear(); AppState.medKinds.addAll(kinds) }
        }
        AppState.seedMedKindsIfEmpty()
    }

    fun saveMeds() {
        persistWrite("medEvents", AppState.medEvents.joinToString("\n") { "${it.t}\t${it.name}\t${it.dose}" })
    }
    fun saveMedKinds() {
        persistWrite("medKinds", AppState.medKinds.joinToString("\n") { k -> "${k.name}\t${k.doses.joinToString(",")}" })
    }

    /** Sensor-Start (für den Ablauf-Hinweis) — überlebt App-Neustart, bis der erste
     *  neue Messwert ihn bestätigt. Läuft so auf iOS und Android gleich. */
    fun loadSensorStart() {
        persistRead("sensorStart")?.trim()?.toLongOrNull()?.let { if (it > 0L) AppState.sensorStartMs = it }
    }
    fun saveSensorStart() {
        persistWrite("sensorStart", AppState.sensorStartMs.toString())
    }
}
