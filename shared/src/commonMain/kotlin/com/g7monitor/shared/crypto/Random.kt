/*
 * Pebbels Shared Core (KMP) — sichere Zufallsbytes.
 * Einziger plattformspezifischer Krypto-Baustein (expect/actual):
 *   Android → java.security.SecureRandom, iOS → SecRandomCopyBytes.
 */
package com.g7monitor.shared.crypto

import com.ionspin.kotlin.bignum.integer.BigInteger

/** Kryptographisch sichere Zufallsbytes (plattformspezifisch). */
expect fun secureRandomBytes(size: Int): ByteArray

/** Zufalls-Skalar in [1, n-1] über sichere Zufallsbytes (getrandom()). */
internal fun randomScalar(): BigInteger {
    while (true) {
        val k = bi(bytesToHex(secureRandomBytes(32)))
        if (k >= BigInteger.ONE && k < Ec.n) return k
    }
}
