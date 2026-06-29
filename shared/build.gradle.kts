/*
 * Pebbels Shared Core — Kotlin Multiplatform (Android + iOS).
 * Enthält den plattformunabhängigen Kern (Krypto, Protokoll, Paket-Decode).
 * Bewusst MINIMAL gehalten und ADDITIV: ändert nichts am :app-Modul.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions { jvmTarget = "17" }
        }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
        }
    }

    sourceSets {
        commonMain.dependencies {
            // KMP-Großzahlen (Apache-2.0) — ersetzt java.math.BigInteger für iOS.
            implementation("com.ionspin.kotlin:bignum:0.3.10")
            // QR-Code-Generator, reines Kotlin Multiplatform (Android + iOS).
            implementation("io.github.g0dkar:qrcode-kotlin:4.1.1")
            // Compose Multiplatform — dieselbe UI für Android + iOS.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
        }
    }
}

android {
    namespace = "com.g7monitor.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Geteilte Compose-Resources (z. B. das Pebbels-Foto) — für Android + iOS.
compose.resources {
    publicResClass = true
    packageOfResClass = "com.g7monitor.shared.resources"
}
