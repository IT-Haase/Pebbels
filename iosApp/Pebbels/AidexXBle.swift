/*
 * AiDEX X / LinX — CoreBluetooth-Hülle für iOS.
 *
 * Die eigentliche Krypto/Protokoll-Logik liegt plattformweit in der geteilten
 * AidexXSession (commonMain) — 1:1 dieselbe wie auf Android. Diese Datei kümmert
 * sich nur um Bluetooth (Scan/Connect/Notify/Write) und schiebt die decodierten
 * Werte über AppState.pushLive / pushBackfill in den geteilten Speicher.
 *
 * Muster wie G7Ble.swift. Verwendet die Data<->KotlinByteArray-Helfer .kb()/.toData()
 * aus G7Ble.swift (falls die dort private sind: in eine gemeinsame Datei verschieben).
 */
import Foundation
import CoreBluetooth
import Shared

final class AidexXBle: NSObject {

    private let bleQueue = DispatchQueue(label: "pebbels.aidex", qos: .userInitiated)
    private func ui(_ block: @escaping () -> Void) { DispatchQueue.main.async(execute: block) }
    private func log(_ m: String) {
        #if DEBUG
        print("AiDEX: " + m)                                   // Xcode-Konsole, nur Debug-Build
        #endif
        ui { AppState.shared.pushLog(s: "AiDEX: " + m) }        // In-App Debug-Tab
    }

    private var status = "Bereit" {
        didSet { let s = status; ui { AppState.shared.status = s; AppState.shared.pushLog(s: s) } }
    }
    private var connected = false {
        didSet { let c = connected; ui { AppState.shared.connected = c } }
    }

    // BLE
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var wantScan = false
    private var handshakeStarted = false
    private var awaitingRead002 = false
    private var notifyReady = 0
    private var seenLog = 0
    private var pairTries = 0
    private var paired = false
    private var advLog = 0
    private var lastBroadcastG: Int32 = -1
    private var sortWork: DispatchWorkItem?
    private var foundName = ""

    // GATT: Service 0x181f, f001 (Masterkey), f002 (Session-Key + Kommandos/Verlauf), f003 (aktuelle Werte)
    private let svcUUID = CBUUID(string: "181F")
    private let uF001 = CBUUID(string: "F001")
    private let uF002 = CBUUID(string: "F002")
    private let uF003 = CBUUID(string: "F003")
    private var c001: CBCharacteristic?
    private var c002: CBCharacteristic?
    private var c003: CBCharacteristic?

    private var session: AidexXSession?
    private var serial = ""

    override init() {
        super.init()
        // Restore-ID: iOS startet die App nach Beendigung (z. B. nachts bei Speicherdruck)
        // für BLE-Events neu und ruft willRestoreState. Eigener Identifier, getrennt vom Dexcom.
        central = CBCentralManager(delegate: self, queue: bleQueue,
                                   options: [CBCentralManagerOptionRestoreIdentifierKey: "pebbels.aidex.central"])
    }

    // MARK: Public
    func start(serial: String) {
        let sn = serial.uppercased()
        guard sn.count >= 6 else { status = "Bitte Seriennummer (SN) eingeben"; return }
        self.serial = sn
        wantScan = true
        handshakeStarted = false
        AppState.shared.active = true
        makeSession()
        if central.state == .poweredOn { beginScan() } else { status = "Bluetooth aus?" }
    }

    func stop() {
        wantScan = false
        AppState.shared.active = false
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        if central.isScanning { central.stopScan() }
        connected = false
        status = "Gestoppt"
    }

    func forgetSensor() {
        // Wenn verbunden: den Sensor sauber ENTKOPPELN (0xf2) — er löscht seinen Bond
        // und lässt sich danach an ein anderes Gerät (z. B. Android) koppeln. Sonst nur stoppen.
        if let s = session, let c = c002, peripheral != nil, let cmd = s.unpairCommand() {
            log("Sensor freigeben: Entkopplung senden")
            s.onUnpairResult = { [weak self] ok in
                guard let self = self else { return }
                self.log("Entkopplung \(ok.boolValue ? "ok" : "fehlgeschlagen")")
                self.stop()
                self.session = nil
                // NACH stop() setzen, sonst überschreibt „Gestoppt" die Meldung. Übersetzt via I18n.
                self.status = I18n.shared.get(lang: AppState.shared.language,
                                              key: ok.boolValue ? "sensor_released" : "sensor_release_unconfirmed")
            }
            peripheral?.writeValue(cmd.toData(), for: c, type: .withoutResponse)
            // Sicherheitsnetz: kommt keine Antwort, nach 5 s trotzdem stoppen.
            bleQueue.asyncAfter(deadline: .now() + 5) { [weak self] in
                guard let self = self, self.session != nil else { return }
                self.stop(); self.session = nil; self.status = "Bereit für neuen Sensor"
            }
        } else {
            stop()
            session = nil
            status = "Bereit für neuen Sensor"
        }
    }

