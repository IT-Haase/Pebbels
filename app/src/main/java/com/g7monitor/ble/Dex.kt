/*
 * Dexcom-Backend (reiner Kotlin-Port, DexSession). G7BleClient ruft nur Dex.*
 * auf; Dex leitet 1:1 an DexSession weiter. Diese eine Indirektion ist später
 * der einzige Umschaltpunkt aufs KMP-:shared-Modul — der Client bleibt dann
 * unverändert. Kein libg7.so / DexNative mehr: die App ist fremdcode-frei.
 */
package com.g7monitor.ble

import com.g7monitor.ble.dexk.DexSession

object Dex {
    /** Reiner Kotlin-Code — immer "geladen" (Felder bleiben für die UI-Anzeige). */
    val Loaded: Boolean get() = true
    val loadError: String? get() = null

    fun setListener(l: DexSession.GlucoseListener?) = DexSession.setListener(l)
    fun setStaleListener(l: DexSession.StaleListener?) = DexSession.setStaleListener(l)

    fun sessionCreate(serial: String, pin: ByteArray): Long = DexSession.sessionCreate(serial, pin)
    fun sessionDestroy(p: Long) = DexSession.sessionDestroy(p)
    /** Krypto-Warmlauf — nimmt die Erst-Aufruf-Latenz aus dem Handshake. */
    fun warmUp() = DexSession.warmUp()
    fun sessionBlob(p: Long): ByteArray? = DexSession.sessionBlob(p)
    fun sessionRestore(p: Long, b: ByteArray): Boolean = DexSession.sessionRestore(p, b)

    fun dexKnownSensor(p: Long): Boolean = DexSession.dexKnownSensor(p)
    fun dexCandidate(p: Long, n: String, a: String): Boolean = DexSession.dexCandidate(p, n, a)
    fun dexSaveDeviceName(p: Long, n: String) = DexSession.dexSaveDeviceName(p, n)
    fun dexGetDeviceName(p: Long): String? = DexSession.dexGetDeviceName(p)
    fun dexResetKeys(p: Long) = DexSession.dexResetKeys(p)
    fun isAuthenticated(p: Long): Boolean = DexSession.isAuthenticated(p)

    fun dexPutPubKey(p: Long, w: Int, i: ByteArray): Boolean = DexSession.dexPutPubKey(p, w, i)
    fun makeRound12bytes(p: Long, w: Int): ByteArray? = DexSession.makeRound12bytes(p, w)
    fun makeRound3bytes(p: Long): ByteArray? = DexSession.makeRound3bytes(p)
    fun dexChallenger(v: ByteArray): ByteArray = DexSession.dexChallenger(v)
    fun dex8AES(p: Long, d: ByteArray, sd: Int, o: ByteArray, so: Int): Boolean =
        DexSession.dex8AES(p, d, sd, o, so)
    fun getDexCertSize(a: ByteArray): Int = DexSession.getDexCertSize(a)

    fun dexcomProcessData(p: Long, b: ByteArray, t: LongArray): Boolean =
        DexSession.dexcomProcessData(p, b, t)
    fun getDexbackfillcmd(p: Long): ByteArray? = DexSession.getDexbackfillcmd(p)
    fun dexbackfill(p: Long, b: ByteArray): Boolean = DexSession.dexbackfill(p, b)
    fun dexEndBackfill(p: Long) = DexSession.dexEndBackfill(p)
}
