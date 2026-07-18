/*
 * AiDEX X / LinX — Protokoll-Kern (Session, Kommandos, Decodierung).
 *
 * Port aus Jugglucos aidexx/java.cpp + glucose.h (GPL-3.0, © 2021 Jaap Korthals
 * Altes) nach reinem Kotlin — kein C++/JNI. Deshalb ebenfalls GPL-3.0.
 *
 * Diese Klasse kennt KEIN Bluetooth. Der AidexXBleClient füttert sie mit den
 * empfangenen Bytes und schreibt die zurückgegebenen (verschlüsselten) Kommandos
 * an Characteristic f002.
 *
 * Ablauf der Kopplung:
 *   1) askKeyBytes()  -> an f001 schreiben
 *   2) f001-Notify (16 B Masterkey) -> onMasterKey()
 *   3) bonden (macht der BleClient)
 *   4) f002-Read (17 B) -> onSessionKeyRead() liefert den ersten Startbefehl
 *   5) f002-Notify -> onDataNotify() (Handshake: DeviceInfo, Startzeit, Verlauf)
 *   6) f003-Notify -> onCurrentNotify() (aktuelle Werte)
 */
package com.g7monitor.shared.aidex

import com.g7monitor.shared.platform.currentTimeMillis

/** Ein decodierter Messwert. isCurrent=true → Live-Wert (Alarm/Anzeige). */
data class AidexReading(
    val timestampMs: Long,
    val mgdl: Int,
    val rateMgdlPerMin: Float,
    val isCurrent: Boolean,
)

class AidexXSession(private val serial: String) {

    private val iv = AidexXCrypto.makeIv(serial)
    private val askKey = AidexXCrypto.makeAskKey(serial)   // keys[0]-Ersatz: entschlüsselt den Masterkey
    private var masterKey: ByteArray? = null               // vom Sensor (f001)
    private var sessionKey: ByteArray? = null              // aus f002 abgeleitet

    var startTimeSec: Long = 0L; private set
    var hasTime: Boolean = false; private set
    private var time0 = 0
    private var unbonded = false
    private var lastLifeCountReceived = 0   // letzter empfangener Verlaufs-Index
    private var lastHistoricPos = 0         // höchster auf dem Sensor verfügbarer Index
    private var historyReqs = 0             // Zähler gegen Endlosschleife beim Nachladen
    private val warmupMin = 30              // AiDEX: erste 30 Min = Aufwärmphase → verwerfen

    /** Backfill-Werte (Verlauf) werden hierüber gemeldet. */
    var onBackfill: (AidexReading) -> Unit = {}
    /** Aktueller Wert (große Anzeige) — LastPast (0x111) + Live (f003). */
    var onCurrent: (AidexReading) -> Unit = {}
    /** Neuester bereits gespeicherter Zeitstempel (ms) — für Verlauf-Wiederaufsetzen. */
    var newestStoredMs: () -> Long = { 0L }
    /** Sensor-Startzeit (ms) — für den Ablauf-Hinweis (AiDEX: 14 Tage). */
    var onStartTime: (Long) -> Unit = {}
    /** Log-Kanal (vom BleClient auf DebugLog gesetzt). */
    var log: (String) -> Unit = {}
    /** „Sensor freigeben": statt Start das Entkopplungs-Kommando (0xf2) senden. */
    var unpair = false
    private var unpairTries = 0
    var onUnpairResult: (Boolean) -> Unit = {}

    fun hasKey(): Boolean = masterKey != null

    // ---- 1) askKey an f001 ----
    fun askKeyBytes(): ByteArray = askKey

    /** Entkopplungs-Kommando (0xf2 ad 2e), verschlüsselt — an f002 schreiben, wenn schon verbunden. */
    fun unpairCommand(): ByteArray? = enc(byteArrayOf(0xf2.toByte(), 0xad.toByte(), 0x2e))

    // ---- 2) Masterkey vom Sensor (f001-Notify, 16 B) ----
    fun onMasterKey(value: ByteArray): Boolean {
        if (value.size != 16) return false
        masterKey = value.copyOf(16)
        return true
    }

