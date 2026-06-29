package com.g7monitor.shared.platform

import java.io.File

/** Von der Android-App beim Start gesetzt (filesDir.path), damit der geteilte
 *  Code echte Persistenz bekommt (z. B. Medikamente). Ohne gesetzten Pfad:
 *  In-Memory-Fallback (z. B. in Tests). Bestehende Glukose-/Settings-Daten
 *  laufen weiter über die eigene Engine der App – hier kollidiert nichts. */
var androidPersistDir: String? = null

private val mem = mutableMapOf<String, String>()

actual fun persistRead(key: String): String? {
    val dir = androidPersistDir ?: return mem[key]
    return runCatching {
        File(dir, "pebbels_$key.txt").let { if (it.exists()) it.readText() else null }
    }.getOrNull()
}

actual fun persistWrite(key: String, value: String) {
    val dir = androidPersistDir
    if (dir == null) { mem[key] = value; return }
    runCatching { File(dir, "pebbels_$key.txt").writeText(value) }
}
