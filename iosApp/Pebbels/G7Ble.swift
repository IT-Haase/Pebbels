import Foundation
import CoreBluetooth
import Shared

// ---------------------------------------------------------------------------
// Brücke Swift Data <-> Kotlin ByteArray
// ---------------------------------------------------------------------------
extension Data {
    func kb() -> KotlinByteArray {
        let a = KotlinByteArray(size: Int32(count))
        for (i, b) in enumerated() { a.set(index: Int32(i), value: Int8(bitPattern: b)) }
        return a
    }
}
extension KotlinByteArray {
    func toData() -> Data {
        var d = Data(count: Int(size))
        for i in 0..<Int(size) { d[i] = UInt8(bitPattern: get(index: Int32(i))) }
        return d
    }
}

// ---------------------------------------------------------------------------
// Dexcom G7 / ONE+ CoreBluetooth-Treiber.
// Portiert die Android-G7BleClient-State-Machine; Krypto = shared DexSession.
// iOS koppelt automatisch (kein createBond) — der [0x06,0x19]-Trigger reicht.
// ---------------------------------------------------------------------------
final class G7Ble: NSObject {

    // BLE läuft auf eigenem Thread; UI-Updates immer zum Haupt-Thread schieben,
    // damit der timing-empfindliche Handshake nicht von der Oberfläche gebremst wird.
    private let bleQueue = DispatchQueue(label: "pebbels.ble", qos: .userInitiated)
    private func ui(_ block: @escaping () -> Void) { DispatchQueue.main.async(execute: block) }

    // UI-Status fließt in den geteilten Compose-AppState (commonMain) — auf Main.
    private var status: String = "Bereit" {
        didSet { let s = status; ui { AppState.shared.status = s; AppState.shared.pushLog(s: s) } }
    }
    private var connected: Bool = false {
        didSet { let c = connected; ui { AppState.shared.connected = c } }
    }
    private var authenticated = false
    private var restoredFromBlob = false

    // BLE
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var chars = [CBCharacteristic?](repeating: nil, count: 4)
    private var wantScan = false

    // UUIDs (aus DexCerts.kt)
    private let scanService = CBUUID(string: "0000FEBC-0000-1000-8000-00805F9B34FB")
    private let svc = CBUUID(string: "F8083532-849E-531C-C594-30F1F86A4EA5")
    private let cUUID = [
        CBUUID(string: "F8083534-849E-531C-C594-30F1F86A4EA5"), // 0 data/glucose
        CBUUID(string: "F8083535-849E-531C-C594-30F1F86A4EA5"), // 1 auth
        CBUUID(string: "F8083536-849E-531C-C594-30F1F86A4EA5"), // 2 backfill
        CBUUID(string: "F8083538-849E-531C-C594-30F1F86A4EA5"), // 3 cert transport
    ]
    private let namePrefixes = ["DXCM", "DX02", "DX01"]

