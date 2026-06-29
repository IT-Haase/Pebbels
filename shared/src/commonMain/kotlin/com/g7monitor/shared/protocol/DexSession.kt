/*
 * Pebbels Shared Core (KMP commonMain) — Dexcom-G7-Session in reinem Kotlin.
 *
 * Plattformunabhängiger Handshake-/Decode-Kern, von Android- UND iOS-BLE-Treiber
 * genutzt. Im Gegensatz zur Android-Altfassung INSTANZ-basiert (eine Session pro
 * Pairing) statt Pointer-Registry — damit keine JVM-Concurrency-Primitive nötig.
 * Krypto = DexJPake (P-256/EC-JPAKE/AES/ECDSA), Decode = DexPacket.
 *
 * Handshake (aus G7BleClient verifiziert):
 *   R1: makeRound12(0) → KeyA-Cert ; putPubKey(0, Peer-Cert1)
 *   R2: makeRound12(1) → KeyB-Cert ; putPubKey(1, Peer-Cert2)
 *   R3: putPubKey(2, Peer-Cert3) ; makeRound3() → unser Round3 + sharedKey
 *   Auth: authConfirm(random8) muss mit der Sensor-Antwort übereinstimmen.
 */
package com.g7monitor.shared.protocol

import com.g7monitor.shared.crypto.DexJPake
import com.g7monitor.shared.crypto.Ec
import com.g7monitor.shared.platform.currentTimeMillis
import com.ionspin.kotlin.bignum.integer.BigInteger

class DexSession(val serial: String, pin: ByteArray) {

    data class Reading(
        val timestampMs: Long, val mgdl: Int, val rateMgdlPerMin: Float,
        val predictedMgdl: Int, val state: Int, val info: Int,
    )

    private val pass: BigInteger = DexJPake.passFromPin(pin)
    private val privA: BigInteger = DexJPake.randomScalar()
    private val pubA: Ec.Pt = Ec.mul(privA, Ec.G)
    private val privB: BigInteger = DexJPake.randomScalar()
    private val pubB: Ec.Pt = Ec.mul(privB, Ec.G)

    private var peer1: DexJPake.PCert? = null
    private var peer2: DexJPake.PCert? = null
    private var peer3: DexJPake.PCert? = null

    var sharedKey: ByteArray? = null
        private set
    var deviceName: String? = null
    var authenticated: Boolean = false
        private set
    var sensorStartMs: Long = 0L
        private set
    private var lastIndex: Int = -1

    // --- J-PAKE-Runden ----------------------------------------------------
    /** which=0 → KeyA-Cert (R1), which=1 → KeyB-Cert (R2). 160 B. */
    fun makeRound12(which: Int): ByteArray =
        (if (which == 0) DexJPake.mkRound12(pubA, privA) else DexJPake.mkRound12(pubB, privB)).byteify()

    /** which=0/1/2 → Peer-Cert1/2/3 speichern. */
    fun putPubKey(which: Int, input: ByteArray) {
        val cert = DexJPake.PCert.fromBytes(input)
        when (which) { 0 -> peer1 = cert; 1 -> peer2 = cert; else -> peer3 = cert }
    }

    /** Unser Round3-Cert + sharedKey berechnen. 160 B, null falls Peer-Certs fehlen. */
    fun makeRound3(): ByteArray? {
        val p1 = peer1 ?: return null
        val p2 = peer2 ?: return null
        val p3 = peer3 ?: return null
        val cert = DexJPake.makeRound3(p1.pubkey1, p2.pubkey1, pubA, privB, pass)
        sharedKey = DexJPake.sharedKey(p2, p3, pass, privB)
        return cert.byteify()
    }

    /** dex8AES: 8 Byte ab data[startdat] mit sharedKey verschlüsseln → 8 B. */
    fun authConfirm(data: ByteArray, startdat: Int = 0): ByteArray? {
        val key = sharedKey ?: return null
        val enc = DexJPake.aes8(key, data.copyOfRange(startdat, startdat + 8))
        authenticated = true
        return enc
    }

    /** dexChallenger: ECDSA-Signatur über den Challenge → 64 B (r||s). */
    fun challenge(value: ByteArray): ByteArray = DexJPake.challenge(value)

