/*
 * Unterstützte Sensor-Typen + Factory.
 *
 * Aktuell nur Dexcom G7 / ONE+. (Der AiDEX-Zweig wurde entfernt — der Sensor
 * war zu ungenau; siehe Backup, falls er je wiederkommen soll.)
 */
package com.g7monitor.ble

import android.content.Context

enum class SensorType { DexcomG7 }

object SensorFactory {
    /** Baut den passenden Treiber zum gewählten Sensor-Typ.
     *  @param pin Pairing-Code (Dexcom). */
    fun create(type: SensorType, ctx: Context, pin: ByteArray): CgmDriver = when (type) {
        SensorType.DexcomG7 -> DexcomG7Driver(ctx, pin)
    }
}
