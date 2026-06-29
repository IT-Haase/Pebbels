/*
 * Dexcom G7 — EC-JPAKE-Handshake in reinem Kotlin (ersetzt libg7.so).
 *
 * Port von Jugglucos `dexcom/ecJPake.cpp` (GPL-3.0, © 2021 Jaap Korthals Altes).
 * Nutzt DexCrypto (P-256, SHA-256, Beweis) + BouncyCastle/JCE.
 *
 * Belegte Abläufe aus der Quelle:
 *   - PCert (160 B) = pubkey1(64, X||Y) ++ gv(64) ++ proof(32).
 *   - Round1/2:  mkround12 → PCert(pub, gv=G·r, proof) je Ephemeral-Keypair.
 *   - validate12: Schnorr-Verify  g·r + A·c == gv   (party = B).
 *   - Round3:  makeRound3Cert(g134 = pubA+pub1+pub2, A=g134·(x2·pass),
 *              fester ran3 = fbc971b8…).  validate3 prüft die Gegenseite.
 *   - SharedKey = SHA256( X( (point1 + g4·(−x2·pass))·x2 ) )[0..16].
 *   - Challenge (dexChallenger): ECDSA-Sig über SHA256(input[2..18]) mit
 *     FESTEM App-Schlüssel (privbufC/pubbufC) → 64 B (r||s).
 *   - dex8AES: AES-128-ECB( data8 ++ data8 ) mit SharedKey → erste 8 B.
 *   - pass (Passphrase) = die 4 PIN-Bytes als BigInteger (BN_bin2bn(pin,4)).
 *
 * STATUS: Handshake-Kern komplett. Verdrahtung an G7BleClient (statt DexNative)
 * + Paket-Decode siehe DexSession.kt / DexPacket.kt. Final am Sensor testbar.
 */
package com.g7monitor.ble.dexk