    // Die zwei öffentlichen Dexcom-Zertifikate (DEX00PG1 / DEX03PG1).
    private let CERTS: [Data] = [
        Data(base64Encoded: "MIIB6jCCAY+gAwIBAgIULzxStusIcBBG1F14zoF4TJ3+UkAwCgYIKoZIzj0EAwIwEzERMA8GA1UEAwwIREVYMDBQRzEwHhcNMjAxMDMwMTU1OTA0WhcNMzUxMDI3MTU1OTA0WjATMREwDwYDVQQDDAhERVgwM1BHMTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABPsayiHYruyaTrUfhTBJU9l3oa1Wl5klD/hjmH9Co82fpP9XHrVovGw5YnfD3LUd7a7oVRPIClxENVOKGfWpY0ijgcAwgb0wDwYDVR0TAQH/BAUwAwEB/zAfBgNVHSMEGDAWgBSeDx428/J2pwH+jog6biamNb1q/DBaBgNVHR8EUzBRME+gNKAyhjBodHRwOi8vY3JsLmRwLnNhYXMucHJpbWVrZXkuY29tL2NybC9ERVgwMFBHMS5jcmyiF6QVMBMxETAPBgNVBAMMCERFWDAwUEcxMB0GA1UdDgQWBBSI9h6BvEsX8FxrG+KZHWAIfM7deTAOBgNVHQ8BAf8EBAMCAYYwCgYIKoZIzj0EAwIDSQAwRgIhAKppzYl+xmOvX54VgYffaFH/B1bwDEAWJFZPgaGfWgeFAiEA2uu5/bFjtzHrBmHxwKGTKHGlDjma0cb1Geq9TJ57oBM=")!,
        Data(base64Encoded: "MIIBzTCCAXSgAwIBAgIUGQUvzBdTC/pW5J3K/NrPhTzlunMwCgYIKoZIzj0EAwIwEzERMA8GA1UEAwwIREVYMDNQRzEwHhcNMjMwNDE0MTAyODE0WhcNMjUwNDEzMTAyODEzWjA6MTgwNgYDVQQDDC8wMSwwMDAwLDAzMDBMUUVDQ3pBQkF3QUEsY2lvaWUzVmJRMmhsWk1qZFVtNXJnQTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABFEYw16eQefgZU/ugBxSqcXfxRDvCVl9XMqEYeSvnGZnFINPK8kD8W+r/EV1WwGD8aCXRc3/y04veZ5QvtmmtYyjfzB9MAwGA1UdEwEB/wQCMAAwHwYDVR0jBBgwFoAUiPYegbxLF/BcaxvimR1gCHzO3XkwHQYDVR0lBBYwFAYIKwYBBQUHAwIGCCsGAQUFBwMBMB0GA1UdDgQWBBTTCedcByVBLXp5IuOqz7J/fr1r4DAOBgNVHQ8BAf8EBAMCBaAwCgYIKoZIzj0EAwIDRwAwRAIgSNSGjPOT2QRBAbbwf9aNfwZCgF+F2nTi/p3o3TUH8CcCIBzRv3xsft1ZQ14ySSX88OuzyuIRDXlAfHeqO5O3vATL")!,
    ]

    // Handshake-Phasen (wie Android)
    private let PH_NONE = -1, PH_R1 = 1, PH_R2 = 2, PH_R3 = 3, PH_REQ_AUTH = 4,
                PH_CHALLENGE = 5, PH_CERT1 = 7, PH_CERT2 = 8, PH_KEYCHAL = 9,
                PH_KEYCHAL_OUT = 10, PH_GETDATA = 11
    private var phase = -1

    private var session: DexSession?
    private var pin: [UInt8] = []
    private var devName = "G7"

    private var random8 = [UInt8](repeating: 0, count: 8)
    private var certIn = Data()
    private var certSize = 0x10000
    private var newCertificates = false
    private var backfilled = false
    private var didFullBackfill = false
    private var backfillBuf = [(Int64, Int32, Float)]()
    private var handshakeFails = 0   // zählt fehlgeschlagene Handshakes (Zombie-Bond-Erkennung)

