/*
 * Treiber für Dexcom G7 / ONE+.
 *
 * Dünner Adapter um den bestehenden G7BleClient: dieser bleibt vollständig
 * unangetastet (1234 Zeilen, erprobt). Hier werden lediglich seine Events auf
 * die generischen CgmEvent gemappt und der Reading-Typ übersetzt.
 */
package com.g7monitor.ble

import android.content.Context
import com.g7monitor.ble.dexk.DexSession
import java.util.concurrent.CopyOnWriteArrayList

class DexcomG7Driver(
    ctx: Context,
    pin: ByteArray,
) : CgmDriver {

    private val client = G7BleClient(ctx, pin)
    private val listeners = CopyOnWriteArrayList<CgmDriver.Listener>()

    /** Brücke: lauscht am G7BleClient und reicht gemappte Events weiter. */
    private val bridge = G7BleClient.Listener { ev -> emit(mapEvent(ev)) }

    override fun addListener(l: CgmDriver.Listener) { listeners.add(l) }
    override fun removeListener(l: CgmDriver.Listener) { listeners.remove(l) }
    private fun emit(ev: CgmEvent) = listeners.forEach { runCatching { it.onEvent(ev) } }

    override fun start() {
        client.addListener(bridge)
        client.start()
    }

    override fun stop() {
        client.removeListener(bridge)
        client.stop()
    }

    override fun forgetSensor() {
        client.forgetSensor()
    }

    override fun hardReset() {
        client.hardReset()
    }

    private fun mapEvent(ev: G7BleClient.Event): CgmEvent = when (ev) {
        is G7BleClient.Event.Scanning          -> CgmEvent.Scanning(ev.active)
        is G7BleClient.Event.ScanHit           -> CgmEvent.ScanHit(ev.name, ev.address, ev.rssi)
        is G7BleClient.Event.Connected         -> CgmEvent.Connected(ev.address)
        is G7BleClient.Event.Bonded            -> CgmEvent.Bonded(ev.address)
        is G7BleClient.Event.HandshakeAdvanced -> CgmEvent.HandshakeAdvanced(ev.phase)
        is G7BleClient.Event.Authenticated     -> CgmEvent.Authenticated(ev.deviceName)
        is G7BleClient.Event.Glucose           -> CgmEvent.Glucose(ev.reading.toCgm())
        is G7BleClient.Event.BackfillGlucose   -> CgmEvent.BackfillGlucose(ev.reading.toCgm())
        is G7BleClient.Event.StaleGlucose      -> CgmEvent.StaleGlucose(ev.ageSec, ev.mgdl)
        is G7BleClient.Event.Disconnected      -> CgmEvent.Disconnected(ev.status)
        is G7BleClient.Event.Error             -> CgmEvent.Error(ev.message, ev.cause)
    }

    private fun DexSession.Reading.toCgm() =
        CgmReading(timestampMs = timestampMs, mgdl = mgdl, rateMgdlPerMin = rateMgdlPerMin)
}
