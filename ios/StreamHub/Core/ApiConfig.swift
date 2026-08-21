import Foundation
import UIKit

/// The parts of the server contract every request depends on.
///
/// See `shared/api/README.md` for the behaviour behind these — in particular why
/// the client header matters and why refresh has to be single-flight.
enum ApiConfig {
    /// Clients pin to the versioned prefix so the unversioned surface the web app
    /// uses stays free to change. Nothing pushes updates to a sideloaded build,
    /// so an old client has to keep working against a newer server.
    static let basePath = "/api/v1"

    /// Identifies this build as a playback client. Sending it is what makes the
    /// server refuse an admin account at the login screen, rather than handing
    /// back a session that fails on every content screen afterwards.
    static let clientHeader = "X-StreamHub-Client"
    static let clientKind = "ios"

    static let clientVersion = "0.2.0"

    /// Reported as the user agent, which is what the account's device list
    /// shows. URLSession's default names the app and CFNetwork, which tells the
    /// account holder nothing about which of their devices they are looking at.
    static var userAgent: String {
        let model = UIDevice.current.model
        let system = UIDevice.current.systemVersion
        return "StreamHub-Ios/\(clientVersion) (\(model); iOS \(system))"
    }

    /// Fixed at build time, from Info.plist.
    static var serverURL: URL {
        let raw = Bundle.main.object(forInfoDictionaryKey: "StreamHubServerURL") as? String
        guard let raw, let url = URL(string: raw.trimmingCharacters(in: CharacterSet(charactersIn: "/"))) else {
            // A build without an address cannot do anything, and failing here
            // names the cause instead of producing 400s from every screen.
            fatalError("StreamHubServerURL is missing or malformed in Info.plist")
        }
        return url
    }
}
