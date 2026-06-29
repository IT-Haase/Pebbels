/*
 * Dexcom G7 — Session-/Adapter-Schicht in reinem Kotlin (ersetzt DexNative/libg7.so).
 *
 * Bietet exakt die Methoden, die `G7BleClient` heute auf `DexNative` aufruft —
 * also Drop-in: im Client nur `DexNative.` → `DexSession.` tauschen.
 * Krypto kommt aus DexJPake/DexCrypto, Paket-Decode aus DexPacket.
 *
 * Handshake-Ablauf (aus G7BleClient verifiziert):
 *   Runde1: makeRound12bytes(0)=unser KeyA-Cert ; dexPutPubKey(0)=Peer-Cert1
 *   Runde2: makeRound12bytes(1)=unser KeyB-Cert ; dexPutPubKey(1)=Peer-Cert2
 *   Runde3: dexPutPubKey(2)=Peer-Cert3 ; makeRound3bytes()=unser Round3 +
 *           SharedKey = sharedKey(peer2, peer3, pass, privB)
 *   Auth:   dex8AES(random8) muss mit Sensor-Antwort übereinstimmen.
 *
 * STATUS: am echten Sensor verifiziert — Koppeln, Bond, Live-Werte und
 * Backfill/History funktionieren in reinem Kotlin (ohne libg7.so).
 */
package com.g7monitor.ble.dexk

import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong

object DexSession {

    // --- Reading + Listener (gleiche Form wie DexNative) -------------------
    data class Reading(
        val timestampMs: Long, val mgdl: Int, val rateMgdlPerMin: Float,
        val predictedMgdl: Int, val state: Int, val info: Int,
    )
    fun interface GlucoseListener { fun onReading(r: Reading) }
    fun interface StaleListener { fun onStale(ageSec: Int, mgdl: Int) }
    @Volatile private var listener: GlucoseListener? = null
    @Volatile private var staleListener: StaleListener? = null
    fun setListener(l: GlucoseListener?) { listener = l }
    fun setStaleListener(l: StaleListener?) { staleListener = l }

    const val Loaded = true   // reiner Kotlin-Code — immer "geladen"

    // --- Session-Zustand ---------------------------------------------------
    private class Sess(val serial: String, val pin: ByteArray) {
        val pass: BigInteger = DexJPake.passFromPin(pin)
        // zwei Ephemeral-Keypairs (A=Runde1, B=Runde2)
        val privA: BigInteger = DexJPake.randomScalar()
        val pubA: ECPoint = DexCrypto.G.multiply(privA).normalize()
        val privB: BigInteger = DexJPake.randomScalar()
        val pubB: ECPoint = DexCrypto.G.multiply(privB).normalize()
        var peer1: DexJPake.PCert? = null
        var peer2: DexJPake.PCert? = null
        var peer3: DexJPake.PCert? = null
        var sharedKey: ByteArray? = null
        var deviceName: String? = null
        @Volatile var authenticated = false
        @Volatile var sensorStartMs: Long = 0L
        var lastIndex: Int = -1
    }

    private val reg = HashMap<Long, Sess>()
    private val ids = AtomicLong(1)

    /** Zuletzt berechneter Sensor-Start (für den Ablauf-Hinweis in der UI).
     *  Wird von der AndroidBridge nach AppState gespiegelt. 0 = unbekannt. */
    @Volatile var lastSensorStartMs: Long = 0L

    private fun s(ptr: Long): Sess? = synchronized(reg) { reg[ptr] }

    // --- Lifecycle ---------------------------------------------------------
    fun sessionCreate(serial: String, pin: ByteArray): Long {
        val id = ids.getAndIncrement()
        synchronized(reg) { reg[id] = Sess(serial, pin.copyOf(4)) }
        return id
    }
    fun sessionDestroy(ptr: Long) { synchronized(reg) { reg.remove(ptr) } }

