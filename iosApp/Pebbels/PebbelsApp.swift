import SwiftUI
import UIKit
import UserNotifications
import UniformTypeIdentifiers
import Shared

@main
struct PebbelsApp: App {
    init() { Bridge.shared.wire() }
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

// Verbindet die geteilte Compose-UI (AppState) mit den iOS-Implementierungen.
final class Bridge {
    static let shared = Bridge()
    let g7 = G7Ble()
    let aidex = AidexXBle()
    let notifDelegate = NotifDelegate()
    let importDelegate = ImportDelegate()
    func wire() {
        UNUserNotificationCenter.current().delegate = notifDelegate
        Persistence.shared.load()   // Einstellungen + Verlauf bei jedem Start laden (auch Hintergrund-Relaunch)
        // Bestehenden Session-Schlüssel aus dem Dokumente-Ordner einmalig in den
        // Keychain übernehmen (überlebt künftig das Neuinstallieren).
        if KeychainStore.loadBlob() == nil, let b = SessionStore.shared.loadBlob() {
            KeychainStore.saveBlob(b.toData())
            if let p = SessionStore.shared.loadPin() { KeychainStore.savePin(p.toData()) }
        }
        if AppState.shared.cloudUuid.isEmpty { AppState.shared.cloudUuid = UUID().uuidString }
        AppState.shared.cloudUuid = AppState.shared.cloudUuid.lowercased()   // Server-Regex verlangt Kleinbuchstaben
        AppState.shared.onConnect = { [weak self] in
            guard let self = self else { return }
            if AppState.shared.sensorType == "aidex" {
                self.aidex.start(serial: AppState.shared.aidexSerial)
            } else {
                self.g7.start(pin: Array(AppState.shared.pin.utf8))
            }
        }
        AppState.shared.onDisconnect = { [weak self] in self?.g7.stop(); self?.aidex.stop() }
        AppState.shared.onHardReset = { [weak self] in
            if AppState.shared.sensorType == "aidex" { self?.aidex.forgetSensor() } else { self?.g7.forgetSensor() }
        }
        AppState.shared.onClearHistory = { AppState.shared.clearHistory(); AppState.shared.backfillCount = 0; Persistence.shared.saveHistory() }
        AppState.shared.onAlarmTest = { Alarms.shared.test() }
        AppState.shared.onSensorTypeChange = { type in AppState.shared.sensorType = type }
        AppState.shared.onCalibrate = { target in
            // Offset so setzen, dass der aktuelle Wert = target wird, und speichern.
            guard let last = AppState.shared.lastGlucose?.intValue, last > 0 else { return }
            AppState.shared.calibOffset = target.int32Value - Int32(last) + AppState.shared.calibOffset
            Persistence.shared.saveSettings()
        }
        AppState.shared.onCalibrateReset = {
            AppState.shared.calibOffset = 0
            Persistence.shared.saveSettings()
        }
        AppState.shared.onScanSerial = {
            guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                  let root = scene.windows.first?.rootViewController else { return }
            AidexScanner.present(from: root) { sn in AppState.shared.aidexSerial = sn }
        }
        AppState.shared.onOpenUrl = { url in
            if let u = URL(string: url) { UIApplication.shared.open(u) }
        }
        AppState.shared.onShareUrl = { url in
            guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                  let root = scene.windows.first?.rootViewController else { return }
            let av = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            root.present(av, animated: true)
        }
        AppState.shared.onExport = {
            let json = AppState.shared.exportJson()
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("pebbels_verlauf.json")
            guard (try? json.write(to: url, atomically: true, encoding: .utf8)) != nil,
                  let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                  let root = scene.windows.first?.rootViewController else { return }
            let av = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            root.present(av, animated: true)
        }
        AppState.shared.onImport = { [weak self] in
            guard let self = self,
                  let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                  let root = scene.windows.first?.rootViewController else { return }
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.json, .plainText])
            picker.delegate = self.importDelegate
            root.present(picker, animated: true)
        }
        g7.autoStartIfPaired()
    }
}

// Zeigt lokale Benachrichtigungen auch an, wenn die App im Vordergrund läuft.
final class NotifDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .list])
    }
}

final class ImportDelegate: NSObject, UIDocumentPickerDelegate {
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), let s = String(data: data, encoding: .utf8) else { return }
        let n = AppState.shared.importJson(s: s)
        let m = AppState.shared.importMeds(s: s)
        AppState.shared.pushLog(s: "Import: \(n) Werte, \(m) Ereignisse")
        Persistence.shared.saveHistory()
    }
}
