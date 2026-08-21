import Foundation

// MARK: - Session

struct User: Codable, Hashable, Sendable {
    let id: String
    let username: String
    var email: String?
    var displayName: String?
    var role: String
    var status: String
}

struct Session: Codable, Sendable {
    var user: User
    var accessToken: String
    var refreshToken: String
}

// MARK: - Search and detail

struct SearchItem: Codable, Hashable, Sendable, Identifiable {
    let provider: String
    let title: String
    let url: String
    var posterUrl: String?
    var mediaType: String = "unknown"

    var id: String { provider + url }
}

/// One provider's slice of a search.
///
/// A provider that failed still gets an entry, with `error` set — `/api/search`
/// answers 200 either way, so this field is the only place a failure shows up.
struct ProviderResults: Codable, Hashable, Sendable, Identifiable {
    let provider: String
    var items: [SearchItem] = []
    var error: String?

    var id: String { provider }
}

struct SearchResponse: Codable, Sendable {
    var query: String = ""
    var results: [ProviderResults] = []
}

struct SeasonRef: Codable, Hashable, Sendable, Identifiable {
    let label: String
    let url: String

    var id: String { url }
}

struct RawStream: Codable, Hashable, Sendable {
    let sourceLabel: String
    var episodeLabel: String?
    let url: String
}

struct Source: Codable, Hashable, Sendable, Identifiable {
    let sourceLabel: String
    var episodeLabel: String?
    let url: String
    var durationSeconds: Int?
    var adSeconds: Int = 0
    var ok: Bool = true
    let directUrl: String
    var proxyUrl: String?

    var id: String { url }
}

/// `/api/item` is polymorphic and returned bare.
///
/// Which case it is has to be discriminated on the key that is present, not on
/// any type field: `seasons` is a hub needing a second call, `episodes` is a
/// season or single-page series, `streams` is a film.
enum ItemDetail: Sendable {
    case seasons(title: String, posterUrl: String?, seasons: [SeasonRef])
    case episodes(title: String, posterUrl: String?, sourceUrl: String?, episodes: [String])
    case movie(title: String, posterUrl: String?, streams: [RawStream])
}

struct AdCut: Codable, Hashable, Sendable {
    let at: Double
    let removed: Double
}

struct AdCuts: Codable, Sendable {
    var removedSeconds: Double = 0
    var reason: String?
    var cuts: [AdCut] = []
}

// MARK: - Library

struct Favorite: Codable, Hashable, Sendable, Identifiable {
    let id: String
    let providerKey: String
    var mediaType: String = "unknown"
    let title: String
    var posterUrl: String?
    let itemUrl: String
    var seasonUrl: String?
    var seasonLabel: String?
    var episodeLabel: String?
}

struct NewFavorite: Codable, Sendable {
    let providerKey: String
    var mediaType: String = "unknown"
    let title: String
    var posterUrl: String?
    let itemUrl: String
    var seasonUrl: String?
    var seasonLabel: String?
    var episodeLabel: String?
}

struct ContinueItem: Codable, Hashable, Sendable, Identifiable {
    let providerKey: String
    var mediaType: String = "unknown"
    let title: String
    var posterUrl: String?
    let itemUrl: String
    var seasonUrl: String?
    var seasonLabel: String?
    var episodeLabel: String?
    var sourceLabel: String?
    var durationSeconds: Int = 0
    var positionSeconds: Int = 0
    var progressPercent: Double = 0
    var isCompleted: Bool = false
    var lastWatchedAt: String?

    var id: String { providerKey + itemUrl }
    var fraction: Double { min(max(progressPercent / 100, 0), 1) }
}

struct WatchProgress: Codable, Hashable, Sendable {
    var providerKey: String = ""
    var itemUrl: String = ""
    var seasonUrl: String?
    var episodeLabel: String?
    var durationSeconds: Int = 0
    var positionSeconds: Int = 0
    var progressPercent: Double = 0
    var isCompleted: Bool = false
    var lastWatchedAt: String?
}

