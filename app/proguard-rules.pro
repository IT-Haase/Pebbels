# Keep all native methods and their classes — libg7.so resolves them by name.
-keepclasseswithmembernames class com.g7monitor.ble.DexNative {
    native <methods>;
}
-keep class com.g7monitor.ble.DexNative { *; }
-keep class com.g7monitor.ble.DexNative$* { *; }

# AiDEX: libblecomm-lib.so löst diese Klassen/Methoden/FELDER per Namen auf
# (FindClass/GetMethodID/GetFieldID) — NICHTS davon darf umbenannt werden.
-keep class com.microtechmd.blecomm.** { *; }

# Kotlin reflection used by Compose state holders
-keep class kotlin.Metadata { *; }