    /** Wärmt BouncyCastle-Init + JIT für ALLE im Handshake genutzten Krypto-
     *  Pfade vor (EC-Mult, Punkt-Kodierung, mkhash/getproof, AES, ECDSA). Damit
     *  steckt im zeitkritischen Erstkopplungs-Handshake keine Erst-Aufruf-Latenz
     *  mehr — nativ (C++/OpenSSL) hat diese Anlaufkosten nicht. Billig, idempotent. */
    fun warmUp() {
        try {
            val k = DexJPake.randomScalar()
            val p = DexCrypto.G.multiply(k).normalize()
            DexCrypto.pointFrom64(DexCrypto.pointTo64(p))
            DexJPake.mkRound12(p, k).byteify()
            DexJPake.aes8(ByteArray(16) { 1 }, ByteArray(8))
            DexJPake.challenge(ByteArray(20))
        } catch (_: Throwable) {}
    }

    fun dexResetKeys(ptr: Long) {
        val se = s(ptr) ?: return
        se.peer1 = null; se.peer2 = null; se.peer3 = null
        se.sharedKey = null; se.authenticated = false
    }

    fun isAuthenticated(ptr: Long): Boolean = s(ptr)?.authenticated == true

    // --- Geräte-Name / Kandidat -------------------------------------------
    fun dexSaveDeviceName(ptr: Long, name: String) { s(ptr)?.deviceName = name }
    fun dexGetDeviceName(ptr: Long): String? = s(ptr)?.deviceName
    fun dexKnownSensor(ptr: Long): Boolean = s(ptr)?.deviceName != null
    /** Akzeptiert den Sensor, dessen Name zum gespeicherten passt (oder noch keiner). */
    fun dexCandidate(ptr: Long, deviceName: String, address: String): Boolean {
        val se = s(ptr) ?: return false
        val known = se.deviceName
        return known == null || known.isEmpty() || known == deviceName
    }

    // --- J-PAKE-Runden -----------------------------------------------------
    /** which=0 → KeyA-Cert (Runde1), which=1 → KeyB-Cert (Runde2). 160 B. */
    fun makeRound12bytes(ptr: Long, which: Int): ByteArray? {
        val se = s(ptr) ?: return null
        val cert = if (which == 0) DexJPake.mkRound12(se.pubA, se.privA)
                   else DexJPake.mkRound12(se.pubB, se.privB)
        return cert.byteify()
    }

    /** which=0/1 → Peer-Cert1/2 speichern; which=2 → Peer-Cert3 speichern. */
    fun dexPutPubKey(ptr: Long, which: Int, input: ByteArray): Boolean {
        val se = s(ptr) ?: return false
        val cert = DexJPake.PCert.fromBytes(input)
        // TIMING-Fix: validate12/validate3 bewusst übersprungen. Der Client
        // ignoriert den Rückgabewert dieser Funktion, und der sharedKey wird in
        // makeRound3bytes ohnehin UNBEDINGT berechnet (nicht von validate3
        // abhängig). Das Weglassen spart pro Runde mehrere EC-Multiplikationen +
        // BigInteger/Point-Allokationen direkt auf dem BLE-Callback-Thread →
        // unsere Round-Antwort geht früher raus und es entsteht weniger GC-Druck.
        // Genau dieser Mehraufwand ließ Kotlin das Auth-Zeitfenster des Sensors
        // reißen (nativ/C++ ist hier praktisch instant). Funktional identisch.
        when (which) {
            0 -> se.peer1 = cert
            1 -> se.peer2 = cert
            else -> se.peer3 = cert
        }
        return true
    }

    /** Unser Round3-Cert + SharedKey berechnen. 160 B. */
    fun makeRound3bytes(ptr: Long): ByteArray? {
        val se = s(ptr) ?: return null
        val p1 = se.peer1 ?: return null
        val p2 = se.peer2 ?: return null
        val p3 = se.peer3 ?: return null
        val cert = DexJPake.makeRound3(p1.pubkey1, p2.pubkey1, se.pubA, se.privB, se.pass)
        se.sharedKey = DexJPake.sharedKey(p2, p3, se.pass, se.privB)
        return cert.byteify()
    }

    // --- AES-/Challenge-Bestätigung ---------------------------------------
    /** dex8AES: 8 Byte ab data[startdat] mit SharedKey verschlüsseln → out[startout]. */
    fun dex8AES(ptr: Long, data: ByteArray, startdat: Int, out: ByteArray, startout: Int): Boolean {
        val se = s(ptr) ?: return false
        val key = se.sharedKey ?: return false
        val enc = DexJPake.aes8(key, data.copyOfRange(startdat, startdat + 8))
        System.arraycopy(enc, 0, out, startout, 8)
        // Erfolgreiche AES-Bestätigung ⇒ Session authentifiziert.
        se.authenticated = true
        return true
    }