struct ProgressUpdate: Codable, Sendable {
    let providerKey: String
    let mediaType: String
    let title: String
    var posterUrl: String?
    let itemUrl: String
    var seasonUrl: String?
    var seasonLabel: String?
    var episodeLabel: String?
    var sourceLabel: String?
    var durationSeconds: Int
    var positionSeconds: Int
    var event: String
}

struct WatchHistoryEntry: Codable, Hashable, Sendable, Identifiable {
    let id: String
    let providerKey: String
    var mediaType: String = "unknown"
    let title: String
    var posterUrl: String?
    let itemUrl: String
    var episodeLabel: String?
    var watchedAt: String?
}

// MARK: - Devices and casting

struct DeviceSession: Codable, Hashable, Sendable, Identifiable {
    let id: String
    var clientKind: String?
    var deviceName: String = "Unknown device"
    var lastSeenAt: String?
    var current: Bool = false

    var isTelevision: Bool { clientKind == "tv" }
}

struct CastPlaybackState: Codable, Hashable, Sendable {
    var provider: String?
    var itemUrl: String?
    var title: String?
    var subtitle: String?
    var posterUrl: String?
    var episodeLabel: String?
    var positionMs: Int = 0
    var durationMs: Int = 0
    var paused: Bool = false
    var buffering: Bool = false
}

struct CastReceiver: Codable, Hashable, Sendable, Identifiable {
    let sessionId: String
    var deviceName: String
    var clientKind: String?
    var state: CastPlaybackState?

    var id: String { sessionId }
    var isTelevision: Bool { clientKind == "tv" }
}

struct CastPlayRequest: Codable, Sendable {
    let streamUrl: String
    var provider: String?
    var itemUrl: String?
    var title: String?
    var subtitle: String?
    var posterUrl: String?
    var episodeLabel: String?
    var episodeUrl: String?
    var nextEpisodeLabel: String?
    var positionMs: Int = 0
}

/// What a controller may send. Encoded flat, with `action` as the discriminator,
/// because that is the shape the server validates.
enum CastCommand: Sendable {
    case play(CastPlayRequest)
    case pause
    case resume
    case stop
    case next
    case seek(positionMs: Int)
}

// MARK: - Health

struct ProviderInfo: Codable, Hashable, Sendable, Identifiable {
    let key: String
    var displayName: String?
    var status: String = "unknown"
    var enabled: Bool = true

    var id: String { key }
}

struct ServerHealth: Codable, Sendable {
    var status: String = "unknown"
    var apiVersion: Int = 1
}

/// What is asking to be signed in, shown before anything is granted.
///
/// The point of it is `deviceName`. A device flow cannot stop somebody being
/// talked into approving a code that is not theirs, so the only defence is that
/// the person can recognise whether the thing asking is their own television.
struct PendingDevice: Codable, Hashable, Sendable {
    var userCode: String
    var deviceName: String = "Unknown device"
    var clientKind: String?
    var requestedAt: String?
    var expiresAt: String?
}

/// The short pairing code, as a person handles it rather than as it is stored.
///
/// What gets typed is never quite what was displayed: people put the separator
/// back, or leave it out, or the keyboard capitalises for them. Only the
/// characters carry meaning, so everything is reduced to those before it goes
/// near the server, and the break is put back only for display.
///
/// Mirrors `UserCode` in android/core and `normalise` in frontend/src/LinkTv.jsx.
enum UserCode {
    static let length = 8

    static func normalise(_ input: String) -> String {
        input.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }

    static func isComplete(_ input: String) -> Bool {
        normalise(input).count == length
    }

    /// `ABCD-EFGH` — one break, because eight unbroken characters get miscounted.
    static func forDisplay(_ input: String) -> String {
        let clean = String(normalise(input).prefix(length))
        guard clean.count > 4 else { return clean }
        let split = clean.index(clean.startIndex, offsetBy: 4)
        return "\(clean[..<split])-\(clean[split...])"
    }
}