import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal object DexJPake {

    private val n = DexCrypto.n
    private val G = DexCrypto.G
    private val rnd = SecureRandom()

    /** Zufalls-Skalar in [1, n-1] (getrandom()). */
    fun randomScalar(): BigInteger {
        while (true) {
            val k = BigInteger(256, rnd)
            if (k >= BigInteger.ONE && k < n) return k
        }
    }

    /** Festes ran3 aus makeRound3Cert. */
    private val RAN3 = BigInteger(
        "fbc971b837e9491e45a4179ed33865c508a1e0a1d350f5af0f96370695fdc393", 16
    )

    /** PIN (4 Bytes, z. B. ASCII "6558") → passphrase-BigInteger. */
    fun passFromPin(pin: ByteArray): BigInteger = BigInteger(1, pin.copyOf(4))

    // --- PCert ------------------------------------------------------------
    class PCert(val pubkey1: ECPoint, val pubkey2: ECPoint, val hash: BigInteger) {
        /** 160 Byte: X||Y(64) ++ gv(64) ++ proof(32). */
        fun byteify(): ByteArray =
            DexCrypto.pointTo64(pubkey1) + DexCrypto.pointTo64(pubkey2) + DexCrypto.to32(hash)

        companion object {
            fun fromBytes(b: ByteArray, off: Int = 0): PCert = PCert(
                DexCrypto.pointFrom64(b, off),
                DexCrypto.pointFrom64(b, off + 64),
                BigInteger(1, b.copyOfRange(off + 128, off + 160))
            )
            /** PCert.fill: gv = p1·rannum, hash = getproof(...). */
            fun fill(p1: ECPoint, pubKey: ECPoint, priv: BigInteger, rannum: BigInteger): PCert {
                val gv = p1.multiply(rannum).normalize()
                val proof = DexCrypto.getproof(p1, pubKey, gv, priv, rannum)
                return PCert(pubKey, gv, proof)
            }
        }
    }

    /** mkround12: Round-1/2-Zertifikat über Generator G + Ephemeral-Keypair. */
    fun mkRound12(pub: ECPoint, priv: BigInteger): PCert =
        PCert.fill(G, pub, priv, randomScalar())

    /** makeRound3Cert: g134 = pubA+pub1+pub2; A = g134·(x2·pass); fester ran3. */
    fun makeRound3(pub1: ECPoint, pub2: ECPoint, pubA: ECPoint, privB: BigInteger, pass: BigInteger): PCert {
        val x2s = privB.multiply(pass).mod(n)
        val g134 = pubA.add(pub1).add(pub2).normalize()
        val a = g134.multiply(x2s).normalize()
        return PCert.fill(g134, a, x2s, RAN3)
    }

    /** validateZeroKnowledgeProof: g·r + A·c == gv. */
    private fun validZkp(g: ECPoint, cert: PCert, party: ByteArray): Boolean {
        val a = cert.pubkey1
        val gv = cert.pubkey2
        val c = DexCrypto.hashBigInt(g, a, gv, party)
        val res = g.multiply(cert.hash).add(a.multiply(c)).normalize()
        return res == gv.normalize()
    }

    /** validate12: Peer-Round-1/2 prüfen (party B, Generator G). */
    fun validate12(cert: PCert): Boolean = validZkp(G, cert, DexCrypto.PARTY_B)

    /** validate3: Peer-Round-3 prüfen (g = g1+g2+g3). */
    fun validate3(g1: ECPoint, g2: ECPoint, cert1: PCert, cert3: PCert): Boolean {
        val g = g1.add(g2).add(cert1.pubkey1).normalize()
        return validZkp(g, cert3, DexCrypto.PARTY_B)
    }

    /** mkSharedKey: SHA256( X( (point1 + g4·(−x2·pass))·x2 ) ) → 16 B. */
    fun sharedKey(peer2: PCert, peer3: PCert, pass: BigInteger, x2: BigInteger): ByteArray {
        val point1 = peer3.pubkey1
        val g4 = peer2.pubkey1
        val num = x2.multiply(pass).mod(n)
        val key1 = g4.multiply(num).negate()
        val key = point1.add(key1).multiply(x2).normalize()
        val x = key.affineXCoord.toBigInteger()
        return DexCrypto.sha256(DexCrypto.to32(x)).copyOf(16)
    }

    // --- Challenge (dexChallenger): ECDSA mit festem App-Schlüssel --------
    private val PRIV_C = BigInteger(
        1, byteArrayOf(
            0x7c, 0xfb.toByte(), 0xd5.toByte(), 0x96.toByte(), 0xf6.toByte(), 0xe7.toByte(),
            0x44, 0x77, 0xb8.toByte(), 0xc0.toByte(), 0xe9.toByte(), 0xf6.toByte(), 0xf7.toByte(),
            0xa1.toByte(), 0x74, 0x27, 0x5e, 0x10, 0x1e, 0xf6.toByte(), 0xbf.toByte(), 0x7d,
            0x18, 0xca.toByte(), 0xf0.toByte(), 0x11, 0x81.toByte(), 0xd1.toByte(), 0x27,
            0xb5.toByte(), 0x79
        )
    )

    /** getchallenge: ECDSA-Signatur über SHA256(input[2..18]) → 64 B (r||s). */
    fun challenge(input: ByteArray): ByteArray {
        val hash = DexCrypto.sha256(input.copyOfRange(2, 18))
        val domain = ECDomainParameters(DexCrypto.curve, G, n)
        val signer = ECDSASigner()
        signer.init(true, ParametersWithRandom(ECPrivateKeyParameters(PRIV_C, domain), rnd))
        val rs = signer.generateSignature(hash)   // [r, s]
        return DexCrypto.to32(rs[0]) + DexCrypto.to32(rs[1])
    }

    /** dex8AES / encrypt8AES: AES-128-ECB( data8 ++ data8 ) → erste 8 B. */
    fun aes8(sharedKey: ByteArray, data8: ByteArray): ByteArray {
        val doubled = data8.copyOf(8) + data8.copyOf(8)   // 16 B
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedKey.copyOf(16), "AES"))
        return cipher.doFinal(doubled).copyOf(8)
    }
}
