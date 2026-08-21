import Foundation

struct StreamHubError: LocalizedError {
    let status: Int
    let message: String
    var errorDescription: String? { message }
}

/// The whole server surface this app uses.
///
/// An actor, because the one thing that must never happen concurrently is a
/// token refresh: `POST /api/auth/refresh` *rotates*, so two refreshes in
/// flight invalidate each other and kill the session. Serializing on the actor
/// plus a single stored task is what makes refresh single-flight.
actor APIClient {

    private let baseURL: URL
    private let store: SessionStore
    private let session: URLSession
    private var refreshTask: Task<Session?, Never>?

    /// Fires when the session cannot be renewed. The app takes this as "go back
    /// to sign-in" rather than leaving every screen failing with a Try again
    /// button that can never succeed.
    let sessionEnded: AsyncStream<Void>
    private let sessionEndedContinuation: AsyncStream<Void>.Continuation

    init(baseURL: URL, store: SessionStore) {
        self.baseURL = baseURL
        self.store = store

        let configuration = URLSessionConfiguration.default
        configuration.httpAdditionalHeaders = [
            ApiConfig.clientHeader: ApiConfig.clientKind,
            // Sign-in creates the session row, and the user agent it records is
            // what the account's device list shows — so this has to be on the
            // unauthenticated requests too, not just the authenticated ones.
            "User-Agent": ApiConfig.userAgent,
        ]
        configuration.waitsForConnectivity = true
        self.session = URLSession(configuration: configuration)

        var continuation: AsyncStream<Void>.Continuation!
        self.sessionEnded = AsyncStream { continuation = $0 }
        self.sessionEndedContinuation = continuation
    }

    /// A URLSession configured the way media and image loads need: the bearer
    /// token on every request, including the second one a master playlist
    /// causes.
    nonisolated func authorizedRequest(_ url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue(ApiConfig.clientKind, forHTTPHeaderField: ApiConfig.clientHeader)
        request.setValue(ApiConfig.userAgent, forHTTPHeaderField: "User-Agent")
        if let token = store.load()?.accessToken, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    // MARK: - URL building

    nonisolated func url(_ segments: [String], query: [URLQueryItem] = []) -> URL {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        components.path = ApiConfig.basePath + "/" + segments.joined(separator: "/")
        if !query.isEmpty { components.queryItems = query }
        return components.url!
    }

    // MARK: - Auth

    func login(login: String, password: String) async throws -> Session {
        let body = ["login": login, "password": password]
        var request = URLRequest(url: url(["auth", "login"]))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await session.data(for: request)
        try check(response, data)

        let session = try decoder.decode(Session.self, from: data)
        store.save(session)
        return session
    }

    func logout() async {
        guard let refreshToken = store.load()?.refreshToken else {
            store.clear()
            return
        }
        var request = authorizedRequest(url(["auth", "logout"]))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONEncoder().encode(["refreshToken": refreshToken])
        _ = try? await session.data(for: request)
        store.clear()
    }

    func heartbeat() async {
        var request = authorizedRequest(url(["auth", "heartbeat"]))
        request.httpMethod = "POST"
        _ = try? await send(request)
    }

    /// Renews the session, at most once at a time.
    ///
    /// `stale` is the access token the caller was holding. If it no longer
    /// matches what is stored, another caller already renewed and this one just
    /// takes the new session — which is the whole point of the guard.
    func renewSession(stale: String?) async -> Session? {
        if let current = store.load(), let stale, current.accessToken != stale {
            return current
        }
        if let existing = refreshTask {
            return await existing.value
        }

        let task = Task<Session?, Never> { [store, session, baseURL] in
            guard let refreshToken = store.load()?.refreshToken else { return nil }
            var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
            components.path = ApiConfig.basePath + "/auth/refresh"

            var request = URLRequest(url: components.url!)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue(ApiConfig.clientKind, forHTTPHeaderField: ApiConfig.clientHeader)
            request.setValue(ApiConfig.userAgent, forHTTPHeaderField: "User-Agent")
            request.httpBody = try? JSONEncoder().encode(["refreshToken": refreshToken])

            guard let (data, response) = try? await session.data(for: request),
                  let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                  let renewed = try? JSONDecoder().decode(Session.self, from: data) else {
                store.clear()
                return nil
            }
            store.save(renewed)
            return renewed
        }
        refreshTask = task
        let result = await task.value
        refreshTask = nil
        if result == nil { sessionEndedContinuation.yield(()) }
        return result
    }

    // MARK: - Content

    func search(query: String, providers: Set<String> = []) async throws -> SearchResponse {
        var items = [URLQueryItem(name: "q", value: query)]
        if !providers.isEmpty {
            items.append(URLQueryItem(name: "providers", value: providers.sorted().joined(separator: ",")))
        }
        return try await get(url(["search"], query: items))
    }

    func item(provider: String, url itemUrl: String, title: String, mediaType: String?, posterUrl: String?) async throws -> ItemDetail {
        var query = [
            URLQueryItem(name: "provider", value: provider),
            URLQueryItem(name: "url", value: itemUrl),
            URLQueryItem(name: "title", value: title),
        ]
        if let mediaType { query.append(URLQueryItem(name: "mediaType", value: mediaType)) }
        if let posterUrl { query.append(URLQueryItem(name: "posterUrl", value: posterUrl)) }

        let data = try await send(authorizedRequest(url(["item"], query: query)))
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw StreamHubError(status: 0, message: "The server sent something unreadable.")
        }

        let resolvedTitle = object["title"] as? String ?? title
        let poster = object["posterUrl"] as? String ?? posterUrl

        // Discriminated on which key is present, not on a type field — the
        // response carries no type field at all.
        if let seasons = object["seasons"] {
            let decoded = try decoder.decode([SeasonRef].self, from: JSONSerialization.data(withJSONObject: seasons))
            return .seasons(title: resolvedTitle, posterUrl: poster, seasons: decoded)
        }
        if let episodes = object["episodes"] as? [String] {
            return .episodes(
                title: resolvedTitle,
                posterUrl: poster,
                // The season's own URL, which the episode and source calls
                // need. Providers name it differently; the Android client reads
                // the same two keys.
                sourceUrl: (object["seasonUrl"] as? String) ?? (object["detailUrl"] as? String),
                episodes: episodes
            )
        }
        if let streams = object["streams"] {
            let decoded = try decoder.decode([RawStream].self, from: JSONSerialization.data(withJSONObject: streams))
            return .movie(title: resolvedTitle, posterUrl: poster, streams: decoded)
        }
        throw StreamHubError(status: 0, message: "This title has nothing to play.")
    }

    func episodes(provider: String, sourceUrl: String) async throws -> [String] {
        struct Response: Codable { var episodes: [String] = [] }
        let response: Response = try await get(url(["episodes"], query: [
            URLQueryItem(name: "provider", value: provider),
            URLQueryItem(name: "sourceUrl", value: sourceUrl),
        ]))
        return response.episodes
    }

    /// `/api/sources` is NDJSON, written as each health probe finishes.
    ///
    /// Streamed rather than collected: the first source usually lands seconds
    /// before the last, and waiting for all of them would leave the screen
    /// empty for no reason. Sources that fail are simply never emitted.
    nonisolated func sources(
        provider: String,
        seasonUrl: String,
        episode: String,
        preferred: String?
    ) -> AsyncThrowingStream<Source, Error> {
        // The parameter is sourceUrl, not url: /api/item takes url and these
        // take sourceUrl. Sending the wrong one is a 400 that reads on screen
        // as "no playable source", which is how this was found.
        var query = [
            URLQueryItem(name: "provider", value: provider),
            URLQueryItem(name: "sourceUrl", value: seasonUrl),
            URLQueryItem(name: "episode", value: episode),
        ]
        if let preferred, !preferred.isEmpty {
            query.append(URLQueryItem(name: "preferredLabel", value: preferred))
        }
        return ndjson(authorizedRequest(url(["sources"], query: query)))
    }

    nonisolated func checkSources(provider: String, streams: [RawStream]) -> AsyncThrowingStream<Source, Error> {
        var request = authorizedRequest(url(["check-sources"]))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONEncoder().encode([
            "provider": AnyCodable(provider),
            "streams": AnyCodable(streams),
        ])
        return ndjson(request)
    }

    /// The cleaned manifest. Ads are already cut and segments point straight at
    /// the CDN, which is what keeps native playback ad-free — AVPlayer cannot
    /// run the browser's segment filter.
    nonisolated func manifestURL(target: String) -> URL {
        url(["manifest"], query: [URLQueryItem(name: "target", value: target)])
    }

    nonisolated func posterURL(target: String) -> URL? {
        guard !target.isEmpty else { return nil }
        return url(["poster"], query: [URLQueryItem(name: "target", value: target)])
    }

    func adCuts(target: String) async throws -> AdCuts {
        try await get(url(["ad-cuts"], query: [URLQueryItem(name: "target", value: target)]))
    }

    // MARK: - Library

    func favorites() async throws -> [Favorite] {
        struct Response: Codable { var favorites: [Favorite] = [] }
        return try await get(url(["me", "favorites"]) as URL, decode: Response.self).favorites
    }

    func addFavorite(_ favorite: NewFavorite) async throws {
        var request = authorizedRequest(url(["me", "favorites"]))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(favorite)
        _ = try await send(request)
    }

    func removeFavorite(id: String) async throws {
        var request = authorizedRequest(url(["me", "favorites", id]))
        request.httpMethod = "DELETE"
        _ = try await send(request)
    }

    func continueWatching() async throws -> [ContinueItem] {
        struct Response: Codable { var items: [ContinueItem] = [] }
        return try await get(url(["me", "continue-watching"]) as URL, decode: Response.self).items
    }

    func history() async throws -> [WatchHistoryEntry] {
        struct Response: Codable { var history: [WatchHistoryEntry] = [] }
        return try await get(url(["me", "history"]) as URL, decode: Response.self).history
    }

    func progress(providerKey: String?, itemUrl: String?) async throws -> [WatchProgress] {
        var query: [URLQueryItem] = []
        if let providerKey { query.append(URLQueryItem(name: "providerKey", value: providerKey)) }
        if let itemUrl { query.append(URLQueryItem(name: "itemUrl", value: itemUrl)) }
        struct Response: Codable { var progress: [WatchProgress] = [] }
        return try await get(url(["me", "progress"], query: query) as URL, decode: Response.self).progress
    }

    func putProgress(_ update: ProgressUpdate) async throws {
        var request = authorizedRequest(url(["me", "progress"]))
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(update)
        _ = try await send(request)
    }

    func sourcePreference(providerKey: String, title: String, mediaType: String) async throws -> String? {
        struct Response: Codable { var sourceLabel: String? }
        let response: Response = try await get(url(["me", "source-preference"], query: [
            URLQueryItem(name: "providerKey", value: providerKey),
            URLQueryItem(name: "title", value: title),
            URLQueryItem(name: "mediaType", value: mediaType),
        ]))
        return response.sourceLabel
    }

    func rememberSourcePreference(providerKey: String, title: String, mediaType: String, sourceLabel: String) async {
        var request = authorizedRequest(url(["me", "source-preference"]))
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONEncoder().encode([
            "providerKey": providerKey, "title": title,
            "mediaType": mediaType, "sourceLabel": sourceLabel,
        ])
        _ = try? await send(request)
    }

    func sessions() async throws -> [DeviceSession] {
        struct Response: Codable { var sessions: [DeviceSession] = [] }
        return try await get(url(["me", "sessions"]) as URL, decode: Response.self).sessions
    }

    // MARK: - Approving a television

    /// Looks up what is asking, so it can be named before anything is granted.
    ///
    /// Throws 404 for a code that has expired, has been used, or never existed.
    /// One answer for all three, because they mean the same thing to somebody
    /// holding a phone: fetch a fresh code off the television.
    func pendingDevice(code: String) async throws -> PendingDevice {
        try await get(url(["auth", "device", "pending"], query: [
            URLQueryItem(name: "code", value: UserCode.normalise(code)),
        ]) as URL, decode: PendingDevice.self)
    }

    /// Hands this account to the television holding `code`.
    func approveDevice(code: String) async throws {
        try await decideDevice(action: "approve", code: code)
    }

    /// Says it was not mine. The code stops working.
    func denyDevice(code: String) async throws {
        try await decideDevice(action: "deny", code: code)
    }

    private func decideDevice(action: String, code: String) async throws {
        var request = authorizedRequest(url(["auth", "device", action]))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(["code": UserCode.normalise(code)])
        _ = try await send(request)
    }

    // MARK: - Plumbing

    private nonisolated var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        return decoder
    }

    private func get<T: Decodable>(_ url: URL) async throws -> T {
        try await get(url, decode: T.self)
    }

    private func get<T: Decodable>(_ url: URL, decode: T.Type) async throws -> T {
        let data = try await send(authorizedRequest(url))
        return try decoder.decode(T.self, from: data)
    }

    /// Sends a request, renewing once on a 401 and replaying it.
    ///
    /// Once, not in a loop: if the renewed token is also rejected the problem is
    /// not the token, and retrying would spin against the server.
    private func send(_ request: URLRequest) async throws -> Data {
        let stale = store.load()?.accessToken
        let (data, response) = try await session.data(for: request)

        guard let http = response as? HTTPURLResponse else {
            throw StreamHubError(status: 0, message: "The server sent something unreadable.")
        }
        if http.statusCode != 401 {
            try check(response, data)
            return data
        }

        guard let renewed = await renewSession(stale: stale) else {
            throw StreamHubError(status: 401, message: "Your session has ended. Sign in again.")
        }
        var retried = request
        retried.setValue("Bearer \(renewed.accessToken)", forHTTPHeaderField: "Authorization")
        let (retryData, retryResponse) = try await session.data(for: retried)
        try check(retryResponse, retryData)
        return retryData
    }

    private nonisolated func check(_ response: URLResponse, _ data: Data) throws {
        guard let http = response as? HTTPURLResponse else {
            throw StreamHubError(status: 0, message: "The server sent something unreadable.")
        }
        guard (200..<300).contains(http.statusCode) else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw StreamHubError(status: http.statusCode, message: message ?? "The server refused that request.")
        }
    }

    /// Parses a newline-delimited JSON body incrementally.
    private nonisolated func ndjson(_ request: URLRequest) -> AsyncThrowingStream<Source, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let (bytes, response) = try await URLSession.shared.bytes(for: request)
                    guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                        continuation.finish(throwing: StreamHubError(status: 0, message: "Could not load sources."))
                        return
                    }
                    for try await line in bytes.lines {
                        guard !line.isEmpty, let data = line.data(using: .utf8) else { continue }
                        if let source = try? JSONDecoder().decode(Source.self, from: data) {
                            continuation.yield(source)
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

/// Minimal type erasure so a mixed-shape body can be encoded without a bespoke
/// struct for each one.
struct AnyCodable: Encodable {
    private let encode: (Encoder) throws -> Void

    init<T: Encodable>(_ value: T) {
        encode = { encoder in try value.encode(to: encoder) }
    }

    func encode(to encoder: Encoder) throws {
        try encode(encoder)
    }
}
