/*
 * AiDEX X / LinX — Bluetooth-Kopplung (reines Kotlin, Androids BluetoothGatt).
 *
 * Port aus Jugglucos AidexXGattCallback.java (GPL-3.0, © 2021 Jaap Korthals
 * Altes). Kein JNI, keine native Lib — analog zum Dexcom-G7BleClient.
 *
 * Ablauf: scannen -> verbinden -> Services -> Notifications (f001/f002/f003)
 *   -> askKey an f001 -> Masterkey (f001-Notify) -> bonden -> f002 lesen
 *   -> Session-Key + Startbefehl -> f002/f003-Notifies -> Werte.
 *
 * STAND: erste Fassung — muss am echten Sensor getestet und feinjustiert werden.
 * Deshalb ausführliches Logging (Tag "AidexX").
 */
package com.g7monitor.ble.aidexx

import com.g7monitor.shared.aidex.AidexReading
import com.g7monitor.shared.aidex.AidexXProtocol
import com.g7monitor.shared.aidex.AidexXSession
import com.g7monitor.shared.aidex.isAidexXDeviceName
import com.g7monitor.shared.ui.AppState
import com.g7monitor.shared.ui.I18n

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.g7monitor.util.DebugLog
import com.g7monitor.ble.CgmEvent
import com.g7monitor.ble.CgmReading
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "AidexX"