    // Cert-Chunk-Sender (char[3], ohne Antwort)
    private var chunkQueue = [Data]()
    private var afterChunks: (() -> Void)?

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: bleQueue,
                                   options: [CBCentralManagerOptionRestoreIdentifierKey: "pebbels.central"])
    }

    // MARK: Public
    func start(pin: [UInt8]) {
        guard pin.count == 4 else { status = "PIN muss 4-stellig sein"; return }
        self.pin = pin
        session = DexSession(serial: "G7", pin: Data(pin).kb())
        restoredFromBlob = false
        if let blob = KeychainStore.loadBlob(), session?.fromBlob(blob: blob.kb()) == true {
            restoredFromBlob = true
        }
        phase = PH_NONE; certIn = Data(); backfilled = false; newCertificates = false
        didFullBackfill = false
        AppState.shared.backfillCount = 0
        AppState.shared.live = false
        handshakeFails = 0
        wantScan = true
        AppState.shared.active = true
        if central.state == .poweredOn { beginScan() } else { status = "Bluetooth aus?" }
    }

    func stop() {
        wantScan = false
        AppState.shared.active = false
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        if central.isScanning { central.stopScan() }
        connected = false; authenticated = false; status = "Gestoppt"
    }

    /// „Neuer Sensor": trennen, gespeicherten Schlüssel + Session verwerfen,
    /// PIN/Name leeren. Verlauf bleibt erhalten — danach frisch koppeln.
    func forgetSensor() {
        stop()
        session?.resetKeys()
        session = nil
        restoredFromBlob = false
        KeychainStore.clear()
        AppState.shared.pin = ""
        AppState.shared.deviceName = nil
        AppState.shared.live = false
        AppState.shared.lastGlucose = nil
        status = "Bereit für neuen Sensor"
    }

    /// Beim App-Start automatisch verbinden, wenn ein Sensor gespeichert ist.
    func autoStartIfPaired() {
        if wantScan { return }
        guard let pinD = KeychainStore.loadPin(), KeychainStore.loadBlob() != nil else { return }
        start(pin: [UInt8](pinD))
    }

    // MARK: Scan
    private func beginScan() {
        guard wantScan else { return }
        status = "Suche Sensor…"
        central.scanForPeripherals(withServices: [scanService], options: nil)
    }

    // MARK: Handshake-Helfer
    private func writeC1(_ bytes: [UInt8]) {
        guard let p = peripheral, let c = chars[1] else { return }
        p.writeValue(Data(bytes), for: c, type: .withResponse)
    }
    private func writeC0(_ data: Data) {
        guard let p = peripheral, let c = chars[0] else { return }
        p.writeValue(data, for: c, type: .withResponse)
    }
    private func cmd(_ n: Int) { writeC1([0x0A, UInt8(n)]) }

    private func docmd0() {
        certIn = Data(); phase = PH_R1; status = "Handshake (Runde 1)"; cmd(0)
    }

    private func requestAuth() {
        phase = PH_REQ_AUTH
        for i in 0..<8 { random8[i] = UInt8.random(in: 0...255) }
        writeC1([0x02] + random8 + [0x02])
    }

    /// Sendet `packet` in 20-Byte-Stücken auf char[3] (.withoutResponse), danach `then`.
    private func sendCerts(_ packet: Data, then: @escaping () -> Void) {
        chunkQueue.removeAll()
        var off = 0
        while off < packet.count {
            let end = min(off + 20, packet.count)
            chunkQueue.append(packet.subdata(in: off..<end))
            off = end
        }
        afterChunks = then
        pumpChunks()
    }
    private func pumpChunks() {
        guard let p = peripheral, let c = chars[3] else { return }
        while !chunkQueue.isEmpty && p.canSendWriteWithoutResponse {
            let chunk = chunkQueue.removeFirst()
            p.writeValue(chunk, for: c, type: .withoutResponse)
        }
        if chunkQueue.isEmpty, let done = afterChunks {
            afterChunks = nil
            done()
        }
    }

    /// Phasenübergang nach dem Senden eines Pakets (entspricht Android sendCerts-Ende).
    private func afterSend() {
        if phase == PH_R1 || phase == PH_R2 {
            certIn = Data()
            cmd(phase)
            phase += 1
        } else {
            phase += 1
            switch phase {
            case PH_REQ_AUTH: newCertificates = true; requestAuth()
            case PH_CERT2: askCertificate(PH_CERT2)
            case PH_KEYCHAL: sendKeyChallenge()
            case PH_KEYCHAL_OUT: writeC1([0x0D, 0x00, 0x02])
            default: break
            }
        }
    }

    private func askCertificate(_ pha: Int) {
        phase = pha
        let idx = pha - PH_CERT1
        certIn = Data()
        let len = CERTS[idx].count
        certSize = len
        writeC1([0x0B, UInt8(idx),
                 UInt8(len & 0xFF), UInt8((len >> 8) & 0xFF),
                 UInt8((len >> 16) & 0xFF), UInt8((len >> 24) & 0xFF)])
    }

    private func sendKeyChallenge() {
        phase = PH_KEYCHAL
        certIn = Data()
        var buf = [UInt8](repeating: 0, count: 17)
        for i in 0..<17 { buf[i] = UInt8.random(in: 0...255) }
        buf[0] = 0x0C
        writeC1(buf)
    }

    private func getDataCmd() {
        handshakeFails = 0   // erfolgreich → Zähler zurücksetzen
        phase = PH_GETDATA
        guard let p = peripheral, let c = chars[0] else { return }
        p.setNotifyValue(true, for: c)   // → didUpdateNotificationStateFor → writeC0([0x4E])
    }

    private func resetCerts() {
        session?.resetKeys()
        if let p = peripheral { central.cancelPeripheralConnection(p) }
    }

    // MARK: Eingehende Daten
    private func onCert(_ value: Data) {
        if certIn.isEmpty { status = "Cert kommt (\(value.count) B) Ph\(phase)" }
        certIn.append(value)
        switch phase {
        case PH_R1, PH_R2, PH_R3:
            if certIn.count == 160 {
                let fromRound = Int32(phase - PH_R1)
                session?.putPubKey(which: fromRound, input: certIn.kb())
                if phase < PH_R3 {
                    guard let pkt = session?.makeRound12(which: fromRound).toData() else { resetCerts(); return }
                    sendCerts(pkt) { [weak self] in self?.afterSend() }
                } else {
                    guard let pkt = session?.makeRound3()?.toData() else { resetCerts(); return }
                    sendCerts(pkt) { [weak self] in self?.afterSend() }
                }
            }
        case PH_CERT1, PH_CERT2:
            if certIn.count >= certSize {
                let idx = phase - PH_CERT1
                sendCerts(CERTS[idx]) { [weak self] in self?.afterSend() }
            }
        default: break
        }
    }

    private func onAuth(_ value: Data) {
        let b = [UInt8](value)
        switch phase {
        case PH_REQ_AUTH:
            guard let aes = session?.authConfirm(data: Data(random8).kb(), startdat: 0)?.toData(),
                  b.count >= 9, Array(b[1..<9]) == [UInt8](aes) else {
                if restoredFromBlob { KeychainStore.clear(); restoredFromBlob = false }
                resetCerts(); return
            }
            phase = PH_CHALLENGE
            guard b.count >= 17,
                  let dataaes = session?.authConfirm(data: value.kb(), startdat: 9)?.toData() else { resetCerts(); return }
            writeC1([0x04] + [UInt8](dataaes))
        case PH_CHALLENGE:
            var auth = 0, bond = 0
            if b.count >= 3 && b[0] == 0x05 { auth = Int(b[1]); bond = Int(b[2]) }
            if bond == 3 { resetCerts(); return }
            let isBonded = bond == 1
            if !newCertificates && auth != 1 { resetCerts() }
            else if (auth == 1 && isBonded) { getDataCmd() }
            else { askCertificate(PH_CERT1) }
        case PH_CERT1, PH_CERT2:
            certSize = Int(DexSession.companion.certSize(ar: value.kb()))
            if certSize < 0 { resetCerts() }
        case PH_KEYCHAL:
            guard let sig = session?.challenge(value: value.kb()).toData() else { resetCerts(); return }
            sendCerts(sig) { [weak self] in self?.afterSend() }
        case PH_KEYCHAL_OUT:
            session?.deviceName = devName
            phase = PH_GETDATA
            authenticated = true
            if let blob = session?.toBlob() { KeychainStore.saveBlob(blob.toData()); KeychainStore.savePin(Data(pin)) }
            status = "Authentifiziert – koppeln…"
            writeC1([0x06, 0x19])   // Bond-Trigger; iOS koppelt automatisch
        default:
            if b == [0x06, 0x01] || phase == PH_GETDATA { getDataCmd() }
        }
    }

    private func onGlucose(_ value: Data) {
        guard let first = value.first else { return }
        if first == 0x4E {
            if let r = session?.processData(data: value.kb()) {
                let mgdl = r.mgdl, rate = Double(r.rateMgdlPerMin), ts = r.timestampMs
                let sensorStart = session?.sensorStartMs ?? 0   // für Ablauf-Hinweis
                status = "Live: \(Int(mgdl)) mg/dL"
                ui {
                    AppState.shared.pushLive(mgdl: mgdl, rate: rate, atMs: ts)
                    AppState.shared.updateSensorStart(ms: sensorStart)
                    Persistence.shared.saveHistory()
                    Alarms.shared.check(mgdl: mgdl)
                }
            }
            bleQueue.asyncAfter(deadline: .now() + 0.05) { [weak self] in self?.askBackfill() }
        } else if first == 0x59 {
            // Backfill fertig → einmal alles übernehmen, sortieren, speichern.
            let buf = backfillBuf; backfillBuf = []
            status = "History geladen (\(buf.count))"
            ui {
                for (t, v, rr) in buf { AppState.shared.pushBackfill(atMs: t, v: v, r: rr) }
                AppState.shared.sortHistory()
                Persistence.shared.saveHistory()
            }
        }
    }

    private func askBackfill() {
        guard let p = peripheral else { return }
        if !backfilled {
            if let c = chars[2] { p.setNotifyValue(true, for: c) }
            return
        }
        if didFullBackfill { return }   // v1: komplette History einmal pro Verbindung
        didFullBackfill = true
        if let cmd = session?.backfillCmd()?.toData() {
            status = "Hole History…"
            writeC0(cmd)
        }
    }
}