    private func makeSession() {
        let s = AidexXSession(serial: serial)
        s.onCurrent = { [weak self] r in self?.report(r, live: true) }
        s.onBackfill = { [weak self] r in self?.report(r, live: false) }
        s.onStartTime = { ms in DispatchQueue.main.async { AppState.shared.updateSensorStart(ms: ms.int64Value) } }
        s.newestStoredMs = { KotlinLong(value: 0) }   // v1: immer voller Backfill (Resume kommt später)
        s.log = { m in DispatchQueue.main.async { AppState.shared.pushLog(s: m) } }
        session = s
    }

    private func report(_ r: AidexReading, live: Bool) {
        let mgdl = r.mgdl                       // Int32 (Kotlin Int)
        let rate = Double(r.rateMgdlPerMin)
        let ts = r.timestampMs
        let trend = r.rateMgdlPerMin
        if live {
            status = "Live: \(mgdl) mg/dL"
            ui {
                AppState.shared.pushLive(mgdl: mgdl, rate: rate, atMs: ts)
                Persistence.shared.saveHistory()
                Alarms.shared.check(mgdl: mgdl)
            }
        } else {
            ui {
                AppState.shared.pushBackfill(atMs: ts, v: mgdl, r: trend)
                self.scheduleSort()
            }
        }
    }

    /// Nach dem Backfill die Historie EINMAL sortieren (debounced) — sonst zieht der Chart
    /// eine gerade Linie vom zuerst gepushten Live-Wert zu den später gepushten alten Punkten.
    private func scheduleSort() {
        sortWork?.cancel()
        let work = DispatchWorkItem {
            AppState.shared.sortHistory()
            Persistence.shared.saveHistory()
        }
        sortWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0, execute: work)
    }

    // MARK: Scan / Handshake
    private func beginScan() {
        guard wantScan else { return }
        status = "Suche AiDEX…"
        seenLog = 0
        // Alle Geräte scannen (Vordergrund) und per Name matchen — wie Android.
        central.scanForPeripherals(withServices: nil, options: nil)
    }

    private func write002(_ data: Data, response: Bool = false) {
        guard let p = peripheral, let c = c002 else { return }
        p.writeValue(data, for: c, type: response ? .withResponse : .withoutResponse)
    }

    private func disconnect() {
        if let p = peripheral { central.cancelPeripheralConnection(p) }
    }

    /// f001-Notify (Masterkey) -> Session prüfen -> f002 lesen (iOS koppelt dabei automatisch).
    fileprivate func onMasterkeyNotify(_ data: Data) {
        guard let s = session else { return }
        if s.onMasterKey(value: data.kb()) {
            status = "Masterkey – koppeln…"
            awaitingRead002 = true
            if let p = peripheral, let c = c002 { p.readValue(for: c) }
        } else {
            status = "Masterkey ungültig"
        }
    }

    /// f002-Read (17 B) -> Session-Key ableiten -> ersten Startbefehl schreiben.
    fileprivate func onSessionKeyRead(_ data: Data) {
        guard let s = session else { return }
        guard let cmd = s.onSessionKeyRead(haveKey: false, value: data.kb()) else {
            status = "Session-Key fehlgeschlagen"; disconnect(); return
        }
        status = "Verbindung OK (\(serial))"
        connected = true
        let dn = foundName.isEmpty ? "AiDEX X-\(serial)" : foundName
        ui { AppState.shared.deviceName = dn }
        write002(cmd.toData())
    }

    /// f002-Notify -> nächstes Kommando (schreiben) / leer (nichts) / nil (trennen).
    fileprivate func onDataNotify(_ data: Data) {
        guard let s = session else { return }
        guard let cmd = s.onDataNotify(value: data.kb()) else { disconnect(); return }
        let d = cmd.toData()
        if !d.isEmpty { write002(d) }
    }

    /// f003-Notify -> aktueller Wert.
    fileprivate func onCurrentNotify(_ data: Data) {
        guard let s = session else { return }
        if let r = s.onCurrentNotify(value: data.kb()) { report(r, live: true) }
    }
}