@SuppressLint("MissingPermission")
class AidexXBleClient(
    private val ctx: Context,
    serialRaw: String,
    private val emit: (CgmEvent) -> Unit,
) {
    private val serial = serialRaw.uppercase().trim()
    // Erst bei Fund erzeugt — mit der SN aus dem Advertisement (tippfehlerfest).
    private var session: AidexXSession? = null

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var f001: BluetoothGattCharacteristic? = null   // Masterkey (unbonded)
    private var f002: BluetoothGattCharacteristic? = null   // Session-Key + Kommandos
    private var f003: BluetoothGattCharacteristic? = null   // aktuelle Werte (bonded)
    @Volatile private var stopped = false
    @Volatile private var bonding = false
    @Volatile private var handshakeStarted = false
    @Volatile private var foundName = ""   // echter Werbename (AiDEX X-… / LinX-…)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reconnectRunnable = Runnable { if (!stopped) { DebugLog.i(TAG, "Auto-Reconnect: neuer Scan …"); start() } }
    private val seenAddrs = java.util.Collections.synchronizedSet(HashSet<String>())

    private val ops = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile private var opRunning = false

    // ---- Start / Stop ----
    fun start() {
        stopped = false
        handshakeStarted = false
        val ad = adapter
        if (ad == null || !ad.isEnabled) { emit(CgmEvent.Error("Bluetooth ist aus")); return }
        if (serial.length < 6) { emit(CgmEvent.Error("Bitte Seriennummer (SN) eingeben")); return }
        DebugLog.i(TAG, "start() Scan nach AiDEX X-/LinX- ...$serial")
        emit(CgmEvent.Scanning(true))
        try { ctx.unregisterReceiver(bondReceiver) } catch (_: Throwable) {}
        try { ctx.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)) } catch (_: Throwable) {}
        seenAddrs.clear()
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        ad.bluetoothLeScanner?.startScan(null, settings, scanCallback)
    }

    fun stop() {
        stopped = true
        mainHandler.removeCallbacks(reconnectRunnable)
        DebugLog.i(TAG, "stop()")
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Throwable) {}
        try { gatt?.disconnect(); gatt?.close() } catch (_: Throwable) {}
        gatt = null
        try { ctx.unregisterReceiver(bondReceiver) } catch (_: Throwable) {}
        emit(CgmEvent.Scanning(false))
    }

    fun forgetSensor() {
        val s = session
        val g = gatt
        val cmd = s?.unpairCommand()   // null = (noch) kein Session-Key → nicht verbunden
        if (s != null && g != null && f002 != null && cmd != null) {
            // Verbunden: den Sensor sauber ENTKOPPELN (0xf2) — er löscht seinen eigenen Bond
            // und lässt sich danach an ein anderes Gerät (z. B. iPhone) koppeln.
            DebugLog.i(TAG, "Sensor freigeben: Entkopplungs-Kommando senden")
            s.onUnpairResult = { ok ->
                DebugLog.i(TAG, "Entkopplung ${if (ok) "ok" else "fehlgeschlagen"} → Bond lösen")
                removeBondReflect()
                emit(CgmEvent.Info(I18n.get(AppState.language,
                                   if (ok) "sensor_released" else "sensor_release_unconfirmed")))
                stop()
            }
            enqueue { f002?.let { writeChar(g, it, cmd) } }
            runNext()
            // Sicherheitsnetz: kommt keine Antwort, nach 5 s trotzdem lösen.
            mainHandler.postDelayed({ if (!stopped) { removeBondReflect(); stop() } }, 5000L)
        } else {
            // Nicht verbunden: nur den lokalen Bond lösen (frisch koppeln geht dann trotzdem).
            removeBondReflect()
            stop()
        }
    }

    private fun removeBondReflect() {
        runCatching {
            val dev = gatt?.device
            if (dev != null && dev.bondState != BluetoothDevice.BOND_NONE) {
                dev.javaClass.getMethod("removeBond").invoke(dev)
            }
        }
    }

    private fun matches(name: String?): Boolean {
        val n = name?.uppercase() ?: return false
        // NUR den eigenen Sensor: die eingegebene SN muss im Gerätenamen stehen.
        return n.contains(serial)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName
            val addr = result.device.address
            if (seenAddrs.add(addr)) {
                val svc = result.scanRecord?.serviceUuids?.joinToString { it.uuid.toString().substring(4, 8) } ?: "-"
                DebugLog.i(TAG, "gesehen: '${name ?: "?"}' $addr ${result.rssi}dBm svc=$svc")
            }
            if (!matches(name)) return
            foundName = name ?: ""
            session = AidexXSession(serial).also { s ->
                s.onBackfill = { r -> emit(CgmEvent.BackfillGlucose(CgmReading(r.timestampMs, r.mgdl, r.rateMgdlPerMin))) }
                s.onCurrent = { r -> DebugLog.i(TAG, "Wert(LastPast): ${r.mgdl} mg/dL"); emit(CgmEvent.Glucose(CgmReading(r.timestampMs, r.mgdl, r.rateMgdlPerMin))) }
                s.newestStoredMs = { com.g7monitor.vm.G7Repository.aidexNewestStoredMs }
                s.onStartTime = { ms -> com.g7monitor.vm.G7Repository.aidexSensorStartMs = ms }
                s.log = { m -> DebugLog.i(TAG, m) }
            }
            DebugLog.i(TAG, "Sensor gefunden: $name (SN=$serial) ${result.rssi}dBm")
            emit(CgmEvent.ScanHit(name, result.device.address, result.rssi))
            try { adapter?.bluetoothLeScanner?.stopScan(this) } catch (_: Throwable) {}
            emit(CgmEvent.Scanning(false))
            connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) {
            DebugLog.e(TAG, "Scan fehlgeschlagen: $errorCode")
            emit(CgmEvent.Error("Scan fehlgeschlagen ($errorCode)"))
        }
    }

    private fun connect(device: BluetoothDevice) {
        DebugLog.i(TAG, "connectGatt ${device.address}")
        gatt = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ---- GATT-Callback ----
    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            DebugLog.i(TAG, "onConnectionStateChange status=$status newState=$newState bond=${g.device.bondState}")
            if (stopped) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emit(CgmEvent.Connected(g.device.address))
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                emit(CgmEvent.Disconnected(status))
                if (!stopped && !bonding) {
                    try { g.close() } catch (_: Throwable) {}; gatt = null
                    // Unerwartet getrennt (z. B. außer Reichweite) → nach kurzer Pause neu verbinden.
                    mainHandler.removeCallbacks(reconnectRunnable)
                    mainHandler.postDelayed(reconnectRunnable, 5000L)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { emit(CgmEvent.Error("Services $status")); return }
            for (svc in g.services) for (ch in svc.characteristics) when (ch.uuid.toString().lowercase()) {
                AidexXProtocol.CHAR_UNBONDED -> f001 = ch
                AidexXProtocol.CHAR_DATA     -> f002 = ch
                AidexXProtocol.CHAR_BONDED   -> f003 = ch
            }
            DebugLog.i(TAG, "Chars: f001=${f001 != null} f002=${f002 != null} f003=${f003 != null}")
            if (f001 == null || f002 == null || f003 == null) { emit(CgmEvent.Error("AiDEX-Characteristics fehlen")); return }
            emit(CgmEvent.HandshakeAdvanced(1))
            // Notifications aktivieren, dann askKey an f001.
            f001?.let { enqueue { enableNotify(g, it) } }
            f002?.let { enqueue { enableNotify(g, it) } }
            f003?.let { enqueue { enableNotify(g, it) } }
            enqueue { val s = session; val c = f001; if (s != null && c != null) writeChar(g, c, s.askKeyBytes(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
            runNext()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            DebugLog.i(TAG, "onDescriptorWrite ${d.characteristic.uuid} status=$status")
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            DebugLog.i(TAG, "onCharacteristicWrite ${ch.uuid} status=$status")
            opDone()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(g, ch, value, status)
        }
        @Deprecated("compat < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            handleRead(g, ch, ch.value ?: ByteArray(0), status)
        }
        private fun handleRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            DebugLog.i(TAG, "onCharacteristicRead ${ch.uuid} status=$status len=${value.size}")
            val s = session ?: return
            if (ch.uuid == f002?.uuid) {
                val haveKey = g.device.bondState == BluetoothDevice.BOND_BONDED && s.hasKey()
                val cmd = s.onSessionKeyRead(haveKey, value)
                if (cmd == null) { emit(CgmEvent.Error("Session-Key fehlgeschlagen")); g.disconnect(); return }
                emit(CgmEvent.Authenticated(foundName.ifEmpty { "AiDEX X-$serial" }))
                enqueue { f002?.let { writeChar(g, it, cmd) } }
            }
            opDone()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(g, ch, value)
        }
        @Deprecated("compat < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handleNotify(g, ch, ch.value ?: ByteArray(0))
        }
        private fun handleNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            val s = session ?: return
            when (ch.uuid) {
                f001?.uuid -> {
                    if (s.onMasterKey(value)) {
                        DebugLog.i(TAG, "Masterkey erhalten -> bonden")
                        emit(CgmEvent.HandshakeAdvanced(2))
                        startBond(g)
                    } else emit(CgmEvent.Error("Masterkey ungültig"))
                }
                f002?.uuid -> {
                    DebugLog.i(TAG, "f002-Notify ${value.size}B")
                    val cmd = s.onDataNotify(value)
                    if (cmd == null) { DebugLog.i(TAG, "onDataNotify=null -> trennen (Typ unbekannt/CRC?)"); g.disconnect(); return }
                    if (cmd.isNotEmpty()) { enqueue { f002?.let { writeChar(g, it, cmd) } }; runNext() }
                }
                f003?.uuid -> {
                    DebugLog.i(TAG, "f003-Notify ${value.size}B")
                    val r = s.onCurrentNotify(value)
                    if (r != null) {
                        DebugLog.i(TAG, "Wert: ${r.mgdl} mg/dL rate=${r.rateMgdlPerMin}")
                        emit(CgmEvent.Glucose(CgmReading(r.timestampMs, r.mgdl, r.rateMgdlPerMin)))
                    }
                }
            }
        }
    }

    // ---- Bonding ----
    private fun startBond(g: BluetoothGatt) {
        val dev = g.device
        if (dev.bondState == BluetoothDevice.BOND_BONDED) { afterBonded(g); return }
        bonding = true
        emit(CgmEvent.Bonded(dev.address))   // „Kopplung läuft"
        dev.createBond()
    }
    private fun afterBonded(g: BluetoothGatt) {
        if (handshakeStarted) return
        handshakeStarted = true
        bonding = false
        emit(CgmEvent.HandshakeAdvanced(3))
        enqueue { f002?.let { readChar(g, it) } }
        runNext()
    }
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            DebugLog.i(TAG, "Bond-State: $state")
            if (state == BluetoothDevice.BOND_BONDED) gatt?.let { afterBonded(it) }
            else if (state == BluetoothDevice.BOND_NONE && bonding) { bonding = false; emit(CgmEvent.Error("Kopplung fehlgeschlagen")) }
        }
    }

    // ---- GATT-Op-Queue (Android erlaubt nur eine Operation gleichzeitig) ----
    private fun enqueue(op: () -> Unit) { ops.add(op) }
    private fun opDone() { opRunning = false; runNext() }
    private fun runNext() {
        if (opRunning) return
        val op = ops.poll() ?: return
        opRunning = true
        try { op() } catch (t: Throwable) { DebugLog.e(TAG, "op", t); opDone() }
    }

    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD) ?: run { opDone(); return }
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        }
    }
    private fun writeChar(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray,
                          writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
        DebugLog.i(TAG, "write ${ch.uuid} ${data.size}B typ=$writeType")
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, data, writeType)
        } else {
            @Suppress("DEPRECATION") run { ch.value = data; ch.writeType = writeType; g.writeCharacteristic(ch) }
        }
    }
    private fun readChar(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        DebugLog.i(TAG, "read ${ch.uuid}")
        g.readCharacteristic(ch)
    }
}
