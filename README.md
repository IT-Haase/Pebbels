# Pebbels 🐾

**Deutsch** · [English](README.en.md)

**Ein Sensor-Monitor für Haustiere.** Die App liest die Werte eines **Dexcom G7 / Dexcom ONE+** oder **AiDEX X / LinX** Sensors per Bluetooth aus und zeigt sie übersichtlich an — aktueller Wert, Verlauf, Trend, Statistik und ein Ereignis-Logbuch. Eine gemeinsame Codebasis (Kotlin Multiplatform + Compose Multiplatform) für **iOS und Android**.

Entstanden aus der Fürsorge für eine kleine französische Bulldogge namens **Pebbels**.

---

## 🐾 In Erinnerung an Pebbels

Diese App trägt den Namen meiner kleinen Maus. Sie war an einem Insulinom erkrankt, und der Wunsch, ihre Werte im Blick zu behalten, war der Antrieb für dieses Projekt. Sie soll weiterleben — in dieser App und in jeder Version, die daraus wächst.

**Eine Bitte von Herzen:** Wenn du auf diesem Projekt aufbaust, würde es mir sehr viel bedeuten, wenn du irgendwo einen kleinen Link zu ihrer Seite lässt:

👉 **https://sa1.de/pebbels-website**

Das ist kein Zwang, nur eine herzliche Bitte. Es soll ihr Vermächtnis sein.

---

## Laden

[![App Store](https://img.shields.io/badge/App_Store-Pebbels-0D96F6?logo=apple&logoColor=white)](https://apps.apple.com/de/app/pebbels/id6780933064) [![Google Play](https://img.shields.io/badge/Google_Play-Pebbels-414141?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=app.pebbels)

iPhone → App Store · Android → Google Play

---

## Was die App macht

- Liest **Dexcom G7 / Dexcom ONE+** und **AiDEX X / LinX** per Bluetooth
- Sensor-Umschalter in den Einstellungen · AiDEX-Seriennummer per **QR-Scan** oder Eingabe
- AiDEX: Werte im Minutentakt, Nachtrag des Sensor-Verlaufs, 14 Tage Laufzeit, Sensor-Freigabe für den Gerätewechsel
- Aktueller Wert mit Trendpfeil, Verlauf (6 Stunden bis 3 Wochen)
- Statistik (Zeit im gewählten Bereich, Mittelwert)
- **Ereignis-Logbuch** mit Zeitstempel (z. B. Fütterung)
- Hinweis, wenn der Sensor bald abläuft
- Heller/dunkler Modus · Deutsch · English · Español
- Export und Import der Daten

## Technik

- **Kotlin Multiplatform** + **Compose Multiplatform** — eine geteilte UI für beide Plattformen (eine Änderung im geteilten Code → beide Apps)
- Android: eigene BLE-Engine · iOS: CoreBluetooth-Bridge zur geteilten Logik
- Architektonisch auf **mehrere Sensoren** vorbereitet

## Bauen

**Voraussetzungen:** JDK 17, aktuelles Android Studio · für iOS zusätzlich Xcode (macOS)

**Android:**
```
./gradlew :app:assembleDebug
```
Die APK liegt danach unter `app/build/outputs/apk/debug/`.

**iOS:**
`iosApp/Pebbels.xcodeproj` in Xcode öffnen und bauen — das Shared-Framework wird per Gradle-Build-Phase automatisch erzeugt.

> Tipp: Der iOS-**Release**-Build (Kotlin/Native-Link) ist speicherhungrig und langsam. Bei Hängern `org.gradle.jvmargs` in `gradle.properties` erhöhen und genug RAM freihalten.

## Geplant / In Arbeit

- ✅ **AiDEX X / LinX:** seit Version 1.2 vollständig unterstützt — auf iOS und Android, inklusive QR-Scan der Seriennummer, Nachtrag des Sensor-Verlaufs und Sensor-Freigabe für den Gerätewechsel.
- Weitere Tier-/CGM-Sensoren, sobald sich die Protokolle erschließen lassen.

> Du suchst nach einer App, die einen **AiDEX**- oder **Dexcom**-Sensor an Hund oder Katze mitliest? Du bist hier richtig — beide werden unterstützt.

## Mitmachen

Pull Requests sind herzlich willkommen — bau die App aus, ergänze Sensoren, verbessere die Oberfläche. Beachte nur die Lizenz: Jede Weiterentwicklung muss ebenfalls **offen (GPL-3.0)** bleiben.

## Herkunft & Lizenz

Die Handshake- und Decode-Logik für **Dexcom G7** und für **AiDEX X** ist abgeleitet aus **Juggluco** (© Jaap Korthals Altes), das unter **GPL-3.0** steht. Deshalb steht auch Pebbels unter **GPL-3.0**:

- Der Quellcode bleibt offen
- Copyright- und Lizenzhinweise müssen erhalten bleiben
- Jede Weitergabe und Weiterentwicklung wieder unter **GPL-3.0(+)**

Voller Lizenztext: [LICENSE](LICENSE). Großer Dank an das Juggluco-Projekt — ohne das Pebbels nicht möglich gewesen wäre.

## ⚠️ Wichtiger Hinweis

Pebbels ist **kein Medizinprodukt** und dient ausschließlich der persönlichen Beobachtung des eigenen Tieres. Die App stellt keine Diagnose, gibt keine medizinischen Empfehlungen und trifft keine Behandlungsentscheidungen. Sie ersetzt nicht die Beratung durch eine Tierärztin / einen Tierarzt oder eine medizinische Fachperson.

„Dexcom" und „AiDEX" sind Marken der jeweiligen Inhaber. Dieses Projekt steht in keiner Verbindung zu Dexcom oder Microtech/AiDEX und wird von diesen nicht unterstützt.
