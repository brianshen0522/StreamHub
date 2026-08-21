import Foundation

/// What the server pushes down `/api/realtime`.
enum RealtimeEvent: Sendable {
    case favorites(action: String)
    case progress(action: String, historyChanged: Bool)
    case receivers([CastReceiver])
    case command(from: String?, fromName: String?, command: CastCommand)
    case unknown(String)
}

/// The live connection: library sync in, remote-control commands both ways.
///
/// Reconnects on its own with exponential backoff, and knows the two close
/// codes that mean something specific — 4002 is an expired access token, which
/// has to be renewed *before* reconnecting or the client loops against the
/// server with a token it already knows is dead.
actor RealtimeClient {

    private let endpoint: URL
    private let store: SessionStore
    private let renew: @Sendable (String?) async -> Session?

    private var task: URLSessionWebSocketTask?
    private var running = false

    private(set) var sessionId: String?

    private let events: AsyncStream<RealtimeEvent>
    private let continuation: AsyncStream<RealtimeEvent>.Continuation

    private static let closeTokenExpired = 4002
    private static let closeUnauthorized = 4003

    init(baseURL: URL, store: SessionStore, renew: @escaping @Sendable (String?) async -> Session?) {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        components.scheme = (components.scheme == "https") ? "wss" : "ws"
        components.path = "/api/realtime"
        self.endpoint = components.url!
        self.store = store
        self.renew = renew

        var continuation: AsyncStream<RealtimeEvent>.Continuation!
        self.events = AsyncStream(bufferingPolicy: .bufferingNewest(64)) { continuation = $0 }
        self.continuation = continuation
    }

    nonisolated func stream() -> AsyncStream<RealtimeEvent> { events }

    func start() {
        guard !running else { return }
        running = true
        Task { await runForever() }
    }

    func stop() {
        running = false
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
    }

    // MARK: - Sending

    /// Tells the account's other devices what this one is playing, or that it is
    /// idle. Sending this at all is what makes a device appear as a cast target.
    func publishPlayback(_ state: CastPlaybackState?) {
        var frame: [String: Any] = ["type": "playback"]
        // The key must be present even when null. A frame that simply omits it
        // still registers the receiver, but the same omission on a real state
        // would silently drop the position.
        if let state, let data = try? JSONEncoder().encode(state),
           let object = try? JSONSerialization.jsonObject(with: data) {
            frame["state"] = object
        } else {
            frame["state"] = NSNull()
        }
        send(frame)
    }

    func sendCommand(to sessionId: String, command: CastCommand) {
        send(["type": "command", "to": sessionId, "command": encode(command)])
    }

    private func encode(_ command: CastCommand) -> [String: Any] {
        switch command {
        case .pause: return ["action": "pause"]
        case .resume: return ["action": "resume"]
        case .stop: return ["action": "stop"]
        case .next: return ["action": "next"]
        case .seek(let positionMs): return ["action": "seek", "positionMs": positionMs]
        case .play(let request):
            guard let data = try? JSONEncoder().encode(request),
                  let object = try? JSONSerialization.jsonObject(with: data) else {
                return ["action": "play"]
            }
            return ["action": "play", "playback": object]
        }
    }

    private func send(_ frame: [String: Any]) {
        guard let task,
              let data = try? JSONSerialization.data(withJSONObject: frame),
              let text = String(data: data, encoding: .utf8) else { return }
        task.send(.string(text)) { _ in }
    }

    // MARK: - Connection

    private func runForever() async {
        var attempt = 0
        while running {
            let outcome = await runOnce()
            sessionId = nil

            if outcome.unauthorized { return }
            if outcome.tokenExpired {
                attempt = 0
                // Renew first. Reconnecting with the same dead token just loops.
                if await renew(outcome.token) == nil { return }
                continue
            }
            if outcome.sawReady { attempt = 0 }
            let delay = min(pow(2.0, Double(attempt)), 30)
            attempt += 1
            try? await Task.sleep(for: .seconds(delay))
        }
    }

    private struct Outcome {
        var sawReady = false
        var tokenExpired = false
        var unauthorized = false
        var token: String?
    }

    private func runOnce() async -> Outcome {
        guard let token = store.load()?.accessToken, !token.isEmpty else {
            return Outcome(unauthorized: true)
        }
        var outcome = Outcome(token: token)

        var request = URLRequest(url: endpoint)
        request.setValue(ApiConfig.clientKind, forHTTPHeaderField: ApiConfig.clientHeader)
        request.setValue(ApiConfig.userAgent, forHTTPHeaderField: "User-Agent")

        let task = URLSession.shared.webSocketTask(with: request)
        self.task = task
        task.resume()

        // Auth travels in the first frame rather than the query string, so
        // access tokens stay out of URLs and proxy access logs. The server
        // allows five seconds for it.
        send(["type": "auth", "token": token])

        while true {
            do {
                let message = try await task.receive()
                guard case .string(let text) = message,
                      let data = text.data(using: .utf8),
                      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let type = object["type"] as? String else { continue }

                if type == "ready" {
                    outcome.sawReady = true
                    sessionId = object["sessionId"] as? String
                    // The handshake carries the receivers already connected, so
                    // a client that opens second still sees the television
                    // without waiting for it to report again.
                    if let list = receivers(from: object) {
                        continuation.yield(.receivers(list))
                    }
                    continue
                }
                if let event = decode(type: type, object: object) {
                    continuation.yield(event)
                }
            } catch {
                let code = task.closeCode.rawValue
                outcome.tokenExpired = (code == Self.closeTokenExpired)
                outcome.unauthorized = (code == Self.closeUnauthorized)
                self.task = nil
                return outcome
            }
        }
    }

    private func receivers(from object: [String: Any]) -> [CastReceiver]? {
        guard let raw = object["receivers"],
              let data = try? JSONSerialization.data(withJSONObject: raw),
              let list = try? JSONDecoder().decode([CastReceiver].self, from: data) else { return nil }
        return list
    }

    private func decode(type: String, object: [String: Any]) -> RealtimeEvent? {
        switch type {
        case "favorites":
            return .favorites(action: object["action"] as? String ?? "")
        case "progress":
            return .progress(
                action: object["action"] as? String ?? "",
                historyChanged: object["history"] as? Bool ?? false
            )
        case "receivers":
            return .receivers(receivers(from: object) ?? [])
        case "command":
            guard let raw = object["command"] as? [String: Any],
                  let command = decodeCommand(raw) else { return .unknown(type) }
            return .command(
                from: object["from"] as? String,
                fromName: object["fromName"] as? String,
                command: command
            )
        default:
            return .unknown(type)
        }
    }

    private func decodeCommand(_ raw: [String: Any]) -> CastCommand? {
        switch raw["action"] as? String {
        case "pause": return .pause
        case "resume": return .resume
        case "stop": return .stop
        case "next": return .next
        case "seek":
            guard let position = raw["positionMs"] as? Int else { return nil }
            return .seek(positionMs: position)
        case "play":
            guard let playback = raw["playback"],
                  let data = try? JSONSerialization.data(withJSONObject: playback),
                  let request = try? JSONDecoder().decode(CastPlayRequest.self, from: data) else { return nil }
            return .play(request)
        default:
            // An action this build does not know is ignored, not an error: a
            // newer server must not be able to drop an older client's socket.
            return nil
        }
    }
}
