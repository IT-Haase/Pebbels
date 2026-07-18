/*
 * Prozess-weites Singleton für BLE-Client + Zustand.
 *
 * Warum das Ganze nicht im ViewModel wohnt:
 *   Ein ViewModel lebt nur so lange wie die Activity, die ihn besitzt. Sobald
 *   der User die App aus dem Recents-Menü wischt, zerstört Android die Activity,
 *   der ViewModel stirbt — und mit ihm der G7BleClient und jede laufende
 *   Verbindung. Für eine CGM-App ist das inakzeptabel.
 *
 * Lösung:
 *   – Die State-/BLE-Logik liegt hier, im Prozess-Singleton.
 *   – Ein Foreground-Service (G7MonitorService) sorgt dafür, dass Android
 *     den Prozess nicht einfach killt, auch wenn keine Activity da ist.
 *   – Der ViewModel ist nur noch ein dünner Wrapper, der state/commands
 *     an diesen Repository weiterleitet.
 */
package com.g7monitor.vm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.g7monitor.alarm.HypoAlarm
import com.g7monitor.util.DebugLog
import com.g7monitor.ble.CgmDriver
import com.g7monitor.ble.CgmEvent
import com.g7monitor.ble.SensorFactory
import com.g7monitor.ble.SensorType
import com.g7monitor.ble.ReadingsStore
import com.g7monitor.ble.SensorStore
import com.g7monitor.service.G7MonitorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object G7Repository {

    private const val TAG = "G7Repository"

    /** Zeitfenster, das beim Cloud-Upload mitgeschickt wird: letzte 6 Stunden. */
    private const val UPLOAD_WINDOW_MS = 6L * 3600L * 1000L

    private val _state = MutableStateFlow(
        G7State(
            nativeLoaded = com.g7monitor.ble.Dex.Loaded,
            nativeError = com.g7monitor.ble.Dex.loadError,
        )
    )
    val state: StateFlow<G7State> = _state.asStateFlow()

    // Der Settings-Store wird bei init() befüllt und ist danach für UI und
    // Alarm-Logik verfügbar. Vor init() bewusst null, damit jeder Lese-
    // zugriff hart crasht statt mit Defaults stillschweigend weiterzumachen.
    private var _settings: SettingsStore? = null
    val settings: SettingsStore
        get() = _settings ?: error("G7Repository.init() wurde noch nicht aufgerufen")

    private var appCtx: Context? = null
    private var driver: CgmDriver? = null
    private var readings: ReadingsStore? = null
    private val knownTimes = HashSet<Long>()
    // letzter UNkalibrierter Sensorwert (für die Offset-Berechnung beim Kalibrieren)
    @Volatile private var lastRawGlucose: Int? = null
    @Volatile private var initialized = false
    @Volatile var aidexSerial: String = ""   // vom UI (SN-Feld) gespiegelt — für den AiDEX-Treiber
    @Volatile var aidexNewestStoredMs: Long = 0L   // neuester gespeicherter AiDEX-Wert (Backfill-Wiederaufsetzen)
    @Volatile var aidexSensorStartMs: Long = 0L    // AiDEX-Sensorstart (ms) — für den Ablauf-Hinweis (14 Tage)

    // --- Hypo-/Hyper-Alarm Throttling ---
    // Wir wollen nicht bei JEDEM 5-Minuten-Paket denselben Alarm feuern,
    // sondern nur, wenn ein neuer Zustand (aus der Range → in Hypo) eintritt
    // ODER das Wiederhol-Intervall aus den Settings abgelaufen ist.
    private var lastHypoAlarmAt: Long = 0L
    private var lastHyperAlarmAt: Long = 0L
    private var wasInHypo: Boolean = false
    private var wasInHyper: Boolean = false

    /** Einmalig vom Application-Context aus aufrufen (MainActivity.onCreate
     *  oder G7MonitorService.onCreate — je nachdem, wer zuerst kommt). */
    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        val app = ctx.applicationContext
        appCtx = app
        _settings = SettingsStore.get(app)
        readings = ReadingsStore(app)
        val initial = readings!!.load()
        knownTimes.addAll(initial.map { it.timeMs })
        aidexNewestStoredMs = initial.filter { it.sensor == "aidex" }.maxOfOrNull { it.timeMs } ?: 0L
        if (initial.isNotEmpty()) {
            val last = initial.last()
            val ageMs = System.currentTimeMillis() - last.timeMs
            val isFresh = ageMs in 0..15L * 60L * 1000L
            _state.value = _state.value.copy(
                history = initial,
                lastGlucose = if (isFresh) last.mgdl else null,
                lastGlucoseAt = if (isFresh) last.timeMs else null,
                rateMgdlPerMin = if (isFresh) last.rateMgdlPerMin else null,
            )
        }
        // Persistierten PIN + Sensor-Snapshot laden — dann weiß die App nach
        // einem Neustart sofort, welcher Sensor gekoppelt war, und der User
        // muss den PIN nicht noch einmal eingeben.
        val snap = try { SensorStore(app).load() } catch (_: Throwable) { null }
        val hasSnap = snap != null
        if (hasSnap) {
            val pinStr = snap!!.pin
                .map { b -> (b.toInt() and 0xFF).toChar() }
                .joinToString("")
                .filter { it.isDigit() }
                .take(4)
            if (pinStr.length == 4) {
                _state.value = _state.value.copy(pin = pinStr)
                DebugLog.i(TAG, "PIN aus SensorStore wiederhergestellt")
            }
        }
        DebugLog.i(TAG, "initialisiert, history=${initial.size}, snapshot=$hasSnap")

        // Wenn ein vorheriger Sensor bekannt ist, PIN gültig, Native geladen
        // und die BLE-Permissions schon erteilt sind → direkt losscannen.
        // Damit ist die App nach einem Neustart nahtlos wieder live, ohne
        // dass der User den Button drücken muss.
        val s = _state.value
        if (hasSnap && s.nativeLoaded && s.pin.length == 4 && hasBlePermissions(app)) {
            DebugLog.i(TAG, "Auto-Reconnect: gespeicherte Session + Permissions vorhanden → startScan()")
            startScan()
        } else if (hasSnap) {
            DebugLog.i(TAG, "Auto-Reconnect übersprungen (native=${s.nativeLoaded} pinLen=${s.pin.length} perms=${hasBlePermissions(app)})")
        }
    }

    /** Prüft ob die für BLE-Scan/-Connect nötigen Runtime-Permissions erteilt sind.
     *  Ohne sie würde G7BleClient.start() in eine SecurityException laufen. */
    private fun hasBlePermissions(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /** Vom UI aufgerufen, nachdem der User Runtime-Permissions erteilt hat.
     *  Wenn ein Sensor bekannt ist und gerade nichts läuft, direkt verbinden. */
    fun onPermissionsGranted() {
        val ctx = appCtx ?: return
        val s = _state.value
        if (driver != null) return                       // läuft schon
        val hasSnap = try { SensorStore(ctx).exists() } catch (_: Throwable) { false }
        if (hasSnap && s.nativeLoaded && s.pin.length == 4 && hasBlePermissions(ctx)) {
            DebugLog.i(TAG, "onPermissionsGranted: startScan()")
            startScan()
        }
    }

    fun setWindow(hours: Int) {
        // bis zu 28 Tage (672 h) — passt zur ReadingsStore-Retention, damit
        // der User auch ganze Extended-Use-Sessions überblickt.
        _state.value = _state.value.copy(windowHours = hours.coerceIn(1, 672))
    }

    fun updatePin(pin: String) {
        val clean = pin.filter { it.isDigit() }.take(4)
        _state.value = _state.value.copy(pin = clean)
        // Sobald der PIN vollständig ist, direkt auf Platte legen — dann
        // überlebt er App-Neustarts auch wenn das Pairing (noch) nicht
        // durchgelaufen ist. Bestehenden Session-Blob (falls vorhanden)
        // nicht überschreiben; nur PIN aktualisieren.
        if (clean.length == 4) {
            val ctx = appCtx ?: return
            try {
                val store = com.g7monitor.ble.SensorStore(ctx)
                val existingBlob = store.load()?.blob ?: ByteArray(0)
                store.save(clean.map { it.code.toByte() }.toByteArray(), existingBlob)
                DebugLog.i(TAG, "updatePin: PIN in SensorStore persistiert (blob=${existingBlob.size} B)")
            } catch (t: Throwable) {
                DebugLog.w(TAG, "updatePin: Persistieren fehlgeschlagen", t)
            }
        }
    }

    fun startScan() {
        val ctx = appCtx ?: return
        val s = _state.value
        // Nicht-Dexcom-Sensoren (AiDEX X / LinX …) laufen über ihren eigenen,
        // vom Dexcom-Flow vollständig getrennten Start-Pfad.
        val activeType = settings.state.value.sensorType
        if (activeType != SensorType.DexcomG7) {
            startAlternativeSensor(activeType)
            return
        }
        if (!s.nativeLoaded) {
            _state.value = s.copy(
                connection = ConnectionState.Error,
                statusMessage = "Native Library nicht geladen"
            )
            return
        }
        if (s.pin.length != 4) {
            _state.value = s.copy(statusMessage = "Bitte 4-stelligen PIN eingeben")
            return
        }
        // Wenn der im UI eingegebene PIN nicht zum gespeicherten Snapshot
        // passt, gehört das Blob zu einem anderen Sensor. Nicht restoren —
        // sonst läuft Handshake ins Leere und wir landen im Zombie-Bond-Loop.
        // Readings bleiben erhalten, damit die Statistik nahtlos weiterläuft.
        try {
            val store = com.g7monitor.ble.SensorStore(ctx)
            val snap = store.load()
            if (snap != null) {
                val storedPin = snap.pin
                    .map { b -> (b.toInt() and 0xFF).toChar() }
                    .joinToString("")
                    .filter { it.isDigit() }
                val currentPin = s.pin
                if (storedPin.length == 4 && storedPin != currentPin) {
                    DebugLog.i(TAG, "startScan: PIN hat sich geändert ($storedPin → $currentPin), invalidieren")
                    store.clear()
                }
            }
        } catch (_: Throwable) {}
        // Foreground-Service starten — danach bleibt der Prozess am Leben,
        // selbst wenn die Activity zerstört wird.
        startForegroundService(ctx)
        val pinBytes = s.pin.map { it.code.toByte() }.toByteArray()
        driver?.stop()
        val c = SensorFactory.create(SensorType.DexcomG7, ctx, pinBytes, "")
        c.addListener { ev -> handleEvent(ev) }
        driver = c
        _state.value = s.copy(
            connection = ConnectionState.Scanning,
            statusMessage = "Scanne nach G7 ...",
            scanLog = emptyList()
        )
        try {
            c.start()
        } catch (t: Throwable) {
            DebugLog.e(TAG, "start() fehlgeschlagen", t)
            _state.value = _state.value.copy(
                connection = ConnectionState.Error,
                statusMessage = "Fehler: ${t.message ?: t::class.java.simpleName}"
            )
        }
    }

    /** Start-Pfad für Nicht-Dexcom-Sensoren (aktuell AiDEX X / LinX).
     *  Kein PIN nötig; der Treiber übernimmt Scan, Auth und Decodierung in
     *  seinem eigenen Paket (ble.aidexx). Bewusst getrennt vom Dexcom-Flow,
     *  damit dort nichts anzufassen ist. */
    private fun startAlternativeSensor(type: SensorType) {
        val ctx = appCtx ?: return
        startForegroundService(ctx)
        driver?.stop()
        val serial = aidexSerial
        val c = SensorFactory.create(type, ctx, ByteArray(0), serial)
        c.addListener { ev -> handleEvent(ev) }
        driver = c
        _state.value = _state.value.copy(
            connection = ConnectionState.Scanning,
            statusMessage = "Scanne nach ${type.label} …",
            scanLog = emptyList(),
        )
        runCatching { c.start() }.onFailure { t ->
            DebugLog.e(TAG, "start() (${type.label}) fehlgeschlagen", t)
            _state.value = _state.value.copy(
                connection = ConnectionState.Error,
                statusMessage = "Fehler: ${t.message ?: t::class.java.simpleName}",
            )
        }
    }

    fun stopScan() {
        driver?.stop()
        driver = null
        _state.value = _state.value.copy(
            connection = ConnectionState.Idle,
            statusMessage = "Gestoppt"
        )
        // Foreground-Service beenden — Notification geht weg, Prozess darf sterben.
        appCtx?.let { it.stopService(Intent(it, G7MonitorService::class.java)) }
    }

    fun forgetSensor() {
        val ctx = appCtx ?: return
        driver?.forgetSensor() ?: run {
            com.g7monitor.ble.SensorStore(ctx).clear()
        }
        readings?.clear()
        knownTimes.clear()
        _state.value = _state.value.copy(
            deviceName = null,
            deviceAddress = null,
            handshakePhase = -1,
            statusMessage = "Sensor vergessen — bereit für neuen",
            history = emptyList(),
            lastGlucose = null,
            lastGlucoseAt = null,
            rateMgdlPerMin = null,
        )
    }


    /** Manuelle Messung (Fingerstick) -> Kalibrierung des aktuellen Sensors.
     *  Offset = Fingerstick − letzter Sensor-Rohwert. Wird auf alle folgenden
     *  Werte dieses Sensors addiert. Gibt true zurück, wenn ein Sensorwert
     *  vorlag (sonst kann nicht kalibriert werden). */
    fun calibrate(manualMgdl: Int): Boolean {
        val raw = lastRawGlucose ?: return false
        val offset = (manualMgdl - raw).coerceIn(-200, 200)
        settings.setCalibOffset(offset)
        _state.value = _state.value.copy(
            // Anzeige sofort auf den kalibrierten Wert ziehen.
            lastGlucose = (raw + offset).coerceIn(10, 600),
            statusMessage = "Kalibriert: Offset ${if (offset >= 0) "+" else ""}$offset mg/dL"
        )
        return true
    }

    /** Kalibrierung des aktuellen Sensors zurücksetzen (Offset 0). */
    fun clearCalibration() {
        settings.setCalibOffset(0)
        _state.value = _state.value.copy(statusMessage = "Kalibrierung zurückgesetzt")
    }

    /** Den gespeicherten Verlauf löschen. */
    fun clearSensorHistory(sensor: String) {
        readings?.clearSensor(sensor)
        if (sensor == "aidex") aidexNewestStoredMs = 0L   // Resume zurücksetzen → nächstes Mal alles neu
        // In-Memory-History und Duplikat-Index ebenfalls bereinigen.
        val kept = _state.value.history.filter { it.sensor != sensor }
        knownTimes.clear()
        knownTimes.addAll(kept.map { it.timeMs })
        val label = if (sensor == "aidex") "AiDEX" else "Dexcom"
        _state.value = _state.value.copy(
            history = kept,
            statusMessage = "$label-Verlauf gelöscht",
        )
        DebugLog.i(TAG, "clearSensorHistory($sensor): ${kept.size} Werte bleiben")
    }

    /** Hard-Reset für den Debug-Tab.
     *  Räumt ALLES auf der Verbindungsseite weg (Scan, GATT, Bond, Session-
     *  Blob, Native-Keys, Auto-Heil-Zähler), behält aber PIN und History.
     *  Danach wird sofort ein frischer startScan() ausgelöst, der den J-PAKE-
     *  Handshake neu durchläuft.
     *
     *  Use-Case: Sensor sendet zwar, aber wir hängen in einem status=19-
     *  Reconnect-Loop oder die Live-Werte sind eingefroren — ein sauberer
     *  Bond-Aufbau klärt App-/Bond-seitige Hänger, ohne dass der User die
     *  PIN erneut eintippt oder die History wegwirft. */
    fun hardReset() {
        DebugLog.i(TAG, "hardReset: vollständiger Reset angefordert")
        val current = driver
        if (current != null) {
            // hardReset() im Client räumt nur State auf — er beendet den
            // Client nicht. Wir stoppen ihn danach explizit, weil startScan()
            // gleich einen neuen instantiiert.
            try { current.hardReset() } catch (t: Throwable) {
                DebugLog.w(TAG, "client.hardReset fehlgeschlagen", t)
            }
            try { current.stop() } catch (_: Throwable) {}
            driver = null
        } else {
            // Kein laufender Client — Session-Blob trotzdem wegräumen, damit
            // beim nächsten Start kein stale Blob restauriert wird.
            try { com.g7monitor.ble.SensorStore(appCtx!!).clearSessionKeepPin() }
            catch (_: Throwable) {}
        }
        _state.value = _state.value.copy(
            connection = ConnectionState.Idle,
            handshakePhase = -1,
            statusMessage = "Hard-Reset — starte neu …",
        )
        // Kurz Luft holen lassen (removeBond + GATT-Close sind async),
        // dann sofort frisch starten — PIN ist noch im State.
        Handler(Looper.getMainLooper()).postDelayed({ startScan() }, 800L)
    }

    /** Cloud-Upload ausführen — schickt die letzten 6 h Glukose-Werte an das
     *  Pebbels-Dashboard. Wird vom Foreground-Service alle 5 Minuten getriggert.
     *
     *  WICHTIG: Diese Methode blockiert (HTTP-Roundtrip, bis zu ~15 s). Sie
     *  MUSS auf einem Hintergrund-Thread aufgerufen werden — der Service ruft
     *  sie aus seinem Coroutine-Scope mit Dispatchers.IO.
     *
     *  Tut nichts, wenn der Upload in den Einstellungen deaktiviert ist oder
     *  keine Werte im 6-h-Fenster liegen. */
    fun performCloudUpload() {
        val cfg = _settings?.state?.value ?: return
        if (!cfg.uploadEnabled) return
        val cutoff = System.currentTimeMillis() - UPLOAD_WINDOW_MS
        val pts = _state.value.history
            .filter { it.timeMs >= cutoff }
            .sortedBy { it.timeMs }
        if (pts.isEmpty()) {
            DebugLog.i(TAG, "Cloud-Upload übersprungen — keine Werte im 6-h-Fenster")
            _state.value = _state.value.copy(
                lastUploadAt = System.currentTimeMillis(),
                lastUploadMsg = "Übersprungen — keine Werte",
            )
            return
        }
        val result = com.g7monitor.cloud.CloudUploader.upload(
            url = SettingsStore.INGEST_URL,
            uuid = cfg.cloudUuid,
            device = "Pebbels APP",
            tirLow = cfg.tirLow,
            tirHigh = cfg.tirHigh,
            points = pts,
        )
        _state.value = _state.value.copy(
            lastUploadAt = System.currentTimeMillis(),
            lastUploadMsg = result.message,
        )
    }

    /** Glukose-Historie als JSONL exportieren (Datensicherung).
     *  Der Aufrufer (Einstellungen-UI) liefert den OutputStream eines vom
     *  User über das System gewählten Speicherorts.
     *  @return Anzahl exportierter Werte. */
    fun exportReadings(out: java.io.OutputStream): Int {
        val r = readings ?: return 0
        return try {
            val n = r.exportTo(out)
            DebugLog.event(TAG, "Daten-Export: $n Werte gesichert")
            n
        } catch (t: Throwable) {
            DebugLog.w(TAG, "Daten-Export fehlgeschlagen", t)
            0
        }
    }

    /** Glukose-Historie aus einem JSONL-Stream importieren. Wird dedupliziert
     *  (über den Timestamp) in Store und In-Memory-Historie eingefügt — alte
     *  Sicherungen lassen sich so gefahrlos einspielen, ohne Doppelwerte.
     *  @return Anzahl tatsächlich neu hinzugefügter Werte. */
    fun importReadings(input: java.io.InputStream): Int {
        val r = readings ?: return 0
        val added = try {
            r.importFrom(input, knownTimes)
        } catch (t: Throwable) {
            DebugLog.w(TAG, "Daten-Import fehlgeschlagen", t)
            emptyList()
        }
        if (added.isNotEmpty()) {
            val merged = (_state.value.history + added).sortedBy { it.timeMs }
            _state.value = _state.value.copy(
                history = merged,
                statusMessage = "${added.size} Werte importiert",
            )
            DebugLog.event(TAG, "Daten-Import: ${added.size} neue Werte")
        } else {
            DebugLog.event(TAG, "Daten-Import: keine neuen Werte (alles schon vorhanden)")
        }
        return added.size
    }

    /** Auf einen anderen Sensor wechseln, ohne die bisherige Glukose-Historie
     *  zu verlieren. Verwirft Session-Blob + Android-Bond + Native-Keys
     *  (damit ein frischer Handshake gegen den neuen Sensor zieht),
     *  übernimmt den neuen PIN und startet direkt einen Scan.
     *
     *  Die Statistik bleibt durchgehend verfügbar — der ReadingsStore ist
     *  sensor-agnostisch und enthält einfach weiter alle bisherigen Werte. */
    fun switchSensor(newPin: String) {
        val ctx = appCtx ?: return
        val pinClean = newPin.filter { it.isDigit() }.take(4)
        if (pinClean.length != 4) {
            _state.value = _state.value.copy(statusMessage = "PIN muss 4 Ziffern haben")
            return
        }
        // WICHTIG: erst forgetSensor() — das ruft am laufenden Client
        // removeBond() auf und löscht so den Android-Bond (sonst bleibt eine
        // „Bond-Leiche" im BT-Stack zurück, die das frische Koppeln blockiert).
        // Danach stoppen und Treiber freigeben.
        try { driver?.forgetSensor() } catch (_: Throwable) {}
        driver?.stop()
        driver = null

        // Session-Blob + Native-Keys weg. Readings bleiben unberührt.
        try {
            val s = com.g7monitor.ble.SensorStore(ctx)
            s.clear()
        } catch (_: Throwable) {}

        _state.value = _state.value.copy(
            pin = pinClean,
            deviceName = null,
            deviceAddress = null,
            handshakePhase = -1,
            connection = ConnectionState.Idle,
            statusMessage = "Sensor-Wechsel — starte Pairing ...",
        )
        DebugLog.i(TAG, "switchSensor: Session/Bond verworfen, Readings behalten, neuer PIN = $pinClean")
        // Direkt in den normalen Scan-Flow — dort wird ein neuer G7BleClient
        // mit dem neuen PIN gebaut, der keine alte Session mehr kennt.
        startScan()
    }

    /** Prüft ob der eingetroffene Wert eine Hypo-/Hyper-Benachrichtigung
     *  auslösen soll. Feuert beim Übertritt der Schwelle sofort und
     *  danach nur noch alle `alarmRepeatMin` Minuten, solange der Zustand
     *  anhält. Wird der Wert wieder normal, wird der State resettet. */
    private fun maybeTriggerAlarm(mgdl: Int) {
        val ctx = appCtx ?: return
        val cfg = _settings?.state?.value ?: return
        val now = System.currentTimeMillis()
        val repeatMs = cfg.alarmRepeatMin * 60_000L

        // Hypo
        if (cfg.hypoEnabled && mgdl < cfg.hypoThreshold) {
            val isNewEvent = !wasInHypo
            val dueRepeat = now - lastHypoAlarmAt >= repeatMs
            if (isNewEvent || dueRepeat) {
                HypoAlarm.trigger(ctx, HypoAlarm.Kind.Hypo, mgdl, cfg)
                lastHypoAlarmAt = now
            }
            wasInHypo = true
        } else {
            wasInHypo = false
        }

        // Hyper
        if (cfg.hyperEnabled && mgdl > cfg.hyperThreshold) {
            val isNewEvent = !wasInHyper
            val dueRepeat = now - lastHyperAlarmAt >= repeatMs
            if (isNewEvent || dueRepeat) {
                HypoAlarm.trigger(ctx, HypoAlarm.Kind.Hyper, mgdl, cfg)
                lastHyperAlarmAt = now
            }
            wasInHyper = true
        } else {
            wasInHyper = false
        }
    }

    /** ECHTER Alarm-Selbsttest. Schickt einen künstlichen Unterzucker-Wert durch
     *  GENAU dieselbe Auswerte-Logik wie ein echter Sensor-Messwert
     *  (maybeTriggerAlarm): Schwellenvergleich, Wiederhol-Logik und Auslösen von
     *  Notification + Ton + Vibration. So ist sicher, dass der Alarm im Ernstfall
     *  wirklich kommt — es wird nichts „nur simuliert".
     *  @return Klartext-Rückmeldung fürs UI. */
    fun runAlarmSelfTest(): String {
        if (appCtx == null) return "App noch nicht bereit."
        val cfg = _settings?.state?.value ?: return "Einstellungen nicht geladen."
        if (!cfg.hypoEnabled) {
            return "Hypo-Alarm ist AUS. Bitte oben den Unterzucker-Alarm einschalten und erneut testen."
        }
        // Debounce zurücksetzen → der Test feuert garantiert sofort.
        wasInHypo = false
        lastHypoAlarmAt = 0L
        val testVal = (cfg.hypoThreshold - 5).coerceAtLeast(1)
        DebugLog.i(TAG, "Alarm-Selbsttest: $testVal mg/dL durch die echte Logik " +
            "(Schwelle ${cfg.hypoThreshold}, Ton=${cfg.alarmSound}, Vib=${cfg.alarmVibrate})")
        maybeTriggerAlarm(testVal)
        // Test-Zustand wieder neutralisieren, damit ein echter tiefer Wert
        // gleich wieder als neues Ereignis alarmiert (Test unterdrückt nichts).
        wasInHypo = false
        lastHypoAlarmAt = 0L
        val tonHint = if (cfg.alarmSound) "mit Ton" else "OHNE Ton (Ton-Schalter ist aus)"
        return "Selbsttest: $testVal mg/dL unter Schwelle ${cfg.hypoThreshold} mg/dL durch " +
            "die echte Alarm-Logik — Alarm $tonHint muss jetzt kommen."
    }

    private fun startForegroundService(ctx: Context) {
        val intent = Intent(ctx, G7MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun handleEvent(ev: CgmEvent) {
        val s = _state.value
        val r = readings
        _state.value = when (ev) {
            is CgmEvent.Scanning -> s.copy(
                connection = if (ev.active) ConnectionState.Scanning else s.connection,
                statusMessage = if (ev.active) "Scanne ..." else s.statusMessage
            )
            is CgmEvent.ScanHit -> {
                val line = "${ev.name ?: "<kein Name>"}  ${ev.address}  ${ev.rssi} dBm"
                val pruned = s.scanLog.filterNot { it.contains(ev.address) }
                s.copy(scanLog = (listOf(line) + pruned).take(8))
            }
            is CgmEvent.Connected -> s.copy(
                connection = ConnectionState.Connecting,
                deviceAddress = ev.address,
                statusMessage = "Verbunden: ${ev.address}"
            )
            is CgmEvent.Bonded -> s.copy(
                connection = ConnectionState.Bonded,
                statusMessage = "Gekoppelt"
            )
            is CgmEvent.HandshakeAdvanced -> s.copy(
                connection = ConnectionState.Authenticating,
                handshakePhase = ev.phase
            )
            is CgmEvent.Authenticated -> {
                DebugLog.event(TAG, "Verbindung OK${ev.deviceName?.let { " ($it)" } ?: ""}")
                s.copy(
                    connection = ConnectionState.Authenticated,
                    deviceName = ev.deviceName,
                    handshakePhase = -1,
                    statusMessage = "Authentifiziert (${ev.deviceName ?: "?"})"
                )
            }
            is CgmEvent.Glucose -> {
                lastRawGlucose = ev.reading.mgdl
                val cal = (ev.reading.mgdl + settings.state.value.calibOffset).coerceIn(10, 600)
                val p = ReadingsStore.Point(
                    timeMs = ev.reading.timestampMs,
                    mgdl = cal,
                    rateMgdlPerMin = ev.reading.rateMgdlPerMin,
                    sensor = settings.state.value.sensorType.tag,
                )
                val isNew = r?.append(p, knownTimes) ?: false
                if (isNew) knownTimes.add(p.timeMs)
                if (isNew && p.sensor == "aidex") aidexNewestStoredMs = maxOf(aidexNewestStoredMs, p.timeMs)
                val newHistory = if (isNew) (s.history + p).sortedBy { it.timeMs } else s.history
                if (isNew && s.history.size % 64 == 0) r?.compactIfNeeded()
                // Neuer Live-Wert — als sichtbares Event in den Debug-Tab.
                if (isNew) DebugLog.event(TAG, "Neuer Wert: $cal mg/dL")
                // Nur für frische Live-Werte einen Alarm auslösen — bei
                // Backfill-Paketen aus der Sensorhistorie wäre der Hypo-
                // Alarm zu alt, um noch zu handeln.
                if (isNew) maybeTriggerAlarm(cal)
                s.copy(
                    connection = ConnectionState.Receiving,
                    lastGlucose = cal,
                    lastGlucoseAt = ev.reading.timestampMs,
                    rateMgdlPerMin = ev.reading.rateMgdlPerMin,
                    statusMessage = "Wert empfangen",
                    history = newHistory,
                )
            }
            is CgmEvent.BackfillGlucose -> {
                val cal = (ev.reading.mgdl + settings.state.value.calibOffset).coerceIn(10, 600)
                val p = ReadingsStore.Point(
                    timeMs = ev.reading.timestampMs,
                    mgdl = cal,
                    rateMgdlPerMin = ev.reading.rateMgdlPerMin,
                    sensor = settings.state.value.sensorType.tag,
                )
                val isNew = r?.append(p, knownTimes) ?: false
                if (isNew) knownTimes.add(p.timeMs)
                if (isNew && p.sensor == "aidex") aidexNewestStoredMs = maxOf(aidexNewestStoredMs, p.timeMs)
                val newHistory = if (isNew) (s.history + p).sortedBy { it.timeMs } else s.history
                if (isNew && s.history.size % 64 == 0) r?.compactIfNeeded()
                s.copy(
                    history = newHistory,
                    statusMessage = if (isNew) "Backfill: $cal mg/dL" else s.statusMessage,
                )
            }
            is CgmEvent.StaleGlucose -> {
                val mins = ev.ageSec / 60
                s.copy(
                    statusMessage = "Letzter Sensorwert $mins min alt (${ev.mgdl} mg/dL) — Sensor prüfen"
                )
            }
            is CgmEvent.Disconnected -> {
                DebugLog.event(TAG, "Verbindung getrennt (status=${ev.status})")
                s.copy(
                    connection = ConnectionState.Idle,
                    statusMessage = "Getrennt (status=${ev.status})"
                )
            }
            is CgmEvent.Info -> s.copy(statusMessage = ev.message)
            is CgmEvent.Error -> {
                DebugLog.w(TAG, ev.message, ev.cause)
                s.copy(
                    connection = ConnectionState.Error,
                    statusMessage = "Fehler: ${ev.message}"
                )
            }
        }
    }
}