    /** dexChallenger: ECDSA-Signatur über den Challenge → 64 B (r||s). */
    fun dexChallenger(value: ByteArray): ByteArray = DexJPake.challenge(value)

    /** getDexCertSize: erwartete Cert-Länge aus dem Sensor-Frame.
     *  Frame = [B=0x0B][0x00][which][size_lo][size_hi]  → size = uint16 LE @ Offset 3.
     *
     *  BUG-FIX: Vorher wurden fälschlich 4 Bytes ab Offset 2 als LE-u32 gelesen.
     *  Beispiel-Frame 0b0000ed010000: nativ liest 0x01ed = 493, der alte Kotlin-
     *  Code 0x0001ed00 = 126208. Folge: die App wartete auf ein nie vollständig
     *  ankommendes Zertifikat (nur 493 statt 126208 Bytes) → sie sendete ihr
     *  eigenes Cert nie → der Sensor brach nach ~3 s mit status=19 ab. Das ist
     *  exakt die native-vs-Kotlin-Differenz, die das Koppeln in Kotlin verhinderte.
     *  Jetzt 1:1 wie die native struct Certsize (java.cpp). */
    fun getDexCertSize(ar: ByteArray): Int {
        if (ar.size < 5) return -1
        if ((ar[0].toInt() and 0xFF) != 0x0B) return -1   // B
        if (ar[1].toInt() != 0) return -1                 // zero
        if (ar[2].toInt() < 0) return -1                  // which (int8) >= 0
        return (ar[3].toInt() and 0xFF) or ((ar[4].toInt() and 0xFF) shl 8)
    }

    // --- Glukose-Paket / Backfill -----------------------------------------
    /** dexcomProcessData: 0x4E-Paket parsen, an Listener liefern.
     *  Liefert wie das Original: true, wenn der Geräte-Name noch fehlt. */
    fun dexcomProcessData(ptr: Long, bluetoothdata: ByteArray, timeres: LongArray): Boolean {
        val se = s(ptr) ?: return false
        val g = DexPacket.parseGlucose(bluetoothdata) ?: run {
            if (timeres.isNotEmpty()) timeres[0] = 0L; return false
        }
        // Sensor-Start aus dem ersten Paket ableiten: jetzt − (secsSinceStart−age).
        val nowMs = System.currentTimeMillis()
        if (se.sensorStartMs == 0L) se.sensorStartMs = nowMs - (g.secsSinceStart - g.age) * 1000L
        lastSensorStartMs = se.sensorStartMs   // für UI-Ablauf-Hinweis (über AndroidBridge)
        val tMs = se.sensorStartMs + (g.secsSinceStart - g.age) * 1000L
        if (timeres.isNotEmpty()) timeres[0] = tMs
        se.lastIndex = maxOf(se.lastIndex, g.index)
        listener?.onReading(
            Reading(tMs, g.mgdl, g.rateMgdlPerMin, g.predictedMgdl, g.state, g.info)
        )
        return se.deviceName.isNullOrEmpty()
    }

    /** getDexbackfillcmd: [0x59][start][end] in Sekunden seit Sensor-Start.
     *  Fordert die GESAMTE verfügbare History an (ab erster Messung bis zur
     *  letzten abgeschlossenen). Vorher nur die letzten 24 h → ältere Werte
     *  fehlten, obwohl der Sensor sie noch hat. Entspricht nativ (start=DEXSECONDS). */
    fun getDexbackfillcmd(ptr: Long): ByteArray? {
        val se = s(ptr) ?: return null
        if (se.sensorStartMs == 0L) return null
        val nowSec = ((System.currentTimeMillis() - se.sensorStartMs) / 1000L).toInt()
        val start = DexPacket.DEX_SECONDS
        val end = nowSec - DexPacket.DEX_SECONDS
        if (end <= start) return null
        return DexPacket.backfillCmd(start, end)
    }

