/*
 * Minimal Dexcom G7 BLE handshake, ported from Juggluco (GPL-3.0).
 *  Original: Common/src/dex/java/tk/glucodata/DexGattCallback.java
 *  Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>
 *
 * This port intentionally drops EVERYTHING that isn't the G7 handshake
 * itself: alarms, WearOS, HealthConnect, broadcasts, Talker, XInfuus,
 * Gadgetbridge, backup. We only do scan -> connect -> handshake -> glucose.
 *
 * Lifecycle:
 *     val client = G7BleClient(context, applicatorPin)
 *     client.addListener { reading -> ... }
 *     client.start()   // keeps scanning/reconnecting until stop()
 *     client.stop()
 *
 * Required runtime permissions (Android 12+):
 *   BLUETOOTH_SCAN, BLUETOOTH_CONNECT  (request before start())
 */
@file:Suppress("DEPRECATION")
package com.g7monitor.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
import android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.g7monitor.ble.dexk.DexSession
import com.g7monitor.util.DebugLog
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

@SuppressLint("MissingPermission")
class G7BleClient(
    private val ctx: Context,
    private val applicatorPin: ByteArray,        // 4-byte PIN from applicator QR code
    private val serialHint: String? = null,      // optional: filter on a known serial prefix
) {
    private val tag = "G7BleClient"
    private val store = SensorStore(ctx)

    /** Observer. */
    fun interface Listener { fun onEvent(ev: Event) }
    sealed class Event {
        data class Scanning(val active: Boolean) : Event()
        /** Roher Treffer beim Scan — bevor Kandidatenfilter greift. */
        data class ScanHit(val name: String?, val address: String, val rssi: Int) : Event()
        data class Connected(val address: String) : Event()
        data class Bonded(val address: String) : Event()
        data class HandshakeAdvanced(val phase: Int) : Event()
        data class Authenticated(val deviceName: String?) : Event()
        /** Live-Messung: frisches 0x4E-Paket vom Sensor (age ≤ 15 min).
         *  Darf die große "aktueller Wert"-Anzeige aktualisieren. */
        data class Glucose(val reading: DexSession.Reading) : Event()
        /** Backfill-Row aus dem 24h-Ringpuffer des Sensors. Zeitstempel liegt
         *  in der Vergangenheit — gehört NICHT in die Live-Anzeige, nur in
         *  den Verlauf/Chart. */
        data class BackfillGlucose(val reading: DexSession.Reading) : Event()
        /** Nativer Code hat einen empfangenen Wert verworfen weil er zu alt war
         *  (z. B. Sensor liegt neben dem Körper, kein Filament-Kontakt). */
        data class StaleGlucose(val ageSec: Int, val mgdl: Int) : Event()
        data class Disconnected(val status: Int) : Event()
        data class Error(val message: String, val cause: Throwable? = null) : Event()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)
    private fun emit(ev: Event) = listeners.forEach {
        try { it.onEvent(ev) } catch (t: Throwable) { DebugLog.w(tag, "listener threw", t) }
    }

    // --- Session state ---------------------------------------------------

    private val running = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var dataptr: Long = 0L
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var deviceName: String? = null
    private val characteristic = arrayOfNulls<BluetoothGattCharacteristic>(4)

    // Handshake phase constants — MUST match ordering in DexGattCallback.java
    private val PH_NONE = -1
    private val PH_ROUND_START = 0
    private val PH_ROUND_1 = 1
    private val PH_ROUND_2 = 2
    private val PH_ROUND_3 = 3
    private val PH_REQUEST_AUTH = 4
    private val PH_CHALLENGE_REPLY = 5
    private val PH_SEND_CERT_0 = 6
    private val PH_SEND_CERT_1 = 7
    private val PH_SEND_CERT_2 = 8
    private val PH_SEND_KEY_CHALLENGE = 9
    private val PH_SEND_KEY_CHALLENGE_OUT = 10
    private val PH_GET_DATA = 11

    @Volatile private var phase = PH_NONE
    private val random8 = ByteArray(8)
    private var packet: ByteArray = ByteArray(0)
    private var startpacket = 0
    private var certinbuf = ByteArray(0)
    private var certinbufiter = 0
    private var certsize = 0x10000
    private var bonded = false
    private var hasService = false
    private var backfilled = false
    private var newCertificates = false

    // --- Live-vs-Backfill-Diskriminator -----------------------------------
    // Beide Pfade (dexcomProcessData für 0x4E + dexbackfill für 0x59) rufen
    // denselben Listener auf. Um die große "aktueller Wert"-Anzeige nur mit
    // ECHTEN Live-Werten zu füllen, setzen wir hier ein Flag um die
    // dexbackfill-Aufrufe herum. Die native-Callbacks sind synchron und
    // laufen auf dem gleichen Binder-Thread, also ist ein @Volatile reicht.
    @Volatile private var inBackfill: Boolean = false
    /** Zeitstempel des zuletzt empfangenen Live-0x4E-Pakets. Dient als
     *  Basis für die "haben wir eine Lücke?"-Entscheidung in askBackfill(). */
    @Volatile private var lastLiveMs: Long = 0L
    /** Zeitstempel des vorher empfangenen Live-Pakets — der Unterschied zum
     *  aktuellen verrät, ob wir zwischenzeitlich Werte verpasst haben. */
    @Volatile private var previousLiveMs: Long = 0L
    /** Wurde die komplette History in diesem App-Lauf schon geholt? Komplett-
     *  Backfill nur beim ersten Connect (App-Start) bzw. nach neuer Bindung;
     *  danach pro Reconnect nur die echte Lücke. */
    @Volatile private var initialBackfillDone: Boolean = false

    // --- Zombie-Bond-Detektor ---------------------------------------------
    // Wenn wir mit einer persistierten Session reinkommen (Auto-Reconnect) und
    // der Sensor uns mit status=19 sofort rauskickt, ohne dass ein Datenpaket
    // kam, ist der gespeicherte sharedKey obsolet. Ohne Gegenmaßnahme scannt
    // die App in einer Endlosschleife und kommt nie weiter. Nach 3 solchen
    // Fehlversuchen in 60 s werfen wir Session + Android-Bond weg und stoppen
    // den Client — der User sieht eine klare Fehlermeldung und kann neu pairen.
    @Volatile private var everGotData: Boolean = false
    private var reconnectFailures: Int = 0
    private var reconnectFailureWindowStart: Long = 0L

    // ---------------------------------------------------------------------
    // Write-Gate — Android's BluetoothGatt serialisiert writeCharacteristic,
    // d. h. ein neuer Write darf erst losgehen, wenn onCharacteristicWrite
    // für den vorigen gefeuert hat. Ohne dieses Gate bekamen wir rc=201
    // (GATT_BUSY / "prior command is not finished") — der Sensor bricht
    // daraufhin ~3 s später mit status=19 ab. Semaphore(1) + Release im
    // Callback erzwingt die Reihenfolge, statt auf sleep() zu vertrauen.
    // ---------------------------------------------------------------------
    private val writeGate = Semaphore(1, true)
    @Volatile private var writeGateHeld = false

    // Writes, die aus GATT-Callbacks (Binder-Thread) kommen, dürfen dort NICHT
    // auf dem Gate blockieren — sonst kann Android auf demselben Binder-Thread
    // weder onCharacteristicWrite noch andere Events dispatchen, wir warten
    // 2 s ins Leere, forcieren Release, feuern in einen noch offenen Write
    // rein (rc=201) und der Sensor bricht mit status=19 ab. Lösung: Ein
    // dedizierter Writer-Thread serialisiert die Writes, blockiert dort
    // problemlos auf dem Gate und lässt den Binder-Thread sofort zurückkehren.
    private val writeThread = HandlerThread("g7-writer").apply { start() }
    private val writeHandler = Handler(writeThread.looper)

    // Post-Bond-Kaskade: nach BOND_BONDED müssen drei CCCD-Writes sequentiell
    // ausgeführt werden (disable char[1] → disable char[3] → enable char[0]).
    // Android's GATT-Queue verarbeitet nur einen Descriptor-Write zur Zeit;
    // feuern wir sie ohne Warten auf onDescriptorWrite ab, gehen #2 und #3
    // mit GATT_BUSY (201) verloren und der Sensor disconnectet ~25 s später
    // mit status=19 (PEER_USER).
    private enum class PostBondStep { None, DisableChar1, DisableChar3, EnableChar0 }
    @Volatile private var postBondStep = PostBondStep.None
    private val bondHandled = AtomicBoolean(false)

    private val bleMgr by lazy {
        ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val adapter: BluetoothAdapter? get() = bleMgr.adapter

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    fun start() {
        if (!running.compareAndSet(false, true)) return
        require(applicatorPin.size == 4) { "PIN must be exactly 4 bytes" }
        // Forward glucose readings from native to our listeners. Ein einziger
        // Listener, der je nach Kontext (inBackfill) den passenden Event-Typ
        // erzeugt — so sieht der ViewModel klar, ob ein Wert live oder
        // historisch ist.
        Dex.setListener { r ->
            if (inBackfill) {
                emit(Event.BackfillGlucose(r))
            } else {
                previousLiveMs = lastLiveMs
                lastLiveMs = r.timestampMs
                emit(Event.Glucose(r))
            }
            // nach jedem Wert den Snapshot aktualisieren (pollcount/lastLifeCount ändern sich)
            persistSession()
        }
        Dex.setStaleListener { ageSec, mgdl ->
            emit(Event.StaleGlucose(ageSec, mgdl))
        }
        DebugLog.i(tag, "start: PIN gesetzt (${applicatorPin.size} B)")
        dataptr = Dex.sessionCreate(serialHint ?: "G7", applicatorPin)
        if (dataptr == 0L) {
            running.set(false)
            emit(Event.Error("Crypto-Initialisierung fehlgeschlagen (libcrypto.so nicht erreichbar)"))
            return
        }
        // Gespeicherte Session wiederherstellen — dann ist der Sensor bereits authentifiziert.
        val snap = store.load()
        if (snap != null) {
            val ok = try { Dex.sessionRestore(dataptr, snap.blob) } catch (_: Throwable) { false }
            if (ok) {
                val known = try { Dex.dexKnownSensor(dataptr) } catch (_: Throwable) { false }
                DebugLog.i(tag, "Persistierte Session geladen (dexKnownSensor=$known)")
            } else {
                DebugLog.w(tag, "sessionRestore() fehlgeschlagen — starte Pairing neu")
            }
        }
        // Krypto-Warmlauf im Hintergrund, während wir scannen — nimmt die
        // BouncyCastle-/JIT-Anlauflatenz aus dem späteren Handshake (Timing).
        Thread({ Dex.warmUp() }, "dex-warmup").start()
        registerBondReceiver()
        startScan()
    }

    /** Aktuellen Session-Snapshot auf Disk schreiben. Ruhig/idempotent. */
    private fun persistSession() {
        if (dataptr == 0L) return
        val blob = try { Dex.sessionBlob(dataptr) } catch (_: Throwable) { null } ?: return
        store.save(applicatorPin, blob)
    }

    /** Gespeicherte Session vergessen — z. B. bei Sensor-Wechsel.
     *  Räumt sowohl den persistenten Snapshot als auch den Android-Bond auf,
     *  damit der nächste Verbindungsversuch einen komplett frischen Handshake
     *  macht (und nicht in den "Zombie-Bond → Status 19"-Fall rennt). */
    fun forgetSensor() {
        store.clear()
        try { if (dataptr != 0L) Dex.dexResetKeys(dataptr) } catch (_: Throwable) {}
        // Android-Bond ebenfalls löschen — per Reflection, kein public API.
        try {
            val dev = device ?: gatt?.device
            if (dev != null && dev.bondState != BOND_NONE) {
                val m = dev.javaClass.getMethod("removeBond")
                m.invoke(dev)
                DebugLog.i(tag, "forgetSensor: removeBond() aufgerufen für ${dev.address}")
            }
        } catch (t: Throwable) { DebugLog.w(tag, "forgetSensor: removeBond fehlgeschlagen", t) }
        initialBackfillDone = false
        lastLiveMs = 0L; previousLiveMs = 0L
    }

    /** Hard-Reset für den Debug-Tab: räumt alles wegwerfbare weg, BEHÄLT
     *  aber den PIN — damit der User nach dem Reset nicht den Applicator-
     *  Code neu eingeben muss. Der Aufrufer (Repository.hardReset) startet
     *  danach scan+pairing neu.
     *
     *  Schritte:
     *   1. Scan stoppen + GATT-Connection schließen + Writer-Queue leeren
     *   2. Native dexResetKeys → Krypto-State weg
     *   3. Android-Bond per removeBond löschen (häufigste Ursache für
     *      status=19-Loops)
     *   4. Persistenten Session-Blob löschen, PIN aufheben
     *
     *  Anders als forgetSensor() OHNE Verlust des PINs — damit der nächste
     *  startScan() sofort den neuen J-PAKE durchläuft. */
    fun hardReset() {
        DebugLog.i(tag, "hardReset: starte vollständigen Reset (PIN bleibt erhalten)")
        // 1) Scan + Verbindung herunterfahren
        try { stopScan() } catch (_: Throwable) {}
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null
        // 2) Native-Keys zurücksetzen
        try { if (dataptr != 0L) Dex.dexResetKeys(dataptr) } catch (_: Throwable) {}
        // 3) Bond entfernen (häufigster Zombie-Bond-Verursacher)
        try {
            val dev = device ?: gatt?.device
            if (dev != null && dev.bondState != BOND_NONE) {
                val m = dev.javaClass.getMethod("removeBond")
                m.invoke(dev)
                DebugLog.i(tag, "hardReset: removeBond() für ${dev.address}")
            } else {
                DebugLog.i(tag, "hardReset: kein aktiver Bond zum Entfernen")
            }
        } catch (t: Throwable) { DebugLog.w(tag, "hardReset: removeBond fehlgeschlagen", t) }
        // 4) Persistenten Session-Blob verwerfen, PIN behalten
        try {
            store.clearSessionKeepPin()
            DebugLog.i(tag, "hardReset: Session-Blob verworfen, PIN behalten")
        } catch (t: Throwable) { DebugLog.w(tag, "hardReset: clearSessionKeepPin fehlgeschlagen", t) }
        // Zähler für Auto-Heil-Logik resetten — sonst greift der nach dem
        // ersten erneuten status=19 sofort wieder.
        reconnectFailures = 0
        reconnectFailureWindowStart = 0L
        // Neue Bindung steht an → beim nächsten Connect komplette History holen.
        initialBackfillDone = false
        lastLiveMs = 0L; previousLiveMs = 0L
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        stopScan()
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null
        device = null
        try { ctx.unregisterReceiver(bondReceiver) } catch (_: Throwable) {}
        if (dataptr != 0L) {
            Dex.sessionDestroy(dataptr)
            dataptr = 0L
        }
        // Writer-Thread sauber beenden — quitSafely wartet auf laufende Posts.
        try { writeThread.quitSafely() } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------------
    // Scan
    // ---------------------------------------------------------------------

    private val scanner get() = adapter?.bluetoothLeScanner
    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // RACE-Schutz: ScanSettings.CALLBACK_TYPE_ALL_MATCHES zustellt jede
            // einzelne Werbe-Wiederholung des Sensors. Während des Slot-Fensters
            // sind das mehrere pro Sekunde. Hat ein vorheriger Callback in
            // diesem Batch schon stopScan() + connectTo() ausgelöst, ist
            // `scanning` bereits false — dann darf hier KEIN weiterer Connect
            // mehr starten, sonst legen wir einen zweiten parallelen GATT-
            // Client auf dieselbe MAC, der Sensor lässt nur einen rein und
            // beide fliegen mit status=19 raus.
            if (!scanning) return
            val dev = result.device
            val name = result.scanRecord?.deviceName ?: dev.name
            val addr = dev.address ?: return
            // Der ServiceUUID-Filter hat schon vorgefiltert — hier kommen nur noch Dexcom-Werbungen an.
            DebugLog.i(tag, "Scan hit: name=${name ?: "<null>"} addr=$addr rssi=${result.rssi}")
            emit(Event.ScanHit(name, addr, result.rssi))

            // Name-Prefix zusätzlich prüfen, solange einer kommt. Bei null-Name vertrauen wir dem UUID-Filter.
            if (name != null && !isG7Name(name)) {
                Log.d(tag, "Scan hit abgelehnt (kein G7-Name): $name")
                return
            }
            // Native-Kandidat-Check — Juggluco nutzt das, um z. B. alte oder gebondete Sensoren auszuschließen.
            if (!Dex.dexCandidate(dataptr, name ?: "", addr)) {
                Log.d(tag, "Scan hit von dexCandidate abgelehnt: ${name ?: "<null>"} @ $addr")
                return
            }
            // Zweite Race-Prüfung direkt vor dem Commit: eine andere Stelle
            // (z. B. ein gleichzeitiger onConnectionStateChange-Reconnect) hätte
            // `scanning` zwischen Callback-Eintritt und hier umlegen können.
            if (!scanning) return
            DebugLog.i(tag, "Candidate bestätigt: ${name ?: "<nameless>"} @ $addr")
            stopScan()
            connectTo(dev, name ?: "G7")
        }
        override fun onScanFailed(errorCode: Int) {
            emit(Event.Error("Scan failed: code=$errorCode"))
        }
    }

    private fun isG7Name(name: String): Boolean {
        if (name.length < 4) return false
        return DexCerts.NAME_PREFIXES.any { name.startsWith(it) }
    }

    private fun startScan() {
        val s = scanner ?: run { emit(Event.Error("BLE scanner unavailable")); return }
        // ServiceUUID-Filter — so wie Juggluco. Damit kommen NUR Dexcom-Werbungen im Callback an,
        // keine fremden BLE-Geräte. Der G7 sendet im Idle ungefähr alle 5 min ein Advertisement,
        // im aktiven Zustand öfter — der Scan läuft weiter, bis stop() gerufen wird oder ein
        // Kandidat akzeptiert wurde.
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(DexCerts.SCAN_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanning = true
        emit(Event.Scanning(true))
        DebugLog.i(tag, "startScan (ServiceUUID=${DexCerts.SCAN_SERVICE_UUID}, LOW_LATENCY)")
        try {
            s.startScan(listOf(filter), settings, scanCallback)
        } catch (t: Throwable) {
            scanning = false
            emit(Event.Error("startScan() warf: ${t.message}", t))
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { scanner?.stopScan(scanCallback) } catch (_: Throwable) {}
        emit(Event.Scanning(false))
    }

    // ---------------------------------------------------------------------
    // Connect + GATT callback
    // ---------------------------------------------------------------------

    private fun connectTo(dev: BluetoothDevice, name: String) {
        device = dev
        deviceName = name
        resetConnectionState()
        gatt = dev.connectGatt(ctx, /* autoConnect = */ false, gattCallback, TRANSPORT_LE)
    }

    private fun resetConnectionState() {
        phase = PH_NONE
        hasService = false
        bonded = false
        backfilled = false
        newCertificates = false
        certsize = 0x10000
        // inBackfill pro Verbindung zurücksetzen. lastLiveMs/previousLiveMs
        // bleiben über Reconnects ERHALTEN — nur so erkennt askBackfill() eine
        // echte Lücke (Reconnect-Pause) und holt gezielt Backfill, statt bei
        // jedem Connect die ganze History neu zu ziehen.
        inBackfill = false
        // `everGotData` NICHT hier zurücksetzen — der Flag lebt über den
        // ganzen Client-Lebenszyklus, weil wir damit entscheiden, ob ein
        // Reconnect-Fehler als "Zombie-Bond-Symptom" zählt oder nicht.
        for (i in characteristic.indices) characteristic[i] = null
        // Write-Gate in sauberen Zustand bringen (falls ein Write mitten in der
        // vorigen Session auf den Callback gewartet hatte, der nie kam).
        if (writeGateHeld) { writeGateHeld = false; writeGate.release() }
        writeGate.drainPermits()
        writeGate.release()
        // Post-Bond-Zustand und Poll-Timer aufräumen.
        postBondStep = PostBondStep.None
        bondHandled.set(false)
        handler.removeCallbacks(bondPollRunnable)
        // Alle offenen Write-Posts verwerfen — sonst feuert ein verspäteter
        // Post in die neue Session (mit falschem characteristic).
        writeHandler.removeCallbacksAndMessages(null)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val bondState = g.device.bondState
            DebugLog.i(tag, "onConnectionStateChange status=$status newState=$newState bond=$bondState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emit(Event.Connected(g.device.address))
                if (bondState == BOND_BONDING) return      // wait for bondReceiver
                bonded = bondState == BOND_BONDED
                // --- "Zombie-Bond"-Schutz (NUR nach echten Fehlversuchen!) ---
                // Wenn Android das Gerät schon als gebondet kennt, aber unsere
                // native Session KEINEN sharedKey hat, dann würden wir einen
                // frischen J-PAKE berechnen, der Sensor aber seinen alten Key
                // benutzen → Status 19 (PEER_USER disconnect). Lösung: Android-
                // Bond wegwerfen und sauber neu koppeln lassen.
                //
                // ABER: Beim FRISCH-Koppeln ist "Bond gebildet, Session noch nicht
                // authentifiziert" der NORMALZUSTAND (der Sensor trennt direkt nach
                // dem Bonden kurz und verbindet neu). Früher haben wir hier den
                // gerade frisch gebildeten Bond sofort wieder weggeworfen → Endlos-
                // Schleife mit status=19, der Sensor koppelte NIE. Darum nur dann
                // entfernen, wenn wir vorher schon echte Fehlversuche gesehen haben
                // (reconnectFailures >= 2) — also wirklich ein veralteter Bond ist.
                if (bondState == BOND_BONDED && dataptr != 0L &&
                    !Dex.isAuthenticated(dataptr) && store.exists()) {
                    DebugLog.w(tag, "veralteter Bond bei bekanntem Sensor → removeBond() und Neuverbindung")
                    try {
                        val m = g.device.javaClass.getMethod("removeBond")
                        m.invoke(g.device)
                    } catch (t: Throwable) {
                        DebugLog.w(tag, "removeBond() per Reflection fehlgeschlagen", t)
                    }
                    try { g.close() } catch (_: Throwable) {}
                    gatt = null
                    // nach ~1s neu scannen/verbinden — der Bond-Remove ist async
                    handler.postDelayed({ if (running.get()) startScan() }, 400)
                    return
                }
                // --- BLE-Leitung aufdrehen (Juggluco-Pattern für G7): ---
                // 1) Connection-Interval kurz halten (7.5-15 ms statt 30-50 ms)
                try {
                    val okPrio = g.requestConnectionPriority(
                        BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    DebugLog.i(tag, "requestConnectionPriority(HIGH) -> $okPrio")
                } catch (t: Throwable) { DebugLog.w(tag, "requestConnectionPriority failed", t) }
                // 2) 2M-PHY (doppelte Datenrate auf der Funkschicht, wenn unterstützt)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        g.setPreferredPhy(
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                        DebugLog.i(tag, "setPreferredPhy(2M) requested")
                    } catch (t: Throwable) { DebugLog.w(tag, "setPreferredPhy failed", t) }
                }
                // 3) MTU anheben — erst in onMtuChanged() rufen wir discoverServices()
                val okMtu = g.requestMtu(512)
                DebugLog.i(tag, "requestMtu(512) -> $okMtu")
                if (!okMtu) {
                    // Fallback: trotzdem weitermachen
                    if (!g.discoverServices()) emit(Event.Error("discoverServices() failed"))
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                emit(Event.Disconnected(status))
                try { g.close() } catch (_: Throwable) {}
                gatt = null
                // Zombie-Bond-Check: Disconnect mit Fehler-Status UND wir haben
                // seit start() noch NIE ein Datenpaket gesehen → zählen.
                // status=19 (PEER_USER) ist der klassische "Sensor kennt
                // unseren sharedKey nicht"-Fall; andere non-zero stati können
                // aber auch dahin führen, also zählen wir alles was != 0 ist.
                if (status != GATT_SUCCESS && !everGotData) {
                    val now = System.currentTimeMillis()
                    // 30-Min-Fenster: Der G7 meldet sich nur alle ~5 Min, ein
                    // 60-s-Fenster setzte den Zähler JEDES Mal zurück (für immer
                    // "#1") → die Bond-Eskalation griff nie. Jetzt zählen wir
                    // Fehlversuche über mehrere Zyklen hinweg (wie das Original).
                    if (now - reconnectFailureWindowStart > 30 * 60_000L) {
                        reconnectFailureWindowStart = now
                        reconnectFailures = 0
                    }
                    reconnectFailures++
                    DebugLog.w(tag, "Reconnect-Fehlversuch #$reconnectFailures (status=$status)")
                    // Nach 3 Fehlversuchen den (evtl. veralteten/halben) Bond aktiv
                    // lösen — wie Jugglucos triedinvain→unbond.
                    // ABER NUR bei einem BEKANNTEN Sensor (store.exists()): nur dann
                    // gibt es einen ALTEN Bond, der veraltet sein kann. Bei einem
                    // FRISCHEN Sensor ist noch keine Session gespeichert → store.exists()
                    // = false → wir dürfen den gerade entstehenden Bond NICHT löschen,
                    // sonst zerstören wir die Erstkopplung (status=19-Endlosschleife).
                    // Genau dieses `store.exists()` hatte die funktionierende Version.
                    if (reconnectFailures >= 3 && store.exists()) {
                        DebugLog.w(tag, "3 Fehlversuche bei bekanntem Sensor — Bond lösen + neu koppeln")
                        reconnectFailures = 0
                        reconnectFailureWindowStart = now
                        autoHealZombieBond(g.device)   // removeBond + verzögerter Rescan
                        return
                    }
                }
                if (running.get()) {
                    // Reconnect: scan again (G7 advertises every 5 min when idle).
                    handler.postDelayed({ if (running.get()) startScan() }, 500)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            DebugLog.i(tag, "onMtuChanged mtu=$mtu status=$status")
            // Service-Discovery erst jetzt starten — so wie Juggluco es macht.
            if (!g.discoverServices()) emit(Event.Error("discoverServices() failed"))
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            DebugLog.i(tag, "onPhyUpdate tx=$txPhy rx=$rxPhy status=$status")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != GATT_SUCCESS) { g.disconnect(); return }
            val service = g.getService(UUID.fromString(DexCerts.SERVICE_UUID)) ?: run {
                emit(Event.Error("G7 service not found")); g.disconnect(); return
            }
            for (i in DexCerts.CHAR_UUIDS.indices) {
                characteristic[i] = service.getCharacteristic(UUID.fromString(DexCerts.CHAR_UUIDS[i])) ?: run {
                    emit(Event.Error("characteristic $i missing")); g.disconnect(); return
                }
            }
            enableNotification(g, characteristic[3]!!)       // start with cert transport
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Gate freigeben — der Stack ist jetzt bereit für den nächsten
            // GATT-Befehl (Char- oder Descriptor-Write). Erst HIER releasen,
            // sonst läuft der nächste gatedWriteDescriptor sofort gegen ein
            // noch in-flight-Kommando und Android meldet "prior command is
            // not finished". Auch bei status!=SUCCESS releasen, sonst hängt
            // der Sender-Thread bis zum 2-s-Timeout.
            if (writeGateHeld) { writeGateHeld = false; writeGate.release() }
            if (status != GATT_SUCCESS) { g.disconnect(); return }
            val ch = descriptor.characteristic

            // --- Post-Bond-Kaskade hat Priorität ---------------------------
            // Wenn wir uns mitten in der Post-Bond-Sequenz befinden, steuern
            // wir hier den nächsten Descriptor-Write erst nach der Quittung
            // des vorigen an.
            if (postBondStep != PostBondStep.None) {
                when (postBondStep) {
                    PostBondStep.DisableChar1 -> if (ch == characteristic[1]) {
                        postBondStep = PostBondStep.DisableChar3
                        DebugLog.i(tag, "post-bond: char[1] disabled, disable char[3]")
                        characteristic[3]?.let { disableNotification(g, it) }
                        return
                    }
                    PostBondStep.DisableChar3 -> if (ch == characteristic[3]) {
                        postBondStep = PostBondStep.EnableChar0
                        DebugLog.i(tag, "post-bond: char[3] disabled, enable char[0]")
                        getDataCmd()
                        return
                    }
                    PostBondStep.EnableChar0 -> if (ch == characteristic[0]) {
                        postBondStep = PostBondStep.None
                        DebugLog.i(tag, "post-bond: char[0] enabled, sende 0x4E")
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        write(0, byteArrayOf(0x4E))
                        return
                    }
                    PostBondStep.None -> {}
                }
            }

            when (ch) {
                characteristic[3] -> {
                    hasService = true
                    enableIndication(g, characteristic[1]!!)
                }
                characteristic[0] -> {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    write(0, byteArrayOf(0x4E))              // ask for glucose
                }
                characteristic[2] -> {
                    backfilled = true
                    askBackfill()
                }
                characteristic[1] -> {
                    if (phase < PH_REQUEST_AUTH) {
                        if (bonded && Dex.isAuthenticated(dataptr)) {
                            phase = PH_REQUEST_AUTH
                            requestAuth()
                        } else {
                            docmd0()
                        }
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            // WICHTIG: Das Gate NUR hier freigeben — erst jetzt ist der
            // Stack bereit, den nächsten writeCharacteristic-Call zu
            // akzeptieren. status!=GATT_SUCCESS wird geloggt, aber wir
            // releasen trotzdem, sonst hängt der Sender-Thread bis zum
            // Timeout.
            if (status != GATT_SUCCESS) {
                DebugLog.w(tag, "onCharacteristicWrite status=$status ch=${ch.uuid}")
            }
            if (writeGateHeld) {
                writeGateHeld = false
                writeGate.release()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (ch) {
                characteristic[0] -> onGlucoseNotify(value)
                characteristic[1] -> authenticate(value)
                characteristic[2] -> {
                    // Kontext-Flag setzen, damit der Listener weiß, dass die
                    // gleich folgenden synchronen nativeDeliverReading-Aufrufe
                    // aus dem Backfill-Ringpuffer kommen — nicht live sind.
                    DebugLog.i(tag, "char[2] backfill-Paket: ${value.size} B (erstes Byte=0x${"%02x".format(value[0])})")
                    inBackfill = true
                    try { Dex.dexbackfill(dataptr, value) }
                    finally { inBackfill = false }
                }
                characteristic[3] -> onCertBytes(value)
            }
        }

        @Deprecated("API <33 path")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            onCharacteristicChanged(g, ch, ch.value ?: return)
        }
    }

    // ---------------------------------------------------------------------
    // Bonding — Dexcom triggers pairing mid-handshake via {0x06, 0x19} etc.
    // ---------------------------------------------------------------------

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val dev: BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val d = device ?: return
            if (dev?.address != d.address) return
            val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BOND_NONE)
            DebugLog.i(tag, "bond state -> $state")
            if (state == BOND_BONDED) startPostBondSequence(d)
        }
    }

    /** Wird entweder vom Receiver oder vom Bond-Poll gerufen. Idempotent. */
    private fun startPostBondSequence(d: BluetoothDevice) {
        if (!bondHandled.compareAndSet(false, true)) return
        handler.removeCallbacks(bondPollRunnable)
        bonded = true
        emit(Event.Bonded(d.address))
        // nach erfolgreichem Bond: Session persistieren (noch vor den ersten Daten)
        persistSession()
        val g = gatt ?: return
        // Serialisierte Kaskade: disable char[1] → disable char[3] → enable char[0].
        // Die nächsten Schritte passieren in onDescriptorWrite, sobald Android
        // die jeweilige CCCD-Write quittiert hat.
        postBondStep = PostBondStep.DisableChar1
        characteristic[1]?.let { disableNotification(g, it) }
    }

    /** Safety-Net: manche OEM-Builds verpassen BOND_STATE_CHANGED-Broadcasts.
     *  Wir poll'en den Bond-Status ab Eintritt in PH_SEND_KEY_CHALLENGE_OUT,
     *  damit die Post-Bond-Kaskade trotzdem gestartet wird. */
    private val bondPollRunnable = object : Runnable {
        override fun run() {
            if (!running.get() || bondHandled.get()) return
            val d = device ?: return
            when (d.bondState) {
                BOND_BONDED -> {
                    DebugLog.i(tag, "Bond-Poll: BONDED erkannt (Receiver vermutlich verpasst)")
                    startPostBondSequence(d)
                }
                BOND_BONDING, BOND_NONE -> handler.postDelayed(this, 500)
            }
        }
    }
    private fun registerBondReceiver() {
        val f = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(bondReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(bondReceiver, f)
        }
    }

    // ---------------------------------------------------------------------
    // Handshake state transitions
    // ---------------------------------------------------------------------

    /** Kick off round 0: ask for the first pubkey. */
    private fun docmd0() {
        certinbufiter = 0
        certinbuf = ByteArray(160)
        phase = PH_ROUND_1
        emit(Event.HandshakeAdvanced(phase))
        cmd(0x0)
    }

    private fun requestAuth() {
        Random.nextBytes(random8)
        val uit = ByteArray(10)
        System.arraycopy(random8, 0, uit, 1, 8)
        uit[0] = 0x02; uit[9] = 0x02
        characteristic[1]?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        write(1, uit)
    }

    /** Incoming bytes on the cert channel (characteristic[3]). */
    private fun onCertBytes(value: ByteArray) {
        System.arraycopy(value, 0, certinbuf, certinbufiter, value.size)
        certinbufiter += value.size
        // Kein Per-Chunk-Log mehr — Handshake ist stabil, interessiert nur
        // noch das Ende (siehe "Certificate X komplett"-Logs weiter unten).
        when (phase) {
            PH_ROUND_1, PH_ROUND_2, PH_ROUND_3 -> {
                if (certinbufiter == 160) {
                    val fromRound1 = phase - PH_ROUND_1
                    DebugLog.i(tag, "dexPutPubKey round=${fromRound1 + 1}")
                    Dex.dexPutPubKey(dataptr, fromRound1, certinbuf)
                    if (phase < PH_ROUND_3) {
                        DebugLog.i(tag, "sendPacket round=${fromRound1 + 1}")
                        sendPacket(fromRound1)
                    } else {
                        DebugLog.i(tag, "makeRound3bytes …")
                        packet = Dex.makeRound3bytes(dataptr)
                            ?: run {
                                DebugLog.w(tag, "makeRound3bytes returned null — resetCerts")
                                resetCerts(); return
                            }
                        DebugLog.i(tag, "Round3-Paket bereit (${packet.size} B), setze WRITE_TYPE_NO_RESPONSE und sende")
                        // WICHTIG: writeType für char[3] sicherstellen (wurde in sendPacket
                        // für Round 1/2 gesetzt, aber Round 3 kommt ohne sendPacket an).
                        characteristic[3]?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        startpacket = 0
                        sendCerts()
                    }
                }
            }
            PH_SEND_CERT_1, PH_SEND_CERT_2 -> {
                if (certinbufiter >= certsize) {
                    val idx = phase - PH_SEND_CERT_1
                    DebugLog.i(tag, "Certificate $idx komplett (${certinbufiter}/$certsize) — sende eigenes Cert")
                    startpacket = 0
                    packet = DexCerts.CERTS[idx]
                    sendCerts()
                }
            }
            PH_SEND_KEY_CHALLENGE -> {
                if (certinbufiter == 64) { Log.d(tag, "SendKeyChallenge: 64 B empfangen") }
            }
            else -> Log.d(tag, "onCertBytes ignoriert (Phase $phase)")
        }
    }

    /** Incoming bytes on the auth channel (characteristic[1]). */
    private fun authenticate(value: ByteArray) {
        val d = device ?: return
        val bondState = d.bondState
        // Auth-Frame-Header nur noch in kompakter Form (kein Hex-Dump außer
        // bei Problemen).
        if (bondState != BOND_BONDED) {
            // Some Dexcom prompts mean "please start pairing now"
            val bondBytes = arrayOf(
                byteArrayOf(0x06, 0x19),
                byteArrayOf(0xFF.toByte(), 0x06, 0x01),
                byteArrayOf(0x06, 0x00)
            )
            for (b in bondBytes) if (value.contentEquals(b)) {
                DebugLog.i(tag, "bond-Trigger vom Sensor → createBond()")
                createBondTransportLE(d); break
            }
        }
        when (phase) {
            PH_REQUEST_AUTH -> {
                val aes = ByteArray(8)
                val ok = Dex.dex8AES(dataptr, random8, 0, aes, 0)
                val verified = ok && value.size >= 9 && rangeEquals(aes, value, 1)
                DebugLog.i(tag, "PH_REQUEST_AUTH verified=$verified")
                if (!verified) {
                    DebugLog.w(tag, "Auth fehlgeschlagen → resetCerts() + disconnect")
                    resetCerts(); return
                }
                phase++
                val dataaes = ByteArray(9)
                Dex.dex8AES(dataptr, value, 9, dataaes, 1)
                dataaes[0] = 0x04
                write(1, dataaes)
            }
            PH_CHALLENGE_REPLY -> {
                var auth = 0; var bond = 0
                if (value.size >= 3 && value[0] == 0x05.toByte()) { auth = value[1].toInt(); bond = value[2].toInt() }
                if (bond == 3) { resetCerts(); return }
                val isBonded = bond == 1
                if (!newCertificates && auth != 1) resetCerts()
                else if (auth == 1 && isBonded || (bonded && bond == 2)) getDataCmd()
                else askCertificate(PH_SEND_CERT_1)
            }
            PH_SEND_CERT_1, PH_SEND_CERT_2 -> {
                certsize = Dex.getDexCertSize(value)
                if (certsize < 0) resetCerts()
            }
            PH_SEND_KEY_CHALLENGE -> {
                startpacket = 0
                packet = Dex.dexChallenger(value)
                sendCerts()
            }
            PH_SEND_KEY_CHALLENGE_OUT -> {
                saveDeviceName()
                phase = PH_GET_DATA
                emit(Event.Authenticated(deviceName))
                write(1, byteArrayOf(0x06, 0x19))
                // Pairing fertig — sharedKey + DexDeviceName sind jetzt gültig.
                // Direkt persistieren, damit beim nächsten App-Start kein Re-Pairing nötig ist.
                persistSession()
                // Bond-Poll als Fallback starten — falls der BOND_STATE_CHANGED-
                // Broadcast verschluckt wird (seltener OEM-Bug), löst der Poll
                // die Post-Bond-Kaskade trotzdem aus, bevor der Sensor nach
                // ~25 s mit status=19 abbricht.
                handler.removeCallbacks(bondPollRunnable)
                handler.postDelayed(bondPollRunnable, 1500)
            }
            else -> {
                if (value.contentEquals(byteArrayOf(0x06, 0x01)) ||
                    (phase == PH_GET_DATA && d.bondState == BOND_BONDED)) {
                    getDataCmd()
                }
            }
        }
    }

    private fun askCertificate(pha: Int) {
        phase = pha
        val idx = pha - PH_SEND_CERT_1
        certinbufiter = 0
        val len = DexCerts.CERTS[idx].size
        certsize = len
        certinbuf = ByteArray(len)
        write(1, byteArrayOf(0x0B, idx.toByte(),
            (len and 0xFF).toByte(), (len ushr 8 and 0xFF).toByte(),
            (len ushr 16 and 0xFF).toByte(), (len ushr 24 and 0xFF).toByte()))
    }

    private fun sendPacket(which: Int): Boolean {
        val p = Dex.makeRound12bytes(dataptr, which) ?: run { resetCerts(); return false }
        packet = p
        startpacket = 0
        characteristic[3]?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        sendCerts()
        return true
    }

    /** Streams `packet` in 20-byte chunks on characteristic[3] with 40ms spacing. */
    private fun sendCerts() {
        val entryPhase = phase
        DebugLog.i(tag, "sendCerts start: phase=$entryPhase packet=${packet.size} B")
        Thread({
            try {
                var chunkIndex = 0
                while (startpacket < packet.size) {
                    // TIMING: 40 ms → 12 ms Chunk-Pause. Bei ~5 Certs × 8 Chunks
                    // spart das ~1,1 s reines Warten im Handshake — entscheidend,
                    // damit Kotlin das Gesamt-Zeitfenster des Sensors einhält (nativ
                    // ist insgesamt schneller). Falls der BLE-Puffer (NO_RESPONSE)
                    // mal voll ist, kurz warten + erneut, statt abzubrechen.
                    sleep(15)
                    val oldStart = startpacket
                    startpacket += 20
                    if (startpacket > packet.size) startpacket = packet.size
                    val slice = packet.copyOfRange(oldStart, startpacket)
                    // writeSync direkt — der sendCerts-Thread ist nicht der
                    // Binder-Thread, darf also auf dem Gate blockieren und
                    // braucht den echten Rückgabewert für die Retry-Logik.
                    var ok = writeSync(3, slice)
                    var tries = 0
                    while (!ok && tries < 6) { sleep(15); ok = writeSync(3, slice); tries++ }
                    if (!ok) {
                        startpacket = oldStart
                        DebugLog.w(tag, "sendCerts: chunk $chunkIndex write(3) FAIL — Abbruch")
                        break
                    }
                    chunkIndex++
                }
                DebugLog.i(tag, "sendCerts: $chunkIndex chunks gesendet, phase=$phase")
                if (phase in PH_ROUND_1 until PH_ROUND_3) {
                    certinbufiter = 0
                    sleep(10)
                    val next = phase + 1
                    cmd(phase)
                    phase = next
                    DebugLog.i(tag, "sendCerts: Round-Übergang → phase=$phase")
                } else {
                    val newPhase = ++phase
                    DebugLog.i(tag, "sendCerts: Übergang ins when, neue phase=$newPhase")
                    when (newPhase) {
                        PH_SEND_KEY_CHALLENGE_OUT -> {
                            // Früher: rc=201 weil write(1, ...) direkt nach
                            // dem letzten write(3, ...) abgefeuert wurde, ohne
                            // auf onCharacteristicWrite zu warten. Jetzt
                            // blockiert write() selbst über writeGate, bis der
                            // vorige Write quittiert ist — sleep() ist nicht
                            // mehr nötig. Retry bleibt als Absicherung, falls
                            // der Stack trotzdem mal zickt.
                            val ok = write(1, byteArrayOf(0x0D, 0x00, 0x02))
                            DebugLog.i(tag, "PH_SEND_KEY_CHALLENGE_OUT write(1, 0D0002) ok=$ok")
                            if (!ok) {
                                sleep(80)
                                val retry = write(1, byteArrayOf(0x0D, 0x00, 0x02))
                                DebugLog.w(tag, "PH_SEND_KEY_CHALLENGE_OUT retry=$retry")
                            }
                        }
                        PH_REQUEST_AUTH -> {
                            DebugLog.i(tag, "sendCerts -> requestAuth()")
                            newCertificates = true; sleep(10); requestAuth()
                        }
                        PH_SEND_CERT_2 -> { sleep(10); askCertificate(PH_SEND_CERT_2) }
                        PH_SEND_KEY_CHALLENGE -> { sleep(10); sendKeyChallenge() }
                        else -> DebugLog.w(tag, "sendCerts: unerwartete phase=$newPhase")
                    }
                }
            } catch (t: Throwable) {
                DebugLog.e(tag, "sendCerts Thread warf", t)
                emit(Event.Error("sendCerts", t))
            }
        }, "g7-sendcerts").start()
    }

    private fun sendKeyChallenge() {
        phase = PH_SEND_KEY_CHALLENGE
        certinbufiter = 0
        certinbuf = ByteArray(64)
        val buf = ByteArray(17)
        Random.nextBytes(buf)
        buf[0] = 0x0C
        write(1, buf)
    }

    private fun getDataCmd() {
        phase = PH_GET_DATA
        val g = gatt ?: return
        val ch = characteristic[0] ?: return
        enableNotification(g, ch)
    }

    private fun saveDeviceName() {
        deviceName?.let { Dex.dexSaveDeviceName(dataptr, it) }
    }

    private fun resetCerts() {
        Dex.dexResetKeys(dataptr)
        try { gatt?.disconnect() } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------------
    // Data channel
    // ---------------------------------------------------------------------

    private fun onGlucoseNotify(value: ByteArray) {
        // Erstes Datenpaket nach Connect → Auto-Heil-Zähler zurücksetzen.
        // Ab jetzt ist klar: der Sensor akzeptiert unsere Session, kein Zombie.
        if (!everGotData) {
            everGotData = true
            reconnectFailures = 0
            DebugLog.i(tag, "erstes Datenpaket empfangen — Auto-Heil-Zähler reset")
        }
        when (value[0]) {
            0x4E.toByte() -> {
                val timeres = longArrayOf(System.currentTimeMillis(), 0L)
                val saveName = Dex.dexcomProcessData(dataptr, value, timeres)
                if (saveName) saveDeviceName()
                // glucose is delivered via the listener from native onGlucose upcall
                handler.postDelayed({ askBackfill() }, 10)
            }
            0x59.toByte() -> {
                DebugLog.i(tag, "char[0] 0x59 backfill-Endmarker empfangen")
                Dex.dexEndBackfill(dataptr)
            }
            else -> {
                DebugLog.i(tag, "char[0] unbekanntes Paket erstes Byte=0x${"%02x".format(value[0])} (${value.size} B)")
            }
        }
    }

    /** Zombie-Bond-Fall: Session-Blob passt nicht mehr zum Sensor. Den Blob
     *  verwerfen + Android-Bond wegräumen + NEU pairen — die PIN aus dem Store
     *  bleibt erhalten (die ist vom Applicator-Label, nicht session-gebunden).
     *  Der User merkt davon nur eine kurze Statusmeldung, muss aber nichts tun. */
    private fun autoHealZombieBond(dev: BluetoothDevice?) {
        try { store.clearSessionKeepPin() } catch (_: Throwable) {}
        try { if (dataptr != 0L) Dex.dexResetKeys(dataptr) } catch (_: Throwable) {}
        try {
            if (dev != null && dev.bondState != BOND_NONE) {
                val m = dev.javaClass.getMethod("removeBond")
                m.invoke(dev)
                DebugLog.i(tag, "autoHeal: removeBond() für ${dev.address}")
            }
        } catch (t: Throwable) { DebugLog.w(tag, "autoHeal: removeBond fehlgeschlagen", t) }
        // Zähler zurücksetzen — sonst greift der Heal gleich nochmal, falls
        // der neue Pairing-Versuch anlaufen darf.
        reconnectFailures = 0
        reconnectFailureWindowStart = 0L
        // Neu-Pairing → komplette History beim nächsten erfolgreichen Connect.
        initialBackfillDone = false
        lastLiveMs = 0L; previousLiveMs = 0L
        emit(Event.Error(
            "Gespeicherte Session ungültig — pairen automatisch neu"
        ))
        // removeBond ist async — eine kurze Pause, dann frischer Scan. Mit
        // erhaltenem PIN und ohne alten Blob läuft der normale Pairing-Flow
        // (J-PAKE-Handshake), ohne dass der User etwas tippen muss.
        handler.postDelayed({
            if (running.get()) startScan()
        }, 1500)
    }

    private fun askBackfill() {
        val g = gatt ?: run { DebugLog.i(tag, "askBackfill: gatt null → skip"); return }
        if (!backfilled) {
            // Erstkontakt nach Connect: Notifications auf char[2] aktivieren.
            // Der eigentliche Backfill wird in onDescriptorWrite ausgelöst.
            DebugLog.i(tag, "askBackfill: char[2] noch nicht aktiv → enableNotification")
            characteristic[2]?.let { enableNotification(g, it) }
            return
        }
        // Komplette History nur EINMAL pro App-Lauf bzw. nach neuer Bindung.
        // Danach bei Reconnects nur nachfordern, wenn wirklich eine Lücke
        // besteht — normale ~5-Minuten-Reconnects haben keine, sonst gäbe es
        // nur Duplikate (und Gefahr des ~3-Row-Abbruchs mit status=19).
        if (!initialBackfillDone) {
            initialBackfillDone = true
            DebugLog.i(tag, "askBackfill: erster Lauf/neue Bindung → komplette History")
        } else {
            // lastLiveMs/previousLiveMs bleiben über Reconnects erhalten → die
            // Lücke ist die echte Reconnect-Pause.
            val gapMs = if (previousLiveMs > 0L) lastLiveMs - previousLiveMs else 0L
            if (gapMs in 0..6L * 60L * 1000L) {
                DebugLog.i(tag, "askBackfill: keine Lücke (${gapMs / 1000}s) → skip")
                return
            }
            DebugLog.i(tag, "askBackfill: Lücke ${gapMs / 1000}s → Backfill nachfordern")
        }
        val cmd = Dex.getDexbackfillcmd(dataptr) ?: run {
            DebugLog.w(tag, "askBackfill: getDexbackfillcmd lieferte null → kein Befehl")
            return
        }
        DebugLog.i(tag, "askBackfill: sende getDexbackfillcmd (${cmd.size} B)")
        write(0, cmd)
    }

    // ---------------------------------------------------------------------
    // GATT helpers
    // ---------------------------------------------------------------------

    private fun cmd(num: Int) {
        val ch = characteristic[1] ?: return
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        write(1, byteArrayOf(0x0A, num.toByte()))
    }

    /**
     * Öffentliche write-Methode: aus einem GATT-Callback heraus NIEMALS direkt
     * schreiben — stattdessen auf den Writer-Thread posten, damit der Binder
     * sofort zurückkehren und den vorigen `onCharacteristicWrite`-Callback
     * dispatchen kann.
     *
     * Rückgabewert: `true` heißt "gequeued", NICHT "geschrieben". Wer den
     * echten Ausgang braucht (z. B. sendCerts für seinen Retry), ruft
     * [writeSync] direkt — das darf aber nur von eigenen Worker-Threads
     * passieren, nicht vom Binder.
     */
    private fun write(nr: Int, data: ByteArray): Boolean {
        if (Thread.currentThread() === writeThread) return writeSync(nr, data)
        writeHandler.post { writeSync(nr, data) }
        return true
    }

    private fun writeSync(nr: Int, data: ByteArray): Boolean {
        val g = gatt ?: run { DebugLog.w(tag, "write($nr): gatt null"); return false }
        val ch = characteristic[nr] ?: run { DebugLog.w(tag, "write($nr): characteristic null"); return false }
        // Gate-acquire: warte auf Quittung des vorherigen Writes. 2 s reichen
        // für den Handshake (realer Callback-Delay ist <50 ms) und verhindern
        // gleichzeitig das Hängenbleiben, falls Android mal keinen Callback
        // liefert (z. B. Disconnect mitten im Write).
        val acquired = try {
            writeGate.tryAcquire(2000, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }
        if (!acquired) {
            DebugLog.w(tag, "write($nr): gate timeout — forciere Release")
            // Safety-Net: wenn wir nach 2 s keinen Callback gesehen haben,
            // reset den Gate-Zustand und mach trotzdem weiter — besser ein
            // evtl. rc=201 als ein Deadlock.
            if (writeGateHeld) { writeGateHeld = false; writeGate.release() }
            writeGate.tryAcquire()
        }
        writeGateHeld = true
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeCharacteristic(ch, data, ch.writeType)
            if (rc != BluetoothStatusCodes.SUCCESS) DebugLog.w(tag, "writeCharacteristic($nr) rc=$rc len=${data.size}")
            rc == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            (ch.setValue(data) && g.writeCharacteristic(ch))
        }
        if (!ok) {
            // Kein Callback wird kommen → Gate sofort freigeben.
            if (writeGateHeld) { writeGateHeld = false; writeGate.release() }
        }
        return ok
    }

    private fun enableNotification(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(ch, true)
        val desc = ch.getDescriptor(CCCD) ?: return false
        return gatedWriteDescriptor(g, desc, ENABLE_NOTIFICATION_VALUE)
    }
    private fun enableIndication(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(ch, true)
        val desc = ch.getDescriptor(CCCD) ?: return false
        return gatedWriteDescriptor(g, desc, ENABLE_INDICATION_VALUE)
    }
    private fun disableNotification(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, false)
        val desc = ch.getDescriptor(CCCD) ?: return
        gatedWriteDescriptor(g, desc, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
    }

    /** Descriptor-Write durch denselben writeGate wie Characteristic-Writes.
     *  Android erlaubt nur EINEN GATT-Befehl gleichzeitig — Mischen aus
     *  writeCharacteristic + writeDescriptor ohne Gate produziert
     *  `writeDescriptor() - prior command is not finished` und der Sensor
     *  bricht ~3 s später mit status=19 ab. */
    private fun gatedWriteDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray): Boolean {
        if (Thread.currentThread() === writeThread) return gatedWriteDescriptorSync(g, d, v)
        writeHandler.post { gatedWriteDescriptorSync(g, d, v) }
        return true
    }

    private fun gatedWriteDescriptorSync(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray): Boolean {
        val acquired = try {
            writeGate.tryAcquire(2000, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }
        if (!acquired) {
            DebugLog.w(tag, "gatedWriteDescriptor: gate timeout — forciere Release")
            if (writeGateHeld) { writeGateHeld = false; writeGate.release() }
            writeGate.tryAcquire()
        }
        writeGateHeld = true
        val ok = writeDescriptor(g, d, v)
        if (!ok) {
            // Kein onDescriptorWrite-Callback wird kommen → Gate sofort freigeben.
            if (writeGateHeld) { writeGateHeld = false; writeGate.release() }
        }
        return ok
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        } else {
            d.setValue(v)
            g.writeDescriptor(d)
        }
    }

    /** Reflection-based pairing on LE transport (Android exposes it only via hidden API). */
    private fun createBondTransportLE(dev: BluetoothDevice): Boolean = try {
        val m = dev.javaClass.getMethod("createBond", Int::class.javaPrimitiveType)
        m.invoke(dev, TRANSPORT_LE) as Boolean
    } catch (t: Throwable) {
        DebugLog.w(tag, "createBond(transport) via reflection failed", t)
        dev.createBond()
    }

    private fun rangeEquals(a: ByteArray, b: ByteArray, bOffset: Int): Boolean {
        if (bOffset + a.size > b.size) return false
        for (i in a.indices) if (a[i] != b[i + bOffset]) return false
        return true
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    companion object { private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") }
}
