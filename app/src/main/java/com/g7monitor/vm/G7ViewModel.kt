/*
 * Dünner ViewModel-Wrapper um G7Repository.
 *
 * Die komplette BLE- und Zustandslogik lebt jetzt im Prozess-weiten
 * G7Repository — damit der Foreground-Service sie unabhängig von der Activity
 * benutzen kann. Dieser ViewModel existiert nur noch, damit die Compose-UI
 * den bekannten `vm.state`/`vm.startScan()`-Stil weiter nutzen kann.
 */
package com.g7monitor.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.g7monitor.ble.ReadingsStore
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState {
    Idle, Scanning, Found, Connecting, Bonded, Authenticating, Authenticated, Receiving, Error
}

data class G7State(
    val connection: ConnectionState = ConnectionState.Idle,
    val pin: String = "",
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val rssi: Int? = null,
    val lastGlucose: Int? = null,
    val lastGlucoseAt: Long? = null,
    val rateMgdlPerMin: Float? = null,
    val handshakePhase: Int = -1,
    val statusMessage: String = "Bereit",
    val scanLog: List<String> = emptyList(),
    val nativeLoaded: Boolean = com.g7monitor.ble.Dex.Loaded,
    val nativeError: String? = com.g7monitor.ble.Dex.loadError,
    val history: List<ReadingsStore.Point> = emptyList(),
    val windowHours: Int = 24,
    // --- Cloud-Upload-Status (nicht persistiert, nur Anzeige) ---
    val lastUploadAt: Long? = null,
    val lastUploadMsg: String? = null,
)

class G7ViewModel(app: Application) : AndroidViewModel(app) {

    init { G7Repository.init(app.applicationContext) }

    val state: StateFlow<G7State> = G7Repository.state
    val settings: StateFlow<SettingsState> = G7Repository.settings.state

    fun setWindow(hours: Int) = G7Repository.setWindow(hours)
    fun updatePin(pin: String) = G7Repository.updatePin(pin)
    fun startScan() = G7Repository.startScan()
    fun stopScan() = G7Repository.stopScan()
    fun forgetSensor() = G7Repository.forgetSensor()
    fun switchSensor(newPin: String) = G7Repository.switchSensor(newPin)
    fun hardReset() = G7Repository.hardReset()
    fun calibrate(manualMgdl: Int): Boolean = G7Repository.calibrate(manualMgdl)
    fun clearCalibration() = G7Repository.clearCalibration()
    /** Gespeicherten Verlauf löschen. */
    fun clearSensorHistory(sensor: String) = G7Repository.clearSensorHistory(sensor)
    fun exportReadings(out: java.io.OutputStream): Int = G7Repository.exportReadings(out)
    fun importReadings(input: java.io.InputStream): Int = G7Repository.importReadings(input)

    // Settings-Mutatoren — reichen direkt an den SettingsStore durch.
    fun updateTir(low: Int, high: Int) = G7Repository.settings.updateTir(low, high)
    fun updateHypo(enabled: Boolean, threshold: Int) =
        G7Repository.settings.updateHypo(enabled, threshold)
    fun updateHyper(enabled: Boolean, threshold: Int) =
        G7Repository.settings.updateHyper(enabled, threshold)
    fun updateAlarmBehavior(repeatMin: Int, sound: Boolean, vibrate: Boolean) =
        G7Repository.settings.updateAlarmBehavior(repeatMin, sound, vibrate)
    fun updateStatsRange(hours: Int) = G7Repository.settings.updateStatsRange(hours)
    fun updateUpload(enabled: Boolean) = G7Repository.settings.updateUpload(enabled)
    fun updateTheme(mode: String) = G7Repository.settings.updateTheme(mode)
    fun updateLanguage(lang: String) = G7Repository.settings.updateLanguage(lang)
    fun updateSensorType(type: com.g7monitor.ble.SensorType) = G7Repository.settings.updateSensorType(type)

    /** Echter Alarm-Selbsttest durch die komplette Auswerte-Kette. */
    fun runAlarmSelfTest(): String = G7Repository.runAlarmSelfTest()

    // Den BLE-Client NICHT stoppen, wenn der ViewModel stirbt (z. B. App
    // in den Hintergrund) — der Foreground-Service hält die Verbindung.
    override fun onCleared() { /* no-op */ }
}