// MARK: - CBCentralManagerDelegate
extension AidexXBle: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        guard c.state == .poweredOn, wantScan else { return }
        if let p = peripheral { c.connect(p, options: nil) }   // wiederhergestellten Sensor direkt verbinden
        else { beginScan() }
    }

    // iOS startet die App nach Beendigung für BLE-Events wieder und übergibt hier den
    // zuvor verbundenen Sensor. Wir hängen ihn + die (aus der SN abgeleitete) Session
    // wieder an; didUpdateState verbindet dann per connect(p) — auch im Hintergrund.
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        guard let ps = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral], let p = ps.first else { return }
        serial = AppState.shared.aidexSerial.uppercased()
        guard serial.count >= 6 else { return }   // ohne SN kein Handshake möglich
        peripheral = p
        p.delegate = self
        wantScan = true
        AppState.shared.active = true
        makeSession()
        status = "Wiederhergestellt – verbinde…"
    }
    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let rawName = p.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? ""
        let name = rawName.uppercased()
        if !name.isEmpty && seenLog < 30 { seenLog += 1; log("gesehen: '\(name)' \(RSSI)dBm") }
        guard name.contains(serial) else { return }   // NUR der eigene Sensor (SN im Namen)
        foundName = rawName.isEmpty ? name : rawName   // echter Werbename (AiDEX X-… oder LinX-…)
        log("Sensor gefunden: \(foundName)")
        c.stopScan()
        peripheral = p
        p.delegate = self
        status = "Verbinde…"
        c.connect(p, options: nil)
    }
    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        log("verbunden → Dienste suchen")
        notifyReady = 0; handshakeStarted = false; awaitingRead002 = false
        c001 = nil; c002 = nil; c003 = nil
        p.discoverServices([svcUUID])
    }
    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        log("connect fehlgeschlagen: \(error?.localizedDescription ?? "?")")
        scheduleReconnect()
    }
    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        connected = false
        status = "Getrennt"
        scheduleReconnect()
    }

    /// Auto-Reconnect. Bekannten Sensor per connect(p) wieder anfordern — das hält iOS
    /// auch im HINTERGRUND aufrecht (Re-Scannen ist im Hintergrund gesperrt, deshalb blieb
    /// AiDEX bisher hängen). Nur ohne bekannten Sensor (Erstverbindung) wird gescannt.
    private func scheduleReconnect() {
        guard wantScan else { return }
        makeSession()                              // frischer Handshake beim Reconnect
        if let p = peripheral {
            status = "Getrennt – verbinde neu…"
            p.delegate = self
            central.connect(p, options: nil)       // ausstehende Wiederverbindung, hintergrundfähig
        } else {
            beginScan()                            // Erstverbindung (nur im Vordergrund möglich)
        }
    }
}

// MARK: - CBPeripheralDelegate
extension AidexXBle: CBPeripheralDelegate {
    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        log("Dienste: \(p.services?.count ?? 0) err=\(error?.localizedDescription ?? "-")")
        guard let svc = p.services?.first(where: { $0.uuid == svcUUID }) else { log("Service 181F fehlt"); disconnect(); return }
        p.discoverCharacteristics([uF001, uF002, uF003], for: svc)
    }
    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for ch in service.characteristics ?? [] {
            log("Char \(ch.uuid) props=0x\(String(ch.properties.rawValue, radix: 16))")
            switch ch.uuid {
            case uF001: c001 = ch
            case uF002: c002 = ch
            case uF003: c003 = ch
            default: break
            }
        }
        log("Chars f001=\(c001 != nil) f002=\(c002 != nil) f003=\(c003 != nil)")
        guard c001 != nil, c002 != nil, c003 != nil else {
            status = "AiDEX-Characteristics fehlen"; disconnect(); return
        }
        // iOS-Kopplung anstoßen: askKey direkt auf die verschlüsselte f001 schreiben (stärkster
        // Pairing-Auslöser). Bei „Authentication insufficient" koppelt iOS oft asynchron —
        // dann wiederholen wir, bis der Write durchgeht.
        pairTries = 0; paired = false
        tryAskKey(p)
    }
    private func tryAskKey(_ p: CBPeripheral) {
        guard let s = session, let c = c001 else { return }
        pairTries += 1
        log("askKey → f001 (Versuch \(pairTries))")
        p.writeValue(s.askKeyBytes().toData(), for: c, type: .withResponse)
    }
    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor ch: CBCharacteristic, error: Error?) {
        log("Notify an \(ch.uuid) err=\(error?.localizedDescription ?? "-")")
        if ch.uuid == uF001 && error == nil && paired && !handshakeStarted {
            handshakeStarted = true
            log("f001-Notify aktiv → askKey für Masterkey")
            tryAskKey(p)   // jetzt liefert der Sensor den Masterkey per f001-Notify
        }
    }
    func peripheral(_ p: CBPeripheral, didWriteValueFor ch: CBCharacteristic, error: Error?) {
        guard ch.uuid == uF001 else { return }
        if let e = error {
            log("askKey-Write Fehler: \(e.localizedDescription)")
            if !paired && pairTries < 6 {
                bleQueue.asyncAfter(deadline: .now() + 2) { [weak self] in
                    guard let self = self, self.wantScan, self.peripheral != nil else { return }
                    self.tryAskKey(p)
                }
            } else if !paired {
                status = "Kopplung fehlgeschlagen"
                log("iOS bondet nicht mit dem Sensor — dann Broadcast-Weg")
            }
        } else if !paired {
            paired = true
            log("askKey ok (gekoppelt) → Notifies aktivieren")
            if let a = c001, let b = c002, let d = c003 {
                p.setNotifyValue(true, for: a)
                p.setNotifyValue(true, for: b)
                p.setNotifyValue(true, for: d)
            }
        }
    }
    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        guard let v = ch.value else { return }
        log("Wert \(ch.uuid) \(v.count)B err=\(error?.localizedDescription ?? "-")")
        switch ch.uuid {
        case uF001: onMasterkeyNotify(v)
        case uF003: onCurrentNotify(v)
        case uF002:
            if awaitingRead002 { awaitingRead002 = false; onSessionKeyRead(v) }
            else { onDataNotify(v) }
        default: break
        }
    }
}
