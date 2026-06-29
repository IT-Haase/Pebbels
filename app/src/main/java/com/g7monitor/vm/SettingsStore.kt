/*
 * Persistente Benutzereinstellungen.
 *
 * Hält die vom User konfigurierbaren Werte, die nichts mit der aktiven
 * BLE-Session zu tun haben und deshalb NICHT in SensorStore gehören:
 *
 *  - TIR-Grenzen (Time-in-Range) für die Statistik
 *  - Hypo-Alarm: Schwelle + Ein/Aus + Wiederholintervall + Ton/Vibration
 *  - Hyper-Alarm: analog, standardmäßig aus
 *
 * Default-Werte sind für HUNDE gewählt (der User misst seinen Hund):
 *   - Normal 80–180 mg/dL
 *   - Hypo < 70
 *   - Hyper > 250 (aus per Default, weil Hunde gelegentlich Spitzen haben)
 *
 * Die UI liest den State über die StateFlow; Schreibzugriffe gehen durch
 * die update-Methoden, damit SharedPreferences und Flow synchron bleiben.
 */
package com.g7monitor.vm

import android.content.Context
import android.content.SharedPreferences
import com.g7monitor.ble.SensorType
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    // --- Statistik / TIR ---
    val tirLow: Int = 60,
    val tirHigh: Int = 160,

    // --- Hypo-Alarm ---
    val hypoEnabled: Boolean = true,
    val hypoThreshold: Int = 50,

    // --- Hyper-Alarm ---
    val hyperEnabled: Boolean = false,
    val hyperThreshold: Int = 200,

    // --- Alarm-Verhalten ---
    val alarmRepeatMin: Int = 10,          // Mindestabstand zwischen Alarmen
    val alarmSound: Boolean = true,
    val alarmVibrate: Boolean = true,

    // --- Zeitraum für Statistik-Screen (Stunden) ---
    val statsRangeHours: Int = 24,

    // --- Cloud-Upload (Pebbels-Dashboard) ---
    // Wenn aktiviert, schickt die App alle 5 Minuten die letzten 6 h
    // Glukose-Werte ans Pebbels-Dashboard. Der Server-Pfad ist fest
    // einprogrammiert (siehe companion object), die cloudUuid identifiziert
    // diese App-Installation eindeutig — sie ist gleichzeitig der
    // Zugriffsschlüssel und wird beim ersten Start einmalig erzeugt.
    val uploadEnabled: Boolean = false,
    val cloudUuid: String = "",

    // --- Aktiver Sensor-Typ (aktuell nur Dexcom) ---
    val sensorType: SensorType = SensorType.DexcomG7,

    // --- Kalibrierung: Offset (mg/dL) für den AKTUELLEN Sensor-Typ ---
    // Manuelle Messung (Fingerstick): Offset = Fingerstick − Sensorwert.
    // Wird auf alle folgenden Werte dieses Sensors addiert. Pro Sensor gespeichert.
    val calibOffset: Int = 0,

    // --- UI (von der geteilten Oberfläche) ---
    val themeMode: String = "",   // "" = System, "light", "dark"
    val language: String = "",    // "" = System, sonst "de"/"en"/"es"
)

