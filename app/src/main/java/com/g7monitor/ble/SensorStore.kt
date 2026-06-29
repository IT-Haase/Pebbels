/*
 * Persistenz für die aktive G7-Session.
 *
 * Speichert den von `DexNative.sessionBlob(dataptr)` gelieferten Binär-Blob
 * im privaten Datenverzeichnis der App (files/g7_session.bin). Beim nächsten
 * Start lädt start() diesen Blob VOR dem Scan zurück in die native Session —
 * damit kennt der native Code den sharedKey + DexDeviceName sofort und der
 * Handshake wird automatisch übersprungen (DexNative.isAuthenticated() = true).
 *
 * Zusätzlich liegt in derselben Datei der 4-byte Applicator-PIN, damit der User
 * nach einem App-Neustart nicht erneut nach dem PIN gefragt werden muss.
 *
 * Datei-Layout:
 *   Magic 'G7SP'      (4 B)
 *   Version           (1 B, = 1)
 *   Reserved          (3 B, 0)
 *   PIN               (4 B)
 *   Blob-Länge        (2 B, LE)
 *   Session-Blob      (N B, normalerweise 48)
 */
package com.g7monitor.ble

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SensorStore(private val ctx: Context) {
    private val tag = "SensorStore"
    private val file: File get() = File(ctx.filesDir, "g7_session.bin")
    private val tmp:  File get() = File(ctx.filesDir, "g7_session.tmp")

    data class Snapshot(val pin: ByteArray, val blob: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Snapshot) return false
            return pin.contentEquals(other.pin) && blob.contentEquals(other.blob)
        }
        override fun hashCode(): Int = pin.contentHashCode() * 31 + blob.contentHashCode()
    }

    fun exists(): Boolean = file.exists() && file.length() >= HEADER_SIZE

    /** Persistenten Snapshot laden. Null wenn nichts da ist oder das Format nicht stimmt. */
    fun load(): Snapshot? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size < HEADER_SIZE) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val m0 = bb.get(); val m1 = bb.get(); val m2 = bb.get(); val m3 = bb.get()
            if (m0 != 'G'.code.toByte() || m1 != '7'.code.toByte() ||
                m2 != 'S'.code.toByte() || m3 != 'P'.code.toByte()) {
                Log.w(tag, "Magic nicht ok — ignoriere Datei")
                return null
            }
            val version = bb.get().toInt()
            if (version != 1) { Log.w(tag, "Version $version unbekannt"); return null }
            bb.get(); bb.get(); bb.get()                 // reserved
            val pin = ByteArray(4); bb.get(pin)
            val blobLen = bb.short.toInt() and 0xFFFF
            // blobLen == 0 ist legitim: "PIN bekannt, aber keine aktive
            // Session" (z. B. direkt nach einem Auto-Heil). Der Aufrufer
            // prüft dann selbst über blob.size, ob sessionRestore lohnt.
            if (blobLen < 0 || blobLen > 1024) { Log.w(tag, "Blob-Länge $blobLen suspekt"); return null }
            if (bytes.size < HEADER_SIZE + blobLen) { Log.w(tag, "Datei zu kurz"); return null }
            val blob = if (blobLen == 0) ByteArray(0)
                       else bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + blobLen)
            Log.i(tag, "Snapshot geladen (Blob=${blob.size} B)")
            Snapshot(pin, blob)
        } catch (t: Throwable) {
            Log.w(tag, "load() fehlgeschlagen", t); null
        }
    }

    /** Atomar speichern (tmp → rename). */
    fun save(pin: ByteArray, blob: ByteArray) {
        require(pin.size == 4) { "PIN muss 4 Byte haben" }
        try {
            val out = ByteArray(HEADER_SIZE + blob.size)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.put('G'.code.toByte()); bb.put('7'.code.toByte())
            bb.put('S'.code.toByte()); bb.put('P'.code.toByte())
            bb.put(1)                                     // version
            bb.put(0); bb.put(0); bb.put(0)               // reserved
            bb.put(pin)
            bb.putShort(blob.size.toShort())
            bb.put(blob)
            tmp.writeBytes(out)
            if (!tmp.renameTo(file)) {
                // Fallback: manuell überschreiben
                file.writeBytes(out)
                tmp.delete()
            }
            Log.i(tag, "Snapshot gespeichert (${out.size} B) → ${file.absolutePath}")
        } catch (t: IOException) {
            Log.w(tag, "save() fehlgeschlagen", t)
        }
    }

    fun clear() {
        try { file.delete() } catch (_: Throwable) {}
        try { tmp.delete() }  catch (_: Throwable) {}
    }

    /** Nur den Session-Blob verwerfen, PIN behalten. Aufrufer ist typischerweise
     *  der Auto-Heil-Pfad nach einem Zombie-Bond: der sharedKey ist obsolet,
     *  aber der Applicator-PIN stimmt noch — der User soll ihn nicht erneut
     *  eingeben müssen, weil das Pairing danach automatisch neu läuft. */
    fun clearSessionKeepPin() {
        val snap = load()
        if (snap == null) { clear(); return }
        // Snapshot mit leerem Blob zurückschreiben — PIN bleibt, sessionRestore
        // wird beim nächsten Start fehlschlagen (Blob zu klein) und der normale
        // Pairing-Flow greift.
        save(snap.pin, ByteArray(0))
    }

    companion object {
        private const val HEADER_SIZE = 4 + 1 + 3 + 4 + 2   // = 14
    }
}
