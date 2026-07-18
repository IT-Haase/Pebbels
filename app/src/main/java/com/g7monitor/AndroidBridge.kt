package com.g7monitor

import androidx.compose.runtime.snapshotFlow
import com.g7monitor.ble.dexk.DexSession
import com.g7monitor.shared.ui.AppState
import com.g7monitor.shared.ui.CloudUploader
import com.g7monitor.shared.ui.GlucosePoint
import com.g7monitor.util.DebugLog
import com.g7monitor.vm.ConnectionState
import com.g7monitor.vm.G7State
import com.g7monitor.vm.G7ViewModel
import com.g7monitor.vm.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Verbindet die geteilte Compose-UI (AppState) mit der bestehenden Android-
 * Engine (G7ViewModel/Repository) — das Android-Pendant zur iOS-Bridge.
 *
 * Die Engine bleibt unangetastet und ist die Quelle der Wahrheit für BLE,
 * Verlauf (ReadingsStore), Einstellungen (SettingsStore), Upload + Alarme.
 * Die Brücke spiegelt deren Zustand nach AppState (für die UI) und leitet
 * UI-Aktionen/Änderungen an die Engine weiter. Kein pushLive → der geteilte
 * CloudUploader/Alarms läuft NICHT (das macht die Engine).
 */
object AndroidBridge {
    private var lastHistory: List<*>? = null
    private var ready = false   // erst Engine→AppState laden, dann UI→Engine zulassen

    fun wire(
        vm: G7ViewModel,
        scope: CoroutineScope,
        onExport: () -> Unit,
        onImport: () -> Unit,
        onOpenUrl: (String) -> Unit,
        onShareUrl: (String) -> Unit,
    ) {
        CloudUploader.enabled = false   // Android: die Engine lädt hoch

        AppState.onConnect = { vm.startScan() }
        AppState.onDisconnect = { vm.stopScan() }
        AppState.onHardReset = { vm.forgetSensor() }
        AppState.onClearHistory = { vm.clearSensorHistory(AppState.sensorType) }
        AppState.onCalibrate = { mgdl -> vm.calibrate(mgdl) }
        AppState.onCalibrateReset = { vm.clearCalibration() }
        AppState.onAlarmTest = { vm.runAlarmSelfTest() }
        AppState.onExport = onExport
        AppState.onImport = onImport
        AppState.onOpenUrl = onOpenUrl
        AppState.onShareUrl = onShareUrl
        AppState.onSensorTypeChange = { tag ->
            vm.updateSensorType(
                if (tag == "aidex") com.g7monitor.ble.SensorType.AidexX
                else com.g7monitor.ble.SensorType.DexcomG7
            )
        }
        // onScanSerial setzt MainActivity (braucht Activity-Kontext für Kamera + Dialog).

        scope.launch { vm.state.collect { applyState(it) } }
        scope.launch { vm.settings.collect { applySettings(it) } }
        scope.launch {
            DebugLog.entries.collect { entries ->
                AppState.log.clear()
                AppState.log.addAll(entries.takeLast(200).map { it.message })
            }
        }
        scope.launch {
            snapshotFlow {
                listOf(
                    AppState.tirLow, AppState.tirHigh, AppState.hypoEnabled, AppState.hypoThreshold,
                    AppState.hyperEnabled, AppState.hyperThreshold, AppState.alarmRepeatMin,
                    AppState.alarmSound, AppState.alarmVibrate, AppState.statsRangeHours,
                    AppState.uploadEnabled, AppState.windowHours, AppState.themeMode,
                    AppState.language, AppState.pin, AppState.aidexSerial,
                )
            }.collect {
                com.g7monitor.vm.G7Repository.aidexSerial = AppState.aidexSerial
                if (ready) pushSettings(vm)
            }
        }
    }

    private fun applyState(st: G7State) {
        AppState.status = st.statusMessage
        AppState.connected = st.connection.ordinal >= ConnectionState.Bonded.ordinal &&
                             st.connection != ConnectionState.Error
        AppState.active = st.connection != ConnectionState.Idle && st.connection != ConnectionState.Error
        AppState.live = st.connection == ConnectionState.Receiving
        AppState.lastGlucose = st.lastGlucose
        AppState.rate = st.rateMgdlPerMin?.toDouble() ?: 0.0
        AppState.lastGlucoseAt = st.lastGlucoseAt ?: 0L
        AppState.updateSensorStart(   // Sensor-Ablauf-Hinweis: je nach aktivem Sensor
            if (AppState.sensorType == "aidex") com.g7monitor.vm.G7Repository.aidexSensorStartMs
            else DexSession.lastSensorStartMs
        )
        AppState.deviceName = st.deviceName
        AppState.handshakePhase = st.handshakePhase
        AppState.windowHours = st.windowHours
        AppState.backfillCount = st.history.size
        if (AppState.pin.isEmpty() && st.pin.isNotEmpty()) AppState.pin = st.pin
        if (st.history !== lastHistory) {
            lastHistory = st.history
            AppState.history.clear()
            AppState.history.addAll(st.history.map { GlucosePoint(it.timeMs, it.mgdl, it.rateMgdlPerMin) })
        }
    }

    private fun applySettings(s: SettingsState) {
        AppState.tirLow = s.tirLow; AppState.tirHigh = s.tirHigh
        AppState.hypoEnabled = s.hypoEnabled; AppState.hypoThreshold = s.hypoThreshold
        AppState.hyperEnabled = s.hyperEnabled; AppState.hyperThreshold = s.hyperThreshold
        AppState.alarmRepeatMin = s.alarmRepeatMin
        AppState.alarmSound = s.alarmSound; AppState.alarmVibrate = s.alarmVibrate
        AppState.statsRangeHours = s.statsRangeHours
        AppState.uploadEnabled = s.uploadEnabled; AppState.cloudUuid = s.cloudUuid
        AppState.calibOffset = s.calibOffset
        AppState.sensorType = s.sensorType.tag
        AppState.themeMode = s.themeMode; AppState.language = s.language
        ready = true
    }

    private fun pushSettings(vm: G7ViewModel) {
        vm.updateTir(AppState.tirLow, AppState.tirHigh)
        vm.updateHypo(AppState.hypoEnabled, AppState.hypoThreshold)
        vm.updateHyper(AppState.hyperEnabled, AppState.hyperThreshold)
        vm.updateAlarmBehavior(AppState.alarmRepeatMin, AppState.alarmSound, AppState.alarmVibrate)
        vm.updateStatsRange(AppState.statsRangeHours)
        vm.updateUpload(AppState.uploadEnabled)
        vm.setWindow(AppState.windowHours)
        vm.updateTheme(AppState.themeMode)
        vm.updateLanguage(AppState.language)
        if (AppState.pin.isNotEmpty()) vm.updatePin(AppState.pin)
    }
}
