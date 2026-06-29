plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.g7monitor"
    compileSdk = 35
    ndkVersion = "27.0.12077973"   // 16-KB-Seiten: NDK r27+ richtet alle .so automatisch aus

    defaultConfig {
        applicationId = "app.pebbels"
        minSdk = 26          // Android 8.0; G7 requires BLE 4.2+
        targetSdk = 35
        versionCode = 5
        versionName = "1.1"

        // G7 target devices are 64-bit — don't bother building arm-v7 or x86.
        ndk { abiFilters += "arm64-v8a" }

        // Kein nativer Build mehr: Dexcom läuft komplett über reinen Kotlin-Code
        // (DexSession/DexJPake/DexCrypto + BouncyCastle). libg7.so wird nicht mehr
        // gebaut/eingebunden → die App ist fremdcode-frei.
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }
}

dependencies {
    // --- Core AndroidX / Compose ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Material-Icons für die Bottom-Nav (Werte/Statistik/Einstellungen).
    // `extended` statt `core`, weil ShowChart/BarChart/Settings nicht alle in
    // core drin sind und das Aufteilen kaum APK-Größe spart (R8 stripped
    // unbenutzte Icons im Release).
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // --- Lifecycle / ViewModel ---
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // --- QR-Code-Erzeugung (Cloud-Upload-Dashboard-Link) ---
    // Nur der reine Encoder-Kern von ZXing — pure Java, kein Android-Teil,
    // ~500 KB. Android Studio lädt das beim Bauen automatisch von Maven
    // Central. Wir erzeugen damit die BitMatrix und zeichnen sie selbst in
    // einem Compose-Canvas (siehe ui/QrCode.kt).
    implementation("com.google.zxing:core:3.5.3")

    // --- OCR: aufgedruckte 4-stellige Dexcom-PIN per Kamera lesen ---
    // Die Dexcom-PIN steckt NICHT im Barcode (der enthält nur die Seriennummer),
    // sie ist nur aufgedruckt. ML-Kit-Texterkennung liest die große Zahl direkt.
    // On-device, offline, kostenlos (Apache/Google).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // CameraX 1.4.x liefert 16-KB-ausgerichtete .so (libimage_processing_util_jni.so)
    // — nötig für Android 15+ / 16-KB-Seiten (Play-Store-Pflicht ab 01.11.2025).
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // --- Dexcom G7 in reinem Kotlin (ersetzt libg7.so) ---
    // BouncyCastle liefert EC-JPAKE (Handshake) + AES/CCM (Paket-Krypto) als
    // reines Java/Kotlin — damit brauchen wir keine native .so mehr.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Geteilte UI + Logik (KMP) — dieselbe PebbelsApp wie auf iOS.
    implementation(project(":shared"))
}
