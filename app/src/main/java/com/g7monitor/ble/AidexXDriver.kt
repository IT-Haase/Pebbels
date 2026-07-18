/*
 * Treiber für AiDEX X / LinX (MicroTech Medical).
 *
 * Dünner Adapter auf CgmDriver — genau wie DexcomG7Driver. Die BLE-/Protokoll-
 * Logik lebt getrennt im Paket ble.aidexx (reines Kotlin, kein JNI).
 */
package com.g7monitor.ble

import android.content.Context
import com.g7monitor.ble.aidexx.AidexXBleClient
import java.util.concurrent.CopyOnWriteArrayList

class AidexXDriver(
    ctx: Context,
    serial: String,
) : CgmDriver {

    private val listeners = CopyOnWriteArrayList<CgmDriver.Listener>()
    private fun emit(ev: CgmEvent) = listeners.forEach { runCatching { it.onEvent(ev) } }

    private val client = AidexXBleClient(ctx, serial) { ev -> emit(ev) }

    override fun addListener(l: CgmDriver.Listener) { listeners.add(l) }
    override fun removeListener(l: CgmDriver.Listener) { listeners.remove(l) }

    override fun start() { client.start() }
    override fun stop() { client.stop() }
    override fun forgetSensor() { client.forgetSensor() }
}
