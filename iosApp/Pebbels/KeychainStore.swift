import Foundation
import Security

/// Session-Schlüssel (Blob + PIN) im iOS-Schlüsselbund. Anders als der
/// Dokumente-Ordner überlebt der Keychain das Löschen/Neuinstallieren der App
/// → nach einem Neuinstall reconnectet die App ohne neuen Handshake (kein
/// manuelles „Sensor vergessen" mehr).
enum KeychainStore {
    private static let service = "de.haase.Pebbels.session"

    private static func save(_ account: String, _ data: Data) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }

    private static func load(_ account: String) -> Data? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    private static func delete(_ account: String) {
        SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ] as CFDictionary)
    }

    static func saveBlob(_ d: Data) { save("blob", d) }
    static func savePin(_ d: Data) { save("pin", d) }
    static func loadBlob() -> Data? { load("blob") }
    static func loadPin() -> Data? { load("pin") }
    static func clear() { delete("blob"); delete("pin") }
}
