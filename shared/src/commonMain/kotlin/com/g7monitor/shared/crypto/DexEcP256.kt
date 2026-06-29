/*
 * Pebbels Shared Core (KMP commonMain) — NIST P-256 in reinem Kotlin.
 * Eigene affine Punktmathematik (Add/Double/ScalarMult) über ionspin-Bignum.
 * Ersetzt BouncyCastle ECPoint; verifiziert bitgleich gegen die G7-Referenz.
 */
package com.g7monitor.shared.crypto

import com.ionspin.kotlin.bignum.integer.BigInteger

internal fun bi(hex: String): BigInteger = BigInteger.parseString(hex, 16)

object Ec {
    val p = bi("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff")
    val a = p - BigInteger.fromInt(3)
    val n = bi("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")
    val G = Pt(
        bi("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"),
        bi("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
    )

    class Pt private constructor(val x: BigInteger, val y: BigInteger, val inf: Boolean) {
        constructor(x: BigInteger, y: BigInteger) : this(x, y, false)
        companion object { val INF = Pt(BigInteger.ZERO, BigInteger.ZERO, true) }
    }

    private fun m(v: BigInteger) = v.mod(p)
    private fun inv(v: BigInteger) = v.mod(p).modInverse(p)

    fun dbl(P: Pt): Pt {
        if (P.inf) return P
        if (P.y == BigInteger.ZERO) return Pt.INF
        val s = m((P.x * P.x * BigInteger.fromInt(3) + a) * inv(P.y * BigInteger.fromInt(2)))
        val x3 = m(s * s - P.x * BigInteger.fromInt(2))
        return Pt(x3, m(s * (P.x - x3) - P.y))
    }
    fun add(P: Pt, Q: Pt): Pt {
        if (P.inf) return Q; if (Q.inf) return P
        if (P.x == Q.x) return if (m(P.y + Q.y) == BigInteger.ZERO) Pt.INF else dbl(P)
        val s = m((Q.y - P.y) * inv(Q.x - P.x))
        val x3 = m(s * s - P.x - Q.x)
        return Pt(x3, m(s * (P.x - x3) - P.y))
    }
    fun neg(P: Pt): Pt = if (P.inf) P else Pt(P.x, m(BigInteger.ZERO - P.y))
    fun mul(k0: BigInteger, P: Pt): Pt {
        val k = k0.mod(n)
        var r = Pt.INF
        for (i in 255 downTo 0) { r = dbl(r); if (k.bitAt(i.toLong())) r = add(r, P) }
        return r
    }

    // --- Kodierung (Hex-basiert, byte-genau, 32-Byte-padded) ---
    fun to32(v: BigInteger): ByteArray = hexToBytes(v.mod(p).toString(16).padStart(64, '0').takeLast(64))
    /** wie to32, aber ohne mod p — für Skalare/Proofs (mod n). */
    fun to32n(v: BigInteger): ByteArray = hexToBytes(v.toString(16).padStart(64, '0').takeLast(64))
    fun to64(P: Pt): ByteArray = to32(P.x) + to32(P.y)
    fun to65(P: Pt): ByteArray = byteArrayOf(4) + to32(P.x) + to32(P.y)
    fun from64(b: ByteArray, off: Int): Pt = Pt(
        bi(bytesToHex(b.copyOfRange(off, off + 32))),
        bi(bytesToHex(b.copyOfRange(off + 32, off + 64)))
    )
}
