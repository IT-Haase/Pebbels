package com.g7monitor.shared.ui

import com.g7monitor.shared.platform.persistRead
import com.g7monitor.shared.platform.persistWrite

/** Persistiert die aktive G7-Session (sharedKey + Sensor-Start + Name) als Hex.
 *  Beim Reconnect wird der Blob wiederhergestellt → der JPAKE-Handshake entfällt,
 *  und der „Zombie-Bond"-Loop (gebondeter Sensor lehnt frischen Handshake ab)
 *  tritt nicht mehr auf. Pendant zu Androids SensorStore. */
object SessionStore {
    fun save(blob: ByteArray, pin: ByteArray) {
        persistWrite("session_blob", blob.toHex())
        persistWrite("session_pin", pin.toHex())
    }
    fun loadBlob(): ByteArray? = persistRead("session_blob")?.takeIf { it.length >= 4 }?.fromHex()
    fun loadPin(): ByteArray? = persistRead("session_pin")?.takeIf { it.length == 8 }?.fromHex()
    fun clear() { persistWrite("session_blob", ""); persistWrite("session_pin", "") }

    private fun ByteArray.toHex(): String =
        joinToString("") { ((it.toInt() and 0xFF) or 0x100).toString(16).substring(1) }
    private fun String.fromHex(): ByteArray =
        ByteArray(length / 2) { ((this[it * 2].digitToInt(16) shl 4) or this[it * 2 + 1].digitToInt(16)).toByte() }
}
