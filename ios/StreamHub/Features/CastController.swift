import SwiftUI

/// Which device this phone is driving, and how to drive it.
///
/// Being connected to a television is app-wide state rather than something the
/// player owns, and that is the whole design: pick a device once and *every*
/// Play goes there until you say otherwise. The alternative — a "cast this"
/// action on each Play — asks the same question every time and lets the phone
/// and the television end up playing different things.
///
/// Matches the Android phone client exactly, because the two drive the same
/// televisions.
@Observable
@MainActor
final class CastController {

    private(set) var receivers: [CastReceiver] = []
    private(set) var targetId: String?

    private var realtime: RealtimeClient?

    /// The device being driven. Derived rather than stored, so a television that
    /// drops off the socket cannot leave the phone showing controls for
    /// something that is no longer listening.
    var target: CastReceiver? {
        guard let targetId else { return nil }
        return receivers.first { $0.sessionId == targetId }
    }

    /// A device was chosen but is no longer reachable. Distinct from simply not
    /// casting: the phone has to say the television went away rather than
    /// quietly dropping back to local playback.
    var lost: Bool {
        targetId != nil && target == nil
    }

    func start(model: AppModel) async {
        guard realtime == nil else { return }
        let client = RealtimeClient(
            baseURL: ApiConfig.serverURL,
            store: model.store,
            renew: { [api = model.api] stale in await api.renewSession(stale: stale) }
        )
        realtime = client
        await client.start()

        // Collected for the life of the app rather than per screen: the cast
        // button has to know whether a television is there before anyone opens
        // a player, and this subscription is what keeps the socket alive.
        for await event in client.stream() {
            if case .receivers(let list) = event {
                receivers = list
            }
        }
    }

    var televisions: [CastReceiver] {
        receivers.filter(\.isTelevision)
    }

    func connect(to receiver: CastReceiver) {
        targetId = receiver.sessionId
    }

    /// Stops driving the device but leaves it playing. Walking away from the
    /// remote is not the same as wanting the television to go dark.
    func disconnect() {
        targetId = nil
    }

    func stopAndDisconnect() {
        if let targetId { send(.stop, to: targetId) }
        targetId = nil
    }

    @discardableResult
    func play(_ request: PlaybackRequest) -> Bool {
        guard let targetId else { return false }
        send(.play(CastPlayRequest(
            streamUrl: request.directUrl,
            provider: request.providerKey,
            itemUrl: request.itemUrl,
            title: request.title,
            subtitle: [request.seasonLabel, request.sourceLabel]
                .compactMap { $0 }
                .joined(separator: " · "),
            posterUrl: request.posterUrl,
            episodeLabel: request.episodeLabel,
            episodeUrl: request.seasonUrl,
            nextEpisodeLabel: request.nextEpisodeLabel,
            prevEpisodeLabel: request.prevEpisodeLabel,
            // The receiver resumes where this account left off, so handing a
            // title to the television lands where it would have on the phone.
            positionMs: request.resumeAtSeconds * 1000
        )), to: targetId)
        return true
    }

    func pause() { if let targetId { send(.pause, to: targetId) } }
    func resume() { if let targetId { send(.resume, to: targetId) } }
    func next() { if let targetId { send(.next, to: targetId) } }
    func previous() { if let targetId { send(.previous, to: targetId) } }
    func seek(toMs positionMs: Int) { if let targetId { send(.seek(positionMs: positionMs), to: targetId) } }

    private func send(_ command: CastCommand, to sessionId: String) {
        guard let realtime else { return }
        Task { await realtime.sendCommand(to: sessionId, command: command) }
    }
}
