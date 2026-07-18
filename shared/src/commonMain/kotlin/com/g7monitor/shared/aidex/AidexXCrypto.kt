/*
 * AiDEX X / LinX — Krypto & CRCs, plattformunabhängig (Android + iOS/Native).
 *
 * Port aus Jugglucos aidexx/{mkiv,decrypt,crc}.cpp (GPL-3.0, © 2021 Jaap Korthals
 * Altes). Nutzt das pure-Kotlin AES-128 aus com.g7monitor.shared.crypto.Aes128
 * und ein pure-Kotlin MD5 — damit läuft alles identisch auf JVM und iOS.
 *
 * Verifiziert gegen Jugglucos Testvektoren (siehe AidexXCryptoTest, JVM):
 *   crc8_maxim(16B)==0x64 · crc16_ccitt_false(23 01 00)==0x203B
 *   AES-128-CFB enc(23 01 00 3B 20)==AE ED CE 60 E5 · dec(23 EA 09 5A 7D)==71 58 4F 96 B6
 */
package com.g7monitor.shared.aidex

import com.g7monitor.shared.crypto.Aes128

/** Pure-Kotlin MD5 — nur für die SN-Schlüsselableitung des AiDEX. */
internal object Md5 {
    // Standard-MD5-Konstanten (floor(abs(sin(i+1))*2^32)) — fest, damit bit-genau auf allen Plattformen.
    private val K: IntArray = longArrayOf(
        0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee,
        0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
        0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be,
        0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
        0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa,
        0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
        0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed,
        0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
        0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c,
        0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
        0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05,
        0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
        0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039,
        0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
        0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1,
        0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
    ).map { it.toInt() }.toIntArray()

    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    fun hash(msg: ByteArray): ByteArray {
        val bitLen = msg.size.toLong() * 8L
        val padLen = ((56 - (msg.size + 1) % 64) + 64) % 64
        val total = msg.size + 1 + padLen + 8
        val m = ByteArray(total)
        msg.copyInto(m)
        m[msg.size] = 0x80.toByte()
        for (i in 0 until 8) m[total - 8 + i] = ((bitLen ushr (8 * i)) and 0xFF).toByte()

        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476

        var off = 0
        while (off < total) {
            val w = IntArray(16) { j ->
                val p = off + j * 4
                (m[p].toInt() and 0xFF) or ((m[p + 1].toInt() and 0xFF) shl 8) or
                    ((m[p + 2].toInt() and 0xFF) shl 16) or ((m[p + 3].toInt() and 0xFF) shl 24)
            }
            var a = a0; var b = b0; var c = c0; var d = d0
            for (i in 0 until 64) {
                val f: Int; val g: Int
                when {
                    i < 16 -> { f = (b and c) or (b.inv() and d); g = i }
                    i < 32 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    i < 48 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else   -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val tmp = d
                d = c; c = b
                val x = a + f + K[i] + w[g]
                val s = S[i]
                b += (x shl s) or (x ushr (32 - s))
                a = tmp
            }
            a0 += a; b0 += b; c0 += c; d0 += d
            off += 64
        }
        val out = ByteArray(16)
        val words = intArrayOf(a0, b0, c0, d0)
        for (wi in 0 until 4) for (i in 0 until 4) out[wi * 4 + i] = ((words[wi] ushr (8 * i)) and 0xFF).toByte()
        return out
    }
}

/** AES-128 im CFB128-Modus (entspricht OpenSSL EVP_aes_128_cfb128 / Java "AES/CFB/NoPadding"). */
internal object AesCfb128 {
    fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = run(key, iv, data, decrypt = false)
    fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = run(key, iv, data, decrypt = true)

    private fun run(key: ByteArray, iv: ByteArray, data: ByteArray, decrypt: Boolean): ByteArray {
        val out = ByteArray(data.size)
        var feedback = iv.copyOf(16)
        var off = 0
        while (off < data.size) {
            val ks = Aes128.encryptBlock(key, feedback)          // Keystream = E(feedback)
            val n = minOf(16, data.size - off)
            val next = ByteArray(16)
            for (i in 0 until n) {
                val outByte = (data[off + i].toInt() xor ks[i].toInt()) and 0xFF
                out[off + i] = outByte.toByte()
                // CFB128: nächstes Feedback ist der CHIFFRE-Block.
                next[i] = if (decrypt) data[off + i] else outByte.toByte()
            }
            feedback = next
            off += 16
        }
        return out
    }
}

/** AiDEX-Krypto: alles wird aus der Seriennummer abgeleitet — keine PIN, kein Server. */
object AidexXCrypto {

    private fun snCharToVal(c: Int): Int = when {
        c in '0'.code..'9'.code -> c - '0'.code
        c in 'A'.code..'Z'.code -> c - 0x37   // 'A'(0x41) -> 0x0A
        c in 'a'.code..'z'.code -> c - 0x57   // 'a'(0x61) -> 0x0A
        else -> c
    }

    private fun snToBytes(serial: String): ByteArray {
        val out = ByteArray(serial.length)
        for (i in serial.indices) out[i] = snCharToVal(serial[i].code).toByte()
        return out
    }

    /** IV = md5( (erste 10 SN-Bytes je *17+0x13) ). */
    fun makeIv(serial: String): ByteArray {
        val b = snToBytes(serial)
        val n = minOf(10, b.size)
        for (i in 0 until n) b[i] = ((b[i].toInt() and 0xFF) * 17 + 0x13).toByte()
        return Md5.hash(b)
    }

    /** „askKey" (entschlüsselt den Masterkey) = md5( SN-Bytes je *13+61 ). */
    fun makeAskKey(serial: String): ByteArray {
        val b = snToBytes(serial)
        val trans = ByteArray(b.size) { ((b[it].toInt() and 0xFF) * 13 + 61).toByte() }
        return Md5.hash(trans)
    }

    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray = AesCfb128.decrypt(key, iv, data)
    fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray = AesCfb128.encrypt(key, iv, data)

    // --- CRCs ---
    fun crc8Maxim(data: ByteArray, len: Int = data.size): Int {
        var crc = 0
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8C else crc ushr 1 }
        }
        return crc and 0xFF
    }

    fun crc16CcittFalse(data: ByteArray, len: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                      else (crc shl 1) and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    /** data + crc16 (little-endian) angehängt — wie Jugglucos addcrc16. */
    fun withCrc16(data: ByteArray): ByteArray {
        val crc = crc16CcittFalse(data)
        return data + byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())
    }

    /** crc32 „normal" (Poly 0x04C11DB7, MSB-first) mit Startwert — für das BLE-Advertisement. */
    fun crc32Normal(buf: ByteArray, offset: Int, len: Int, seed: Long): Long {
        var crc = seed and 0xFFFFFFFFL
        for (i in 0 until len) {
            crc = crc xor (((buf[offset + i].toInt() and 0xFF).toLong()) shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000L != 0L) ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL
                      else (crc shl 1) and 0xFFFFFFFFL
            }
        }
        return crc and 0xFFFFFFFFL
    }
}