    // ---- 4) Session-Key ableiten (f002-Read, 17 B) -> erster Startbefehl ----
    fun onSessionKeyRead(haveKey: Boolean, value: ByteArray): ByteArray? {
        val mk = masterKey ?: return null
        if (value.size != 17) return null
        unbonded = !haveKey
        val dec = AidexXCrypto.decrypt(value, mk, iv)          // 16 B Key + 1 B crc8
        if (dec.size < 17) return null
        if (AidexXCrypto.crc8Maxim(dec, 16) != (dec[16].toInt() and 0xFF)) return null
        sessionKey = dec.copyOf(16)
        val late = haveKey && hasTime
        val cmd = when {
            unpair -> byteArrayOf(0xf2.toByte(), 0xad.toByte(), 0x2e)   // Sensor freigeben (Entkopplung)
            late   -> byteArrayOf(0x35, 0x01, 0x4e, 0xf7.toByte())
            else   -> byteArrayOf(0x10, 0xc1.toByte(), 0xf3.toByte())
        }
        return enc(cmd)
    }

    // ---- 5) f002-Notify: Handshake + Verlauf ----
    /** @return nächstes (verschlüsseltes) Kommando für f002, ByteArray(0) = nichts
     *  senden, null = Verbindung trennen. */
    fun onDataNotify(value: ByteArray): ByteArray? {
        val d = dec(value) ?: run { log("f002 dec=null"); return null }
        if (d.size < 2) { log("f002 size<2"); return null }
        val crcOk = AidexXCrypto.crc16CcittFalse(d, d.size - 2) == le16(d, d.size - 2)
        val type = le16(d, 0)
        log("f002 type=0x${type.toString(16)} crcOk=$crcOk len=${d.size}")
        if (!crcOk) return null
        val body = d.copyOfRange(2, d.size - 2)               // ohne Typ + crc16
        val next = when (type) {
            0x110 -> deviceInfo()
            0x120, 0x020 -> { time0 = 2; enc(byteArrayOf(0x21, 0xb3.toByte(), 0xd5.toByte())) }
            0x121 -> receiveStartTime(body)
            0x122 -> lastValue(d)
            0x123 -> pastValues(body)
            0x111 -> { lastGlucose(body); whenPresent() }
            0x131 -> writeTime()
            0x134 -> enc(byteArrayOf(0x11, 0xe0.toByte(), 0xe3.toByte()))
            0x135 -> enc(byteArrayOf(0x34, 0x01, 0x7f, 0xc4.toByte()))
            0x1f2 -> { log("Entkopplung erfolgreich"); onUnpairResult(true); null }
            0x0f2 -> if (unpairTries++ < 4) enc(byteArrayOf(0xf2.toByte(), 0xad.toByte(), 0x2e))
                     else { log("Entkopplung fehlgeschlagen"); onUnpairResult(false); null }
            else -> { log("f002 Typ 0x${type.toString(16)} unbekannt"); ByteArray(0) }
        }
        if (next == null) log("f002 Handler(0x${type.toString(16)}) -> null")
        return next
    }

    // ---- 6) f003-Notify: aktueller Wert ----
    fun onCurrentNotify(value: ByteArray): AidexReading? {
        val d = dec(value) ?: return null
        if (d.size < 17) return null                          // 15 B CurrentGlucose + crc16
        if (AidexXCrypto.crc16CcittFalse(d, d.size - 2) != le16(d, d.size - 2)) return null
        val t = d[0].toInt() and 0xFF                         // 1 = Wert, 3 = Sensor beendet
        if (t != 1 && t != 3) return null
        return currentGlucose(d)
    }

    // ---------- Protokoll-Details ----------

    private fun deviceInfo(): ByteArray? = when (time0) {
        1 -> enc(byteArrayOf(0x31, 0x01, 0x8a.toByte(), 0x3b))
        0 -> if (hasTime && !unbonded) enc(byteArrayOf(0x35, 0x01, 0x4e, 0xf7.toByte()))
             else enc(byteArrayOf(0x21, 0xb3.toByte(), 0xd5.toByte()))
        else -> enc(byteArrayOf(0x21, 0xb3.toByte(), 0xd5.toByte()))
    }

