// Top-level build file. Shared config for all modules.

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
    // KMP-Migration (iOS + Android). Nur 'apply false' — ändert :app NICHT,
    // wird ausschließlich vom neuen :shared-Modul angewandt.
    id("org.jetbrains.kotlin.multiplatform") version "2.0.0" apply false
    id("com.android.library") version "8.5.2" apply false
    // Compose Multiplatform — teilt die ECHTE Compose-UI mit iOS (1:1).
    id("org.jetbrains.compose") version "1.6.11" apply false
}