// ---------------------------------------------------------------------------
extension G7Ble: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            if let p = peripheral, wantScan { central.connect(p, options: nil) }  // wiederhergestellten Sensor verbinden
            else if wantScan { beginScan() }
        } else { status = "Bluetooth nicht bereit" }
    }

    // iOS startet die App nach Force-Quit/Neustart für BLE-Events wieder und
    // übergibt hier den zuvor verbundenen Sensor + wir stellen die Session her.
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        guard let ps = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral], let p = ps.first else { return }
        peripheral = p
        p.delegate = self
        wantScan = true
        AppState.shared.active = true
        if let pinD = KeychainStore.loadPin(), let blob = KeychainStore.loadBlob() {
            pin = [UInt8](pinD)
            let s = DexSession(serial: "G7", pin: pinD.kb())
            s.fromBlob(blob: blob.kb())
            session = s
        }
        status = "Wiederhergestellt – verbinde…"
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? p.name ?? ""
        if !name.isEmpty && !namePrefixes.contains(where: { name.hasPrefix($0) }) { return }
        devName = name.isEmpty ? "G7" : name
        c.stopScan()
        peripheral = p
        p.delegate = self
        status = "Verbinde \(devName)…"
        c.connect(p, options: nil)
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        connected = true
        // Bei JEDER (Wieder-)Verbindung Phase zurücksetzen, damit der Auth-Schritt
        // neu triggert. Schlüssel behalten wenn authentifiziert (schneller Reconnect
        // ohne JPAKE); sonst frische Session für einen sauberen Handshake.
        phase = PH_NONE; certIn = Data()
        if session?.authenticated != true {
            session = DexSession(serial: "G7", pin: Data(pin).kb())
        }
        status = "Verbunden – suche Dienste"
        p.discoverServices([svc])
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        let wasAuthed = (session?.authenticated == true)
        connected = false; authenticated = false
        for i in chars.indices { chars[i] = nil }
        if wantScan {
            if wasAuthed {
                handshakeFails = 0
            } else {
                handshakeFails += 1
                if handshakeFails >= 3 {
                    wantScan = false
                    AppState.shared.active = false
                    status = "Bond-Problem: Sensor in den Bluetooth-Einstellungen vergessen, dann Start"
                    return
                }
            }
            let e = error as NSError?
            status = "Getrennt (\(e?.code ?? 0)) – verbinde neu…"
            c.connect(p, options: nil)   // ausstehende Wiederverbindung — läuft auch im Hintergrund
        }
    }
}

