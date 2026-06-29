package com.g7monitor.shared.platform

/** Einfacher String-Key-Value-Speicher (plattformspezifisch).
 *  iOS: Datei im Documents-Ordner. Android: vorerst In-Memory
 *  (Android-:app nutzt :shared noch nicht — eigene Persistenz dort). */
expect fun persistRead(key: String): String?
expect fun persistWrite(key: String, value: String)
