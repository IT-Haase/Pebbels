/*
 * Pebbels Shared Core (KMP commonMain) — ECDSA über NIST P-256, reines Kotlin.
 * Ersetzt BouncyCastle ECDSASigner. Standard-Algorithmus (FIPS 186-4):
 *   r = x(k·G) mod n ;  s = k⁻¹·(e + r·d) mod n .
 * Der Nonce k kommt aus secureRandomBytes (jede Signatur unterscheidet sich —
 * der Sensor prüft nur die Gültigkeit gegen den festen App-Pubkey).
 * signWithK ist gegen den RFC-6979-P-256/SHA-256-Vektor verifiziert.
 */
package com.g7monitor.shared.crypto

import com.ionspin.kotlin.bignum.integer.BigInteger

object Ecdsa {
    private val n = Ec.n

    /** Signatur mit explizitem Nonce k (deterministisch, für Tests/Vektoren). */
    fun signWithK(d: BigInteger, hash: ByteArray, k: BigInteger): Pair<BigInteger, BigInteger> {
        val e = bi(bytesToHex(hash))
        val r = Ec.mul(k, Ec.G).x.mod(n)
        val s = (k.modInverse(n) * (e + r * d)).mod(n)
        return r to s
    }

    /** Signatur mit sicherem Zufalls-Nonce. */
    fun sign(d: BigInteger, hash: ByteArray): Pair<BigInteger, BigInteger> {
        while (true) {
            val (r, s) = signWithK(d, hash, randomScalar())
            if (r != BigInteger.ZERO && s != BigInteger.ZERO) return r to s
        }
    }

    /** Standard-ECDSA-Verifikation (für Selbsttests). */
    fun verify(q: Ec.Pt, hash: ByteArray, r: BigInteger, s: BigInteger): Boolean {
        if (r < BigInteger.ONE || r >= n || s < BigInteger.ONE || s >= n) return false
        val e = bi(bytesToHex(hash))
        val w = s.modInverse(n)
        val u1 = (e * w).mod(n)
        val u2 = (r * w).mod(n)
        val x = Ec.add(Ec.mul(u1, Ec.G), Ec.mul(u2, q))
        if (x.inf) return false
        return x.x.mod(n) == r
    }
}
