/*
 * AiDEX X / LinX — Protokoll-Konstanten (MicroTech Medical).
 *
 * Grundlage: Jugglucos AiDEX-X-Implementierung (GPL-3.0, © 2021 Jaap Korthals
 * Altes). Nach Kotlin portiert — deshalb steht auch dieser Teil unter GPL-3.0.
 *
 * Dieses Paket (ble.aidexx) ist strikt getrennt vom Dexcom-Code (ble.dexk).
 * Hier liegen ausschließlich die AiDEX-spezifischen Bausteine.
 */
package com.g7monitor.shared.aidex

/** BLE-Kennungen und Eckdaten des AiDEX X / LinX. */
object AidexXProtocol {

    // Gerätename beginnt mit einem dieser Präfixe; Seriennummer = die letzten 10 Zeichen.
    const val NAME_PREFIX_AIDEX = "AiDEX X-"
    const val NAME_PREFIX_LINX  = "LinX-"

    // GATT-Service + Characteristics
    const val SERVICE         = "0000181f-0000-1000-8000-00805f9b34fb"
    const val CHAR_DATA       = "0000f002-0000-1000-8000-00805f9b34fb" // Session-Key + Kommandos/History
    const val CHAR_UNBONDED   = "0000f001-0000-1000-8000-00805f9b34fb" // Masterkey
    const val CHAR_BONDED     = "0000f003-0000-1000-8000-00805f9b34fb" // aktuelle Werte
    const val CHAR_START_TIME = "00002aaa-0000-1000-8000-00805f9b34fb"

    /** Der aktuelle Wert wird OFFEN im BLE-Advertisement gefunkt (nur CRC-geschützt,
     *  nicht verschlüsselt) — Länge 62 Byte. Ermöglicht den Live-Wert ohne Pairing. */
    const val ADVERTISE_LEN = 62

    // Krypto: AES-128-CFB. Schlüssel & IV werden aus der Seriennummer per MD5
    // abgeleitet; darüber wird ein Session-Key ausgetauscht (nur für Verlauf + Startzeit nötig).

    // Glukose-Decodierung: 10-Bit-Rohwert = mg/dL direkt (mmol/L = /18).
    const val GLUCOSE_MIN_MGDL = 18
    const val GLUCOSE_MAX_MGDL = 800

    /** Trend-Rohwert -> Änderungsrate in mg/dL pro Minute. */
    fun rateFromTrend(trend: Int): Float = trend * 0.1f
}

/** True, wenn der BLE-Gerätename zu einem AiDEX X / LinX gehört. */
fun isAidexXDeviceName(name: String?): Boolean =
    name != null && (name.startsWith(AidexXProtocol.NAME_PREFIX_AIDEX) ||
                     name.startsWith(AidexXProtocol.NAME_PREFIX_LINX))
