/*
 * Sensor-neutrale Treiber-Schnittstelle.
 *
 * Jeder unterstützte CGM-Sensor (aktuell Dexcom G7/ONE+) implementiert diese
 * Schnittstelle. Der gesamte App-Kern — Speicher, Alarme, Statistik, UI,
 * Cloud-Upload — kennt NUR diese Schnittstelle und die generischen Typen
 * CgmReading / CgmEvent. Alles Sensor-Spezifische (BLE-Protokoll, Auth,
 * Decodierung) lebt komplett im jeweiligen Treiber.
 *
 * Einen neuen Sensor hinzufügen:
 *   1. neue XyzDriver-Klasse, die CgmDriver implementiert
 *   2. Eintrag in SensorType + SensorFactory
 */
package com.g7monitor.ble

interface CgmDriver {

    fun interface Listener { fun onEvent(ev: CgmEvent) }

    fun addListener(l: Listener)
    fun removeListener(l: Listener)

    /** Startet Scan/Verbindung und hält sie bis stop(). */
    fun start()

    /** Beendet Verbindung und Scan. */
    fun stop()

    /** Sensor dauerhaft vergessen (Bond/Schlüssel/Session löschen, sofern der
     *  Sensor so etwas überhaupt hat). Default: nichts zu tun. */
    fun forgetSensor() {}

    /** Vollständiger Verbindungs-Reset (Bond/Session/Keys), ohne PIN/History
     *  zu verwerfen. Nur für Sensoren relevant, die so etwas haben (Dexcom).
     *  Default: nichts zu tun. */
    fun hardReset() {}
}

/** Ein generischer Glukose-Messwert — unabhängig vom Sensortyp. */
data class CgmReading(
    val timestampMs: Long,            // UTC-Zeit der Messung
    val mgdl: Int,                    // Glukose in mg/dL
    val rateMgdlPerMin: Float = 0f,   // Trend (Änderung/min), 0 wenn unbekannt
)

/** Generische Ereignisse, die ein Treiber an den Kern meldet. Bewusst eine
 *  Verallgemeinerung der bisherigen G7-Events, damit der Repository-Code
 *  praktisch unverändert bleibt. */
sealed class CgmEvent {
    data class Scanning(val active: Boolean) : CgmEvent()
    data class ScanHit(val name: String?, val address: String, val rssi: Int) : CgmEvent()
    data class Connected(val address: String) : CgmEvent()
    data class Bonded(val address: String) : CgmEvent()
    /** Fortschritt beim (sensor-spezifischen) Verbindungsaufbau. */
    data class HandshakeAdvanced(val phase: Int) : CgmEvent()
    data class Authenticated(val deviceName: String?) : CgmEvent()
    /** Frischer Live-Wert (darf die große Anzeige aktualisieren + Alarm auslösen). */
    data class Glucose(val reading: CgmReading) : CgmEvent()
    /** Wert aus der Sensor-Historie (nur Verlauf/Chart, kein Alarm). */
    data class BackfillGlucose(val reading: CgmReading) : CgmEvent()
    /** Letzter Wert zu alt (Sensor evtl. nicht am Körper). */
    data class StaleGlucose(val ageSec: Int, val mgdl: Int) : CgmEvent()
    data class Disconnected(val status: Int) : CgmEvent()
    data class Error(val message: String, val cause: Throwable? = null) : CgmEvent()
    /** Neutrale Statusmeldung (kein Fehler) — z. B. „Sensor freigegeben". */
    data class Info(val message: String) : CgmEvent()
}
