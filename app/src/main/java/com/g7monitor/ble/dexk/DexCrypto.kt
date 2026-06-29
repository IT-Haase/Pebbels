/*
 * Dexcom G7 — EC-JPAKE-Krypto in reinem Kotlin (ersetzt libg7.so).
 *
 * 1:1-Port der Krypto-Grundbausteine aus Jugglucos `dexcom/ecJPake.cpp`
 * (GPL-3.0, © 2021 Jaap Korthals Altes) — hier neu in Kotlin/BouncyCastle
 * geschrieben, kein abgeleiteter Binärcode mehr.
 *
 * Belegte Fakten aus der Quelle:
 *   - Kurve: NIST P-256 (secp256r1), Generator G, Ordnung n.
 *   - Hash: SHA-256.
 *   - Party-IDs:  A = "client" (63 6c 69 65 6e 74),  B = 37 56 27 67 56 27.
 *   - Punkt-Kodierung im Hash: 65-Byte unkomprimiert (04 || X32 || Y32).
 *   - PCert/Pubkey roh: 32-Byte X || 32-Byte Y (links mit 0 aufgefüllt).
 *   - Längenpräfix `setnum`: 4-Byte BIG-ENDIAN.
 *   - Schnorr-Beweis:  proof = (rannum − hash·privKey) mod n,
 *     mit hash = BN_bin2bn(SHA256(frame)) mod n.
 *
 * STATUS: Grundbausteine (Kurve, Hash, Punkt-Kodierung, Beweis). Die
 * Handshake-Runden (makeRound12/3, dexChallenger), die Schlüssel-Ableitung
 * und der Paket-Decode (AES-CCM) folgen in DexJPake.kt / DexPacket.kt.
 */
package com.g7monitor.ble.dexk

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest

internal object DexCrypto {

    // --- Kurve P-256 -------------------------------------------------------
    private val params = CustomNamedCurves.getByName("secp256r1")
    val curve = params.curve
    val G: ECPoint = params.g
    val n: BigInteger = params.n            // Gruppen-Ordnung (entspricht getorder())

    // Party-IDs aus ecJPake.cpp (a_bytes / b_bytes)
    val PARTY_A = byteArrayOf(0x63, 0x6c, 0x69, 0x65, 0x6e, 0x74)             // "client"
    val PARTY_B = byteArrayOf(0x37, 0x56, 0x27, 0x67, 0x56, 0x27)

    // --- SHA-256 (sha256()) ------------------------------------------------
    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    // --- 4-Byte-Big-Endian-Länge (setnum()) --------------------------------
    private fun setnum(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte()
        )

    // --- Punkt → 65-Byte unkomprimiert (tobytes4 / point2oct) --------------
    fun pointTo65(p: ECPoint): ByteArray = p.getEncoded(false)   // 0x04||X32||Y32

    // --- 65 Byte → Punkt ---------------------------------------------------
    fun pointFrom65(b: ByteArray): ECPoint = curve.decodePoint(b)

    // --- Punkt → 64 Byte X||Y (für PCert-Pubkey, je 32 Byte, 0-gepadded) ---
    fun pointTo64(p: ECPoint): ByteArray {
        val np = p.normalize()
        val x = to32(np.affineXCoord.toBigInteger())
        val y = to32(np.affineYCoord.toBigInteger())
        return x + y
    }

    // --- 64 Byte X||Y → Punkt (PCert::frombytes) ---------------------------
    fun pointFrom64(b: ByteArray, off: Int = 0): ECPoint {
        val x = BigInteger(1, b.copyOfRange(off, off + 32))
        val y = BigInteger(1, b.copyOfRange(off + 32, off + 64))
        return curve.createPoint(x, y)
    }

    /** BigInteger → 32-Byte Big-Endian, links mit 0 aufgefüllt. */
    fun to32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()                         // ggf. mit Vorzeichen-0x00
        val out = ByteArray(32)
        when {
            raw.size == 32 -> System.arraycopy(raw, 0, out, 0, 32)
            raw.size == 33 && raw[0].toInt() == 0 ->       // führendes Vorzeichen-Byte
                System.arraycopy(raw, 1, out, 0, 32)
            raw.size < 32 ->
                System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
            else ->                                        // länger: hinteren 32 nehmen
                System.arraycopy(raw, raw.size - 32, out, 0, 32)
        }
        return out
    }

    // --- Hash-Frame (mkhash): [len|p1][len|gv][len|pubKey][len|party] -------
    fun mkhash(p1: ECPoint, gv: ECPoint, pubKey: ECPoint, party: ByteArray): ByteArray {
        val ec = 65
        val buf = ArrayList<Byte>(4 * 4 + party.size + 3 * ec)
        fun put(b: ByteArray) { for (x in b) buf.add(x) }
        put(setnum(ec));           put(pointTo65(p1))
        put(setnum(ec));           put(pointTo65(gv))
        put(setnum(ec));           put(pointTo65(pubKey))
        put(setnum(party.size));   put(party)
        return sha256(buf.toByteArray())
    }

    // --- Hash → BigInteger mod n (mkhashBigInt) ----------------------------
    fun hashBigInt(p1: ECPoint, pubKey: ECPoint, gv: ECPoint, party: ByteArray): BigInteger =
        BigInteger(1, mkhash(p1, gv, pubKey, party)).mod(n)

    // --- Schnorr-Beweis (getproof): (rannum − hash·privKey) mod n ----------
    fun getproof(
        p1: ECPoint, pubKey: ECPoint, gv: ECPoint,
        privateKey: BigInteger, rannum: BigInteger
    ): BigInteger {
        val h = hashBigInt(p1, pubKey, gv, PARTY_A)
        return rannum.subtract(h.multiply(privateKey)).mod(n)
    }
}