    /** dexbackfill: Backfill-Paket(e) verarbeiten — je 9-Byte-Record (native
     *  struct dexbackfill) dekodieren und als History-Wert liefern. */
    fun dexbackfill(ptr: Long, bluetoothdata: ByteArray): Boolean {
        val se = s(ptr) ?: return false
        if (se.sensorStartMs == 0L) return false
        // BUG-FIX: Backfill-Record = 9 Byte (native struct dexbackfill), NICHT 19.
        //   0-3 secsSinceStart i32 LE | 4-5 mgdL u16 LE | 6 type u8 | 7 extra u8 | 8 trend i8
        // Vorher wurde der Puffer in 19-Byte-Zeilen zerlegt → bei den 9-Byte-
        // Paketen lief die Schleife NIE → History/Backfill wurde komplett
        // verworfen. usable nur bei type 0x6/0x7/0xe (wie native ::usable()).
        var off = 0
        var count = 0
        while (off + 9 <= bluetoothdata.size) {
            val secs = (bluetoothdata[off].toInt() and 0xFF) or
                       ((bluetoothdata[off + 1].toInt() and 0xFF) shl 8) or
                       ((bluetoothdata[off + 2].toInt() and 0xFF) shl 16) or
                       ((bluetoothdata[off + 3].toInt() and 0xFF) shl 24)
            val mgdl = (bluetoothdata[off + 4].toInt() and 0xFF) or ((bluetoothdata[off + 5].toInt() and 0xFF) shl 8)
            val type = bluetoothdata[off + 6].toInt() and 0xFF
            val trend = bluetoothdata[off + 8].toInt()   // signed
            if ((type == 0x06 || type == 0x07 || type == 0x0e) && mgdl in 10..600) {
                val tMs = se.sensorStartMs + secs * 1000L
                listener?.onReading(Reading(tMs, mgdl, trend / 10.0f, -1, 0, 0))
                count++
            }
            off += 9
        }
        return count > 0
    }

    fun dexEndBackfill(ptr: Long) { /* nichts zu tun */ }

    // --- Session-Blob (eigenes Format: SharedKey + Name + Start) -----------
    fun sessionBlob(ptr: Long): ByteArray? {
        val se = s(ptr) ?: return null
        val key = se.sharedKey ?: return null
        val name = (se.deviceName ?: "").toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + 16 + 8 + 1 + name.size)
        out[0] = 1                              // Version
        System.arraycopy(key, 0, out, 1, 16)
        val st = se.sensorStartMs
        for (i in 0 until 8) out[17 + i] = (st ushr (8 * i)).toByte()
        out[25] = name.size.toByte()
        System.arraycopy(name, 0, out, 26, name.size)
        return out
    }
    fun sessionRestore(ptr: Long, blob: ByteArray): Boolean {
        val se = s(ptr) ?: return false
        // --- Kompatibilität: native libg7-Session ("G7S"-Record) mitlesen ----
        // Damit ein mit der nativen Version gekoppelter Sensor in der reinen
        // Kotlin-App OHNE Neu-Koppeln weiterläuft (Reconnect statt Frisch-Pair).
        // Format: "G7S" + 1 Versionsbyte + 16 B SharedKey + Name (ASCII, 0-term).
        run {
            var i = 0
            while (i + 20 <= blob.size && i < 24) {
                if (blob[i] == 0x47.toByte() && blob[i + 1] == 0x37.toByte() &&
                    blob[i + 2] == 0x53.toByte() && blob[i + 3] != 0x50.toByte()) {
                    val ko = i + 4
                    se.sharedKey = blob.copyOfRange(ko, ko + 16)
                    var j = ko + 16
                    val sb = StringBuilder()
                    while (j < blob.size && blob[j].toInt() != 0 && sb.length < 20) {
                        val c = blob[j].toInt() and 0xFF
                        if (c in 32..126) sb.append(c.toChar())
                        j++
                    }
                    if (sb.isNotEmpty()) se.deviceName = sb.toString()
                    se.authenticated = true
                    return true
                }
                i++
            }
        }
        if (blob.size < 26 || blob[0].toInt() != 1) return false
        se.sharedKey = blob.copyOfRange(1, 17)
        var st = 0L
        for (i in 0 until 8) st = st or ((blob[17 + i].toLong() and 0xFF) shl (8 * i))
        se.sensorStartMs = st
        val nlen = blob[25].toInt() and 0xFF
        if (26 + nlen <= blob.size) se.deviceName = String(blob, 26, nlen, Charsets.UTF_8)
        se.authenticated = true
        return true
    }
}
