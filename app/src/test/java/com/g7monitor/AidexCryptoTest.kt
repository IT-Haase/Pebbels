package com.g7monitor

import com.g7monitor.shared.aidex.AidexXCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** Verifiziert die pure-Kotlin-AiDEX-Krypto (Shared) gegen die JVM-Referenz. */
class AidexCryptoTest {

    private fun jvmCfb(mode: Int, data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CFB/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    @Test fun cfbMatchesJvm() {
        repeat(200) {
            val key = Random.nextBytes(16)
            val iv = Random.nextBytes(16)
            val data = Random.nextBytes(Random.nextInt(1, 48))
            assertArrayEquals("enc", jvmCfb(Cipher.ENCRYPT_MODE, data, key, iv), AidexXCrypto.encrypt(data, key, iv))
            assertArrayEquals("dec", jvmCfb(Cipher.DECRYPT_MODE, data, key, iv), AidexXCrypto.decrypt(data, key, iv))
        }
    }

    private fun snVal(c: Char): Int = when {
        c in '0'..'9' -> c - '0'
        c in 'A'..'Z' -> c.code - 0x37
        c in 'a'..'z' -> c.code - 0x57
        else -> c.code
    }

    @Test fun md5DerivationMatchesJvm() {
        val md = MessageDigest.getInstance("MD5")
        for (serial in listOf("22222DTZMP", "AB12CD34EF", "0000000000")) {
            val b = ByteArray(serial.length) { snVal(serial[it]).toByte() }
            val n = minOf(10, b.size)
            for (i in 0 until n) b[i] = ((b[i].toInt() and 0xFF) * 17 + 0x13).toByte()
            assertArrayEquals("iv $serial", md.digest(b), AidexXCrypto.makeIv(serial))

            val b2 = ByteArray(serial.length) { snVal(serial[it]).toByte() }
            val trans = ByteArray(b2.size) { ((b2[it].toInt() and 0xFF) * 13 + 61).toByte() }
            assertArrayEquals("askKey $serial", md.digest(trans), AidexXCrypto.makeAskKey(serial))
        }
    }

    @Test fun crcVectors() {
        assertEquals(0x203B, AidexXCrypto.crc16CcittFalse(byteArrayOf(0x23, 0x01, 0x00)))
        // crc8_maxim über 16 Bytes 00..0F == 0x3C (Python-Referenz)
        val d = ByteArray(16) { it.toByte() }
        assertEquals(0x3C, AidexXCrypto.crc8Maxim(d))
    }
}