class SettingsStore private constructor(ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    // ---------- Lesen ----------

    private fun load(): SettingsState {
        // Einmalige Migration auf die neuen Standardwerte (Hypo 50, Hyper 200, TIR 60–160).
        if (prefs.getInt(K_VER, 0) < 2) {
            prefs.edit()
                .putInt(K_TIR_LOW, 60).putInt(K_TIR_HIGH, 160)
                .putInt(K_HYPO_TH, 50).putInt(K_HYPER_TH, 200)
                .putInt(K_VER, 2).apply()
        }
        val st = loadSensorType()
        return SettingsState(
            tirLow          = prefs.getInt(K_TIR_LOW, 60),
            tirHigh         = prefs.getInt(K_TIR_HIGH, 160),
            hypoEnabled     = prefs.getBoolean(K_HYPO_EN, true),
            hypoThreshold   = prefs.getInt(K_HYPO_TH, 50),
            hyperEnabled    = prefs.getBoolean(K_HYPER_EN, false),
            hyperThreshold  = prefs.getInt(K_HYPER_TH, 200),
            alarmRepeatMin  = prefs.getInt(K_ALRM_REP, 10),
            alarmSound      = prefs.getBoolean(K_ALRM_SND, true),
            alarmVibrate    = prefs.getBoolean(K_ALRM_VIB, true),
            statsRangeHours = prefs.getInt(K_STATS_H, 24),
            uploadEnabled   = prefs.getBoolean(K_UP_EN, false),
            cloudUuid       = ensureUuid(),
            sensorType      = st,
            calibOffset     = prefs.getInt(K_CALIB + st.name, 0),
            themeMode       = prefs.getString(K_THEME, "") ?: "",
            language        = prefs.getString(K_LANG, "") ?: "",
        )
    }

    /** Liest den gespeicherten Sensor-Typ; fällt bei Unbekanntem auf Dexcom. */
    private fun loadSensorType(): SensorType =
        runCatching {
            SensorType.valueOf(prefs.getString(K_SENSOR, null) ?: SensorType.DexcomG7.name)
        }.getOrDefault(SensorType.DexcomG7)

    /** Liest die Cloud-UUID — und erzeugt sie beim allerersten Start
     *  einmalig. Danach bleibt sie für die Lebensdauer der Installation
     *  konstant: sie ist die Identität dieses Hundes auf dem Server. */
    private fun ensureUuid(): String {
        val existing = prefs.getString(K_UP_UUID, null)
        if (existing != null && existing.length == 36) return existing
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(K_UP_UUID, fresh).apply()
        return fresh
    }

    // ---------- Schreiben ----------

    fun updateTir(low: Int, high: Int) {
        // Sanity-Check: low muss < high sein, sonst ignorieren.
        if (low >= high) return
        prefs.edit().putInt(K_TIR_LOW, low).putInt(K_TIR_HIGH, high).apply()
        _state.value = _state.value.copy(tirLow = low, tirHigh = high)
    }

    fun updateHypo(enabled: Boolean, threshold: Int) {
        prefs.edit().putBoolean(K_HYPO_EN, enabled).putInt(K_HYPO_TH, threshold).apply()
        _state.value = _state.value.copy(hypoEnabled = enabled, hypoThreshold = threshold)
    }

    fun updateHyper(enabled: Boolean, threshold: Int) {
        prefs.edit().putBoolean(K_HYPER_EN, enabled).putInt(K_HYPER_TH, threshold).apply()
        _state.value = _state.value.copy(hyperEnabled = enabled, hyperThreshold = threshold)
    }

    fun updateAlarmBehavior(repeatMin: Int, sound: Boolean, vibrate: Boolean) {
        val r = repeatMin.coerceIn(1, 120)
        prefs.edit()
            .putInt(K_ALRM_REP, r)
            .putBoolean(K_ALRM_SND, sound)
            .putBoolean(K_ALRM_VIB, vibrate)
            .apply()
        _state.value = _state.value.copy(
            alarmRepeatMin = r, alarmSound = sound, alarmVibrate = vibrate
        )
    }

    fun updateStatsRange(hours: Int) {
        val h = hours.coerceIn(1, 720)
        prefs.edit().putInt(K_STATS_H, h).apply()
        _state.value = _state.value.copy(statsRangeHours = h)
    }

    /** Cloud-Upload nur ein-/ausschalten. URL ist fest, UUID generiert. */
    fun updateUpload(enabled: Boolean) {
        prefs.edit().putBoolean(K_UP_EN, enabled).apply()
        _state.value = _state.value.copy(uploadEnabled = enabled)
    }

    /** Aktiven Sensor-Typ wechseln (Dexcom/AiDEX/...). Lädt den zum neuen Typ
     *  gespeicherten Kalibrier-Offset mit. */
    fun updateSensorType(type: SensorType) {
        prefs.edit().putString(K_SENSOR, type.name).apply()
        _state.value = _state.value.copy(
            sensorType = type,
            calibOffset = prefs.getInt(K_CALIB + type.name, 0),
        )
    }

    /** Kalibrier-Offset (mg/dL) für den AKTUELLEN Sensor-Typ setzen. */
    fun setCalibOffset(offset: Int) {
        val t = _state.value.sensorType
        val o = offset.coerceIn(-200, 200)
        prefs.edit().putInt(K_CALIB + t.name, o).apply()
        _state.value = _state.value.copy(calibOffset = o)
    }

    fun updateTheme(mode: String) {
        prefs.edit().putString(K_THEME, mode).apply()
        _state.value = _state.value.copy(themeMode = mode)
    }
    fun updateLanguage(lang: String) {
        prefs.edit().putString(K_LANG, lang).apply()
        _state.value = _state.value.copy(language = lang)
    }

    companion object {
        private const val PREFS_NAME = "g7_settings"

        private const val K_TIR_LOW  = "tir_low"
        private const val K_TIR_HIGH = "tir_high"
        private const val K_HYPO_EN  = "hypo_enabled"
        private const val K_HYPO_TH  = "hypo_threshold"
        private const val K_HYPER_EN = "hyper_enabled"
        private const val K_HYPER_TH = "hyper_threshold"
        private const val K_ALRM_REP = "alarm_repeat_min"
        private const val K_ALRM_SND = "alarm_sound"
        private const val K_ALRM_VIB = "alarm_vibrate"
        private const val K_STATS_H  = "stats_range_hours"
        private const val K_UP_EN    = "upload_enabled"
        private const val K_UP_UUID  = "cloud_uuid"
        private const val K_SENSOR   = "sensor_type"
        private const val K_CALIB    = "calib_offset_"   // + SensorType.name
        private const val K_VER      = "settings_ver"
        private const val K_THEME    = "theme_mode"
        private const val K_LANG     = "ui_language"

        // --- Pebbels-Server: fest einprogrammiert ---
        /** Endpunkt, an den die App die Werte sendet. */
        const val INGEST_URL = "https://sa1.de/pebbels/ingest.php"
        /** Basis der Dashboard-URL — die UUID wird angehängt. */
        private const val DASHBOARD_BASE =
            "https://sa1.de/pebbels/dash_board_pebbels.php?id="

        /** Fertige Dashboard-URL für eine UUID (für QR-Code + Teilen). */
        fun dashboardUrl(uuid: String): String = DASHBOARD_BASE + uuid

        @Volatile private var instance: SettingsStore? = null

        fun get(ctx: Context): SettingsStore {
            return instance ?: synchronized(this) {
                instance ?: SettingsStore(ctx).also { instance = it }
            }
        }
    }
}