    fun resetKeys() {
        peer1 = null; peer2 = null; peer3 = null
        sharedKey = null; authenticated = false
    }

    // --- Glukose / Backfill ----------------------------------------------
    /** 0x4E-Paket parsen; leitet beim ersten Paket den Sensor-Start ab. */
    fun processData(data: ByteArray): Reading? {
        val g = DexPacket.parseGlucose(data) ?: return null
        val nowMs = currentTimeMillis()
        if (sensorStartMs == 0L) sensorStartMs = nowMs - (g.secsSinceStart - g.age) * 1000L
        val tMs = sensorStartMs + (g.secsSinceStart - g.age) * 1000L
        lastIndex = maxOf(lastIndex, g.index)
        return Reading(tMs, g.mgdl, g.rateMgdlPerMin, g.predictedMgdl, g.state, g.info)
    }

    /** [0x59][start][end] in Sek. seit Start — die GESAMTE verfügbare History. */
    fun backfillCmd(): ByteArray? {
        if (sensorStartMs == 0L) return null
        val nowSec = ((currentTimeMillis() - sensorStartMs) / 1000L).toInt()
        val start = DexPacket.DEX_SECONDS
        val end = nowSec - DexPacket.DEX_SECONDS
        if (end <= start) return null
        return DexPacket.backfillCmd(start, end)
    }

    /** Backfill-Paket(e): je 9-Byte-Record (native struct dexbackfill) dekodieren.
     *  0-3 secs i32 LE | 4-5 mgdl u16 LE | 6 type u8 | 7 extra | 8 trend i8.
     *  usable nur bei type 0x6/0x7/0xe. */
    fun parseBackfill(data: ByteArray): List<Reading> {
        if (sensorStartMs == 0L) return emptyList()
        val out = ArrayList<Reading>()
        var off = 0
        while (off + 9 <= data.size) {
            val secs = (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8) or
                       ((data[off + 2].toInt() and 0xFF) shl 16) or ((data[off + 3].toInt() and 0xFF) shl 24)
            val mgdl = (data[off + 4].toInt() and 0xFF) or ((data[off + 5].toInt() and 0xFF) shl 8)
            val type = data[off + 6].toInt() and 0xFF
            val trend = data[off + 8].toInt()
            if ((type == 0x06 || type == 0x07 || type == 0x0e) && mgdl in 10..600) {
                out.add(Reading(sensorStartMs + secs * 1000L, mgdl, trend / 10.0f, -1, 0, 0))
            }
            off += 9
        }
        return out
    }

    // --- Reconnect-Blob (portables v1-Format: Key + Start + Name) ----------
    fun toBlob(): ByteArray? {
        val key = sharedKey ?: return null
        val name = (deviceName ?: "").encodeToByteArray()
        val out = ByteArray(1 + 16 + 8 + 1 + name.size)
        out[0] = 1
        key.copyInto(out, 1, 0, 16)
        val st = sensorStartMs
        for (i in 0 until 8) out[17 + i] = (st ushr (8 * i)).toByte()
        out[25] = name.size.toByte()
        name.copyInto(out, 26)
        return out
    }

    fun fromBlob(blob: ByteArray): Boolean {
        if (blob.size < 26 || blob[0].toInt() != 1) return false
        sharedKey = blob.copyOfRange(1, 17)
        var st = 0L
        for (i in 0 until 8) st = st or ((blob[17 + i].toLong() and 0xFF) shl (8 * i))
        sensorStartMs = st
        val nlen = blob[25].toInt() and 0xFF
        if (26 + nlen <= blob.size) deviceName = blob.copyOfRange(26, 26 + nlen).decodeToString()
        authenticated = true
        return true
    }

    companion object {
        /** getDexCertSize: erwartete Cert-Länge = uint16 LE @ Offset 3.
         *  Frame = [0x0B][0x00][which][size_lo][size_hi]. */
        fun certSize(ar: ByteArray): Int {
            if (ar.size < 5) return -1
            if ((ar[0].toInt() and 0xFF) != 0x0B) return -1
            if (ar[1].toInt() != 0) return -1
            if (ar[2].toInt() < 0) return -1
            return (ar[3].toInt() and 0xFF) or ((ar[4].toInt() and 0xFF) shl 8)
        }
    }
}
