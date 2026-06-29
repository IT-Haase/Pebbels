/*
 * Dexcom G7 — Paket-Decode in reinem Kotlin (ersetzt den Daten-Teil von libg7.so).
 *
 * Port von `dexcom/java.cpp` (struct glucoseinput / dexcomProcessData / askfilldata).
 * WICHTIG: Die Glukose-Pakete sind KLARTEXT — keine Entschlüsselung nötig
 * (dexcomProcessData castet die 19 Bytes direkt auf die Struktur).
 *
 * 0x4E-Glukosepaket, 19 Byte, little-endian:
 *   0    type            u8
 *   1    status          i8
 *   2-5  secsSinceStart  i32
 *   6-7  sequence        u16
 *   8-9  bogus           u16
 *   10-11 age            u16
 *   12-13 mgdL:12 | donly:4   (mgdL = u16 & 0x0FFF)
 *   14   state           i8
 *   15   trend           i8   (rate = trend/10)
 *   16-17 predicted:10 | unknown:6  (predicted = u16 & 0x03FF)
 *   18   info            u8
 * Zeit der Messung = starttime + secsSinceStart − age   (Sekunden).
 */
package com.g7monitor.ble.dexk

internal object DexPacket {

    data class Glucose(
        val type: Int,
        val secsSinceStart: Int,
        val sequence: Int,
        val age: Int,
        val mgdl: Int,
        val state: Int,
        val trend: Int,
        val predictedMgdl: Int,
        val info: Int,
    ) {
        val rateMgdlPerMin: Float get() = trend / 10.0f
        /** Mess-Zeitpunkt in ms, gegeben den Sensor-Start (ms). */
        fun timeMs(sensorStartMs: Long): Long =
            sensorStartMs + (secsSinceStart - age) * 1000L
        /** Index im 5-Minuten-Raster (wie getindex()). */
        val index: Int get() = (secsSinceStart - age) / DEX_SECONDS
    }

    const val DEX_SECONDS = 5 * 60

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun i32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    /** Decodiert ein 0x4E-Glukosepaket (>= 19 Byte). null wenn zu kurz. */
    fun parseGlucose(p: ByteArray): Glucose? {
        if (p.size < 19) return null
        val mw = u16(p, 12)
        val pw = u16(p, 16)
        return Glucose(
            type = u8(p, 0),
            secsSinceStart = i32(p, 2),
            sequence = u16(p, 6),
            age = u16(p, 10),
            mgdl = mw and 0x0FFF,
            state = p[14].toInt(),
            trend = p[15].toInt(),               // signed
            predictedMgdl = pw and 0x03FF,
            info = u8(p, 18),
        )
    }

    /** Backfill-Anforderung (askfilldata, gepackt 9 Byte):
     *  [0x59][start i32 LE][end i32 LE] — start/end = Sekunden seit Sensor-Start. */
    fun backfillCmd(startSec: Int, endSec: Int): ByteArray = byteArrayOf(
        0x59,
        startSec.toByte(), (startSec ushr 8).toByte(), (startSec ushr 16).toByte(), (startSec ushr 24).toByte(),
        endSec.toByte(), (endSec ushr 8).toByte(), (endSec ushr 16).toByte(), (endSec ushr 24).toByte(),
    )
}
