/*
 * Pebbels Shared Core (KMP commonMain) — EC-JPAKE-Handshake (Dexcom G7/ONE+).
 * Voller Port von ecJPake.cpp, reines Kotlin auf Ec (P-256) + Sha256 + Aes128
 * + Ecdsa. Verifiziert bitgleich gegen die C++/BouncyCastle-Referenz
 * (round3-Cert, sharedKey, dex8AES). Spiegelt die App-API (PCert-basiert).
 */
package com.g7monitor.shared.crypto

import com.ionspin.kotlin.bignum.integer.BigInteger

object DexJPake {
    private val n = Ec.n
    private val G = Ec.G

    private val PARTY_A = byteArrayOf(0x63, 0x6c, 0x69, 0x65, 0x6e, 0x74) // "client"
    private val PARTY_B = byteArrayOf(0x37, 0x56, 0x27, 0x67, 0x56, 0x27)
    private val RAN3 = bi("fbc971b837e9491e45a4179ed33865c508a1e0a1d350f5af0f96370695fdc393")
    /** Fester App-Schlüssel für dexChallenger (ECDSA). */
    private val PRIV_C = bi("7cfbd596f6e74477b8c0e9f6f7a174275e101ef6bf7d18caf01181d127b579")

    private fun setnum(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** PIN (4 Bytes) → passphrase-BigInteger (BN_bin2bn(pin,4)). */
    fun passFromPin(pin: ByteArray): BigInteger = bi(bytesToHex(pin.copyOf(4)))

    /** Zufalls-Skalar in [1, n-1]. */
    fun randomScalar(): BigInteger = com.g7monitor.shared.crypto.randomScalar()

    /** mkhashBigInt: SHA256( [65|p1][65|gv][65|pub][len|party] ) mod n. */
    private fun hashBI(p1: Ec.Pt, pub: Ec.Pt, gv: Ec.Pt, party: ByteArray): BigInteger {
        val frame = setnum(65) + Ec.to65(p1) + setnum(65) + Ec.to65(gv) + setnum(65) + Ec.to65(pub) + setnum(party.size) + party
        return bi(bytesToHex(Sha256.hash(frame))).mod(n)
    }

    /** getproof: (rannum − hash·privKey) mod n. */
    fun getproof(p1: Ec.Pt, pub: Ec.Pt, gv: Ec.Pt, priv: BigInteger, ran: BigInteger): BigInteger =
        (ran - hashBI(p1, pub, gv, PARTY_A) * priv).mod(n)

    /** Schnorr-Zertifikat: pubkey1(64) ++ gv(64) ++ proof(32) = 160 B. */
    class PCert(val pubkey1: Ec.Pt, val pubkey2: Ec.Pt, val hash: BigInteger) {
        fun byteify(): ByteArray = Ec.to64(pubkey1) + Ec.to64(pubkey2) + Ec.to32n(hash)

        companion object {
            fun fromBytes(b: ByteArray, off: Int = 0): PCert = PCert(
                Ec.from64(b, off),
                Ec.from64(b, off + 64),
                bi(bytesToHex(b.copyOfRange(off + 128, off + 160)))
            )
            /** gv = p1·rannum, proof = getproof(...). */
            fun fill(p1: Ec.Pt, pub: Ec.Pt, priv: BigInteger, ran: BigInteger): PCert {
                val gv = Ec.mul(ran, p1)
                return PCert(pub, gv, getproof(p1, pub, gv, priv, ran))
            }
        }
    }

    /** Round-1/2-Zertifikat über Generator G + Ephemeral-Keypair. */
    fun mkRound12(pub: Ec.Pt, priv: BigInteger): PCert = PCert.fill(G, pub, priv, randomScalar())

    /** Round3: g134 = pubA+pub1+pub2; A = g134·(privB·pass); fester ran3. */
    fun makeRound3(pub1: Ec.Pt, pub2: Ec.Pt, pubA: Ec.Pt, privB: BigInteger, pass: BigInteger): PCert {
        val x2s = (privB * pass).mod(n)
        val g134 = Ec.add(Ec.add(pubA, pub1), pub2)
        val a = Ec.mul(x2s, g134)
        return PCert.fill(g134, a, x2s, RAN3)
    }

    /** validateZeroKnowledgeProof: g·r + A·c == gv. */
    private fun validZkp(g: Ec.Pt, cert: PCert, party: ByteArray): Boolean {
        val a = cert.pubkey1
        val gv = cert.pubkey2
        val c = hashBI(g, a, gv, party)
        val res = Ec.add(Ec.mul(cert.hash, g), Ec.mul(c, a))
        return !res.inf && res.x == gv.x && res.y == gv.y
    }

    fun validate12(cert: PCert): Boolean = validZkp(G, cert, PARTY_B)
    fun validate3(g1: Ec.Pt, g2: Ec.Pt, cert1: PCert, cert3: PCert): Boolean =
        validZkp(Ec.add(Ec.add(g1, g2), cert1.pubkey1), cert3, PARTY_B)

    /** mkSharedKey: SHA256( X( (point1 + g4·(−x2·pass))·x2 ) )[0..16]. */
    fun sharedKey(peer2: PCert, peer3: PCert, pass: BigInteger, x2: BigInteger): ByteArray {
        val point1 = peer3.pubkey1
        val g4 = peer2.pubkey1
        val num = (x2 * pass).mod(n)
        val key = Ec.mul(x2, Ec.add(point1, Ec.neg(Ec.mul(num, g4))))
        return Sha256.hash(Ec.to32(key.x)).copyOf(16)
    }

    /** dexChallenger: ECDSA-Sig über SHA256(input[2..18]) → 64 B (r||s). */
    fun challenge(input: ByteArray): ByteArray {
        val hash = Sha256.hash(input.copyOfRange(2, 18))
        val (r, s) = Ecdsa.sign(PRIV_C, hash)
        return Ec.to32n(r) + Ec.to32n(s)
    }

    /** dex8AES: AES-128-ECB( data8 ++ data8 ) → erste 8 B. */
    fun aes8(sharedKey: ByteArray, data8: ByteArray): ByteArray = Aes128.aes8(sharedKey, data8)
}
