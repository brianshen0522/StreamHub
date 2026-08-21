import Foundation
import SwiftUI

/// Hand-wired dependencies and the one piece of state above every screen:
/// whether anybody is signed in.
///
/// One person's app with a handful of objects does not need a DI framework.
@Observable
@MainActor
final class AppModel {

    let store = SessionStore()
    let api: APIClient

    /// Nil means the sign-in screen. Set on sign-in, cleared when the server
    /// stops renewing the session.
    var session: Session?

    init() {
        let store = self.store
        self.api = APIClient(baseURL: ApiConfig.serverURL, store: store)
        self.session = store.load()

        Task { [api] in
            for await _ in await api.sessionEnded {
                self.session = nil
            }
        }
    }

    func signedIn(_ session: Session) {
        self.session = session
    }

    func signOut() {
        Task {
            await api.logout()
            session = nil
        }
    }

    func posterURL(_ target: String?) -> URL? {
        guard let target, !target.isEmpty else { return nil }
        return api.posterURL(target: target)
    }
}

extension Color {
    /// The web client's `--accent`, so every client looks like the same product.
    static let streamHubAccent = Color(red: 0xE5 / 255, green: 0x09 / 255, blue: 0x14 / 255)
}
