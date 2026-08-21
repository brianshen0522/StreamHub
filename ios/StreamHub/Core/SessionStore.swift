import Foundation
import Security

/// Where the session lives between launches.
///
/// The Keychain rather than UserDefaults: these are long-lived credentials —
/// a refresh token is good for thirty days — and UserDefaults is a plist in the
/// container, readable by anything that can read the container.
///
/// `afterFirstUnlock` rather than the default: playback continues with the
/// screen locked, and a token the app cannot read while locked would end the
/// stream the moment it needed renewing.
final class SessionStore: @unchecked Sendable {

    private let service = "com.streamhub.ios.session"
    private let account = "session"
    private let lock = NSLock()
    private var cached: Session?

    init() {
        cached = readFromKeychain()
    }

    func load() -> Session? {
        lock.lock()
        defer { lock.unlock() }
        return cached
    }

    func save(_ session: Session) {
        lock.lock()
        cached = session
        lock.unlock()

        guard let data = try? JSONEncoder().encode(session) else { return }
        var query = baseQuery()
        SecItemDelete(query as CFDictionary)
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(query as CFDictionary, nil)
    }

    func clear() {
        lock.lock()
        cached = nil
        lock.unlock()
        SecItemDelete(baseQuery() as CFDictionary)
    }

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private func readFromKeychain() -> Session? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(Session.self, from: data)
    }
}