    private fun receiveStartTime(body: ByteArray): ByteArray? {
        if (body.size < 9) { log("startTime body<9"); return null }
        val lst = LocalStartTime.parse(body)
        val newStart = lst.unixtime()
        log("startTime y=${lst.year} ${lst.month}-${lst.day} ${lst.hour}:${lst.min} -> unix=$newStart")
        if (newStart == 0L) { time0 = 1; return deviceInfo() }   // Zeit setzen, NICHT trennen
        time0 = 0
        val changed = startTimeSec != newStart
        startTimeSec = newStart
        hasTime = true
        onStartTime(newStart * 1000L)
        // Wiederaufsetzen: bereits gespeicherte Werte nicht erneut holen — nur die Lücke.
        val storedMs = newestStoredMs()
        if (storedMs > 0L) {
            val idx = ((storedMs / 1000L - startTimeSec) / 60L).toInt()
            if (idx > lastLifeCountReceived) { lastLifeCountReceived = idx; log("Resume ab Verlaufs-Index $idx") }
        }
        return if (changed) enc(byteArrayOf(0x35, 0x01, 0x4e, 0xf7.toByte()))
               else askLastId()
    }

    /** 0x111: letzter Wert (LastPast) — 8 B. */
    private fun lastGlucose(body: ByteArray) {
        if (body.size < 8) return
        val minFromStart = le16(body, 0)
        val g16 = le16(body, 5)
        val glucose = g16 and 0x3FF
        val valid = (g16 ushr 15) and 1
        val trend = body[4].toInt()                           // int8 (Kotlin-Byte ist signed)
        if (minFromStart > lastHistoricPos) lastHistoricPos = minFromStart   // Verlaufs-Obergrenze
        if (valid == 1 && glucose in 18..800 && hasTime && minFromStart >= warmupMin) {
            log("LastPast Wert=$glucose mg/dL min=$minFromStart trend=$trend")
            onCurrent(reading(minFromStart, glucose, trend, current = true))
        } else {
            log("LastPast ungültig (valid=$valid glucose=$glucose hasTime=$hasTime)")
        }
    }

    /** 0x123: Verlaufs-Array (je 2 B HistoryGlucose ab startID). */
    private fun pastValues(body: ByteArray): ByteArray? {
        if (body.size < 4) return ByteArray(0)
        val startId = le16(body, 0)
        val n = (body.size - 2) / 2
        var curId = startId
        var saved = 0
        for (i in 0 until n) {
            val g16 = le16(body, 2 + i * 2)
            val glucose = g16 and 0x3FF
            val warmup = (g16 ushr 10) and 1
            val valid = (g16 ushr 15) and 1
            if (i == 0) log("Verlauf[0] id=$curId g16=0x${g16.toString(16)} glu=$glucose warmup=$warmup valid=$valid")
            if (valid == 1 && glucose in 18..800 && hasTime && curId >= warmupMin) {
                onBackfill(reading(curId, glucose, 0, current = false))
                saved++
            }
            curId++
        }
        if (curId - 1 <= lastLifeCountReceived) { log("Verlauf stagniert — stoppe"); return ByteArray(0) }
        lastLifeCountReceived = curId - 1
        log("Verlauf: +$saved ab $startId, bei $lastLifeCountReceived/$lastHistoricPos")
        return if (curId < lastHistoricPos) askHistory(curId) else ByteArray(0)
    }

    /** 0x122: „lastValue" — Sensor meldet den höchsten verfügbaren Verlaufs-Index. */
    private fun lastValue(d: ByteArray): ByteArray? {
        if (d.size < 8) return ByteArray(0)
        val mid = le32(d, 2)
        if (mid != 0x10001L) { log("lastValue mid=0x${mid.toString(16)}"); return ByteArray(0) }
        lastHistoricPos = le16(d, d.size - 4)
        log("lastValue lastOnSensor=$lastHistoricPos")
        return whenPresent()
    }

    /** Lädt den Verlauf in Häppchen: solange noch Werte fehlen (lastLifeCountReceived <
     *  lastHistoricPos), das nächste 0x23-Kommando schicken; sonst Ruhe. */
    private fun whenPresent(): ByteArray? {
        val nextId = lastLifeCountReceived + 1
        if (nextId < lastHistoricPos) {
            if (historyReqs++ > 400) { log("Verlauf-Limit erreicht — stoppe"); return ByteArray(0) }
            log("Verlauf laden ab $nextId (Ziel $lastHistoricPos)")
            return askHistory(nextId)
        }
        log("Verlauf komplett bis $lastLifeCountReceived")
        return ByteArray(0)
    }

    /** 0x23 + Start-Index (2 B LE) + crc16 — fordert einen Verlaufs-Block an.
     *  starthistory ist bei AiDEX 0, daher relID = Start-Index. */
    private fun askHistory(startId: Int): ByteArray? {
        val relId = if (startId < 1) 1 else startId
        val cmd = byteArrayOf(0x23, (relId and 0xFF).toByte(), ((relId ushr 8) and 0xFF).toByte())
        return enc(AidexXCrypto.withCrc16(cmd))
    }

