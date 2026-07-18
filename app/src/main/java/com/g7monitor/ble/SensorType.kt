/*
 * Unterstützte Sensor-Typen + Factory.
 *
 * Der gesamte App-Kern kennt nur SensorType + die CgmDriver-Schnittstelle.
 * Alles Sensor-Spezifische (BLE, Auth, Decodierung) lebt im jeweiligen
 * Treiber und in dessen EIGENEM Paket — die Sensor-Welten sind strikt getrennt:
 *   – Dexcom G7 / ONE+  ->  ble.dexk    (DexcomG7Driver)
 *   – AiDEX X / LinX    ->  ble.aidexx  (AidexXDriver)
 */
package com.g7monitor.ble

import android.content.Context

/** Ein unterstützter Sensor.
 *  @param tag   Kurzkennung für Speicher/Verlauf ("dexcom" / "aidex").
 *  @param label Anzeigename fürs UI. */
enum class SensorType(val tag: String, val label: String) {
    DexcomG7("dexcom", "Dexcom G7 / ONE+"),
    AidexX  ("aidex",  "AiDEX X / LinX"),
}

object SensorFactory {
    /** Baut den passenden Treiber zum gewählten Sensor-Typ.
     *  @param pin    Pairing-Code — nur Dexcom (sonst leeres Array).
     *  @param serial Seriennummer — nur AiDEX (sonst leerer String). */
    fun create(type: SensorType, ctx: Context, pin: ByteArray, serial: String): CgmDriver =
        when (type) {
            SensorType.DexcomG7 -> DexcomG7Driver(ctx, pin)
            SensorType.AidexX   -> AidexXDriver(ctx, serial)
        }
}