// ---------------------------------------------------------------------------
extension G7Ble: CBPeripheralDelegate {
    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == svc }) else { resetCerts(); return }
        p.discoverCharacteristics(cUUID, for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for ch in service.characteristics ?? [] {
            if let idx = cUUID.firstIndex(of: ch.uuid) { chars[idx] = ch }
        }
        guard chars.allSatisfy({ $0 != nil }) else { resetCerts(); return }
        status = "Handshake startet"
        if let c = chars[3] { p.setNotifyValue(true, for: c) }   // Cert-Kanal zuerst
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor ch: CBCharacteristic, error: Error?) {
        if ch == chars[3] {
            if let c = chars[1] { p.setNotifyValue(true, for: c) }
        } else if ch == chars[1] {
            if phase < PH_REQ_AUTH {
                if session?.authenticated == true { requestAuth() } else { docmd0() }
            }
        } else if ch == chars[0] {
            writeC0(Data([0x4E]))       // Glukose anfordern
        } else if ch == chars[2] {
            backfilled = true
            askBackfill()
        }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        guard let v = ch.value else { return }
        if ch == chars[0] { onGlucose(v) }
        else if ch == chars[1] { onAuth(v) }
        else if ch == chars[2] {
            let readings = session?.parseBackfill(data: v.kb()) ?? []
            for r in readings { backfillBuf.append((r.timestampMs, r.mgdl, r.rateMgdlPerMin)) }   // nur puffern (kein UI/IO im Burst)
        }

        else if ch == chars[3] { onCert(v) }
    }

    func peripheralIsReady(toSendWriteWithoutResponse p: CBPeripheral) {
        pumpChunks()
    }
}