    private fun currentGlucose(d: ByteArray): AidexReading? {
        val minFromStart = le16(d, 4)
        val g16 = le16(d, 6)
        val glucose = g16 and 0x3FF
        val valid = (g16 ushr 15) and 1
        val trend = d[3].toInt()                              // int8
        if (valid != 1 || glucose !in 18..800 || !hasTime || minFromStart < warmupMin) return null
        return reading(minFromStart, glucose, trend, current = true)
    }

    private fun reading(minFromStart: Int, mgdl: Int, trend: Int, current: Boolean): AidexReading {
        val ts = (startTimeSec + minFromStart * 60L) * 1000L
        return AidexReading(ts, mgdl, AidexXProtocol.rateFromTrend(trend), current)
    }

    // Kommandos
    private fun askLastId(): ByteArray? = enc(byteArrayOf(0x22, 0xd0.toByte(), 0xe5.toByte()))
    private fun writeTime(): ByteArray? {
        val cmd = ByteArray(1 + 9)
        cmd[0] = 0x20
        LocalStartTime.now().writeInto(cmd, 1)
        return enc(AidexXCrypto.withCrc16(cmd))
    }

    // Krypto-Helfer
    private fun enc(cmd: ByteArray): ByteArray? {
        val sk = sessionKey ?: return null
        return AidexXCrypto.encrypt(cmd, sk, iv)
    }
    private fun dec(value: ByteArray): ByteArray? {
        val sk = sessionKey ?: return null
        return AidexXCrypto.decrypt(value, sk, iv)
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)
}

/** LocalStartTime (packed, 9 Bytes) — Sensor-Startzeit inkl. Zeitzone/DST. */
private class LocalStartTime(
    val year: Int, val month: Int, val day: Int,
    val hour: Int, val min: Int, val sec: Int,
    val tzQuarter: Int, val dstQuarter: Int,
) {
    fun unixtime(): Long {
        if (year < 2010) return 0L
        val days = daysFromCivil(year, month, day)             // Tage seit 1970-01-01 (UTC)
        val utc = days * 86_400L + hour * 3600L + min * 60L + sec
        val offsetMin = tzQuarter * 15 + dstQuarter * 15
        return utc - offsetMin * 60L
    }
    fun writeInto(buf: ByteArray, off: Int) {
        buf[off] = (year and 0xFF).toByte(); buf[off + 1] = ((year ushr 8) and 0xFF).toByte()
        buf[off + 2] = month.toByte(); buf[off + 3] = day.toByte()
        buf[off + 4] = hour.toByte(); buf[off + 5] = min.toByte(); buf[off + 6] = sec.toByte()
        buf[off + 7] = tzQuarter.toByte(); buf[off + 8] = dstQuarter.toByte()
    }
    companion object {
        /** Tage seit 1970-01-01 (proleptischer Gregorianischer Kalender, Howard Hinnant). */
        private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era.toLong() * 146097L + doe - 719468L
        }
        fun parse(b: ByteArray): LocalStartTime {
            val year = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
            return LocalStartTime(
                year, b[2].toInt() and 0xFF, b[3].toInt() and 0xFF,
                b[4].toInt() and 0xFF, b[5].toInt() and 0xFF, b[6].toInt() and 0xFF,
                b[7].toInt(), b[8].toInt() and 0xFF,
            )
        }
        /** Aktuelle Zeit in UTC-Feldern (tz/dst=0). Nur für das seltene „Zeit setzen" (0x20). */
        fun now(): LocalStartTime {
            var t = currentTimeMillis() / 1000L
            val sec = (t % 60L).toInt(); t /= 60L
            val min = (t % 60L).toInt(); t /= 60L
            val hour = (t % 24L).toInt(); t /= 24L
            val z = t + 719468L
            val era = (if (z >= 0) z else z - 146096L) / 146097L
            val doe = z - era * 146097L
            val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
            val y = yoe + era * 400L
            val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
            val mp = (5L * doy + 2L) / 153L
            val d = (doy - (153L * mp + 2L) / 5L + 1L).toInt()
            val m = (if (mp < 10L) mp + 3L else mp - 9L).toInt()
            val year = (if (m <= 2) y + 1L else y).toInt()
            return LocalStartTime(year, m, d, hour, min, sec, 0, 0)
        }
    }
}
