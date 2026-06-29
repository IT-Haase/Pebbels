# Pebbels 🐾

[Deutsch](README.md) · **English**

**A sensor monitor for pets.** Pebbels reads the values of a **Dexcom G7 / Dexcom ONE+** sensor over Bluetooth and shows them at a glance — current value, history, trend, statistics and an event log. One shared codebase (Kotlin Multiplatform + Compose Multiplatform) for **iOS and Android**.

Born out of caring for a little French bulldog named **Pebbels**.

---

## 🐾 In memory of Pebbels

This app carries the name of my little one. She had an insulinoma, and the wish to keep an eye on her readings was what drove this project. She should live on — in this app and in every version that grows from it.

**A heartfelt request:** If you build on this project, it would mean a great deal to me if you left a small link to her page somewhere:

👉 **https://sa1.de/pebbels-website**

It's not a requirement, just a warm request. Let it be her legacy.

---

## Download

[![App Store](https://img.shields.io/badge/App_Store-Pebbels-0D96F6?logo=apple&logoColor=white)](https://apps.apple.com/de/app/pebbels/id6780933064) [![Google Play](https://img.shields.io/badge/Google_Play-Pebbels-414141?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=app.pebbels)

iPhone → App Store · Android → Google Play

---

## What it does

- Reads **Dexcom G7 / Dexcom ONE+** over Bluetooth
- Current value with trend arrow, history (6 hours to 3 weeks)
- Statistics (time in the chosen range, average)
- **Event log** with timestamps (e.g. feeding)
- A hint when the sensor is about to expire
- Light / dark mode · German · English · Español
- Data export and import

## Tech

- **Kotlin Multiplatform** + **Compose Multiplatform** — one shared UI for both platforms (one change in shared code → both apps)
- Android: its own BLE engine · iOS: CoreBluetooth bridge to the shared logic
- Architected to support **multiple sensors**

## Build

**Requirements:** JDK 17, a recent Android Studio · for iOS additionally Xcode (macOS)

**Android:**
```
./gradlew :app:assembleDebug
```
The APK is then located under `app/build/outputs/apk/debug/`.

**iOS:**
Open `iosApp/Pebbels.xcodeproj` in Xcode and build — the shared framework is generated automatically by a Gradle build phase.

> Tip: The iOS **Release** build (Kotlin/Native link) is memory-hungry and slow. On hangs, raise `org.gradle.jvmargs` in `gradle.properties` and keep enough RAM free.

## Roadmap / In progress

- 🔬 **Aidex (AiDEX X):** support for the affordable **Aidex** CGM sensor is in progress — our own reverse engineering, our own code. Anyone who wants to help or follow along is very welcome!
- More pet / CGM sensors as their protocols become accessible.

> Looking for an app that reads an **Aidex** or **Dexcom** sensor on a dog or cat? You're in the right place — and Aidex support is on the way.

## Contributing

Pull requests are warmly welcome — extend the app, add sensors, improve the interface. Just mind the license: every derivative must stay **open (GPL-3.0)** as well.

## Origin & license

The Dexcom G7 handshake and decode logic is derived from **Juggluco** (© Jaap Korthals Altes), which is licensed under **GPL-3.0**. That is why Pebbels is also under **GPL-3.0**:

- The source stays open
- Copyright and license notices must be preserved
- Every redistribution and derivative again under **GPL-3.0(+)**

Full license text: [LICENSE](LICENSE). Huge thanks to the Juggluco project — without it, Pebbels would not have been possible.

## ⚠️ Important note

Pebbels is **not a medical device** and is solely for personally observing your own animal. The app makes no diagnosis, gives no medical recommendations and makes no treatment decisions. It does not replace advice from a veterinarian or a medical professional.

"Dexcom" is a trademark of its respective owners. This project is not affiliated with, nor endorsed by, Dexcom.
