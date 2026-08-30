import AVKit
import SwiftUI

/// How playback ended, so the screen that opened the player knows what to do
/// next.
enum PlayerExit: Equatable {
    case done
    case playNext(String)
}

/// Playback.
///
/// `AVPlayerViewController`, not a hand-built player. It brings the transport,
/// the gestures, AirPlay, Picture in Picture, background audio and the
/// lock-screen and Control Centre integration with no work at all, and every
/// one of those would otherwise be a permanent maintenance cost for a worse
/// result.
///
/// What this view adds is the three things the system controller cannot know:
/// where to resume, how to report progress back to the account, and what should
/// happen once the episode runs out.
struct PlayerView: View {
    let request: PlaybackRequest
    var onExit: (PlayerExit) -> Void = { _ in }

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var player: AVPlayer?
    @State private var reporter: ProgressReporter?
    @State private var ended = false

    // ── mid-stream failure recovery ──────────────────────────────────────
    // A fatal fault with nobody listening is a black rectangle that ignores
    // every tap. The ladder retries in place, then relays the stream through
    // the server, then admits defeat visibly — the same order as Android.
    @State private var recovery = RecoveryLadder()
    @State private var recoveringLabel: String?
    @State private var fatalMessage: String?
    @State private var lastGoodPosition: Double = 0
    @State private var lastFaultAt: Date?

    var body: some View {
        ZStack {
            if let player {
                PlayerContainer(player: player, onDone: { leave(.done) })
            } else {
                ZStack {
                    Color.black
                    ProgressView().tint(.white)
                }
            }

            if ended, let next = request.nextEpisodeLabel {
                UpNextPrompt(
                    label: next,
                    onPlay: { leave(.playNext(next)) },
                    onDismiss: { leave(.done) }
                )
            }

            if let recoveringLabel, fatalMessage == nil {
                Text(recoveringLabel)
                    .font(.callout.weight(.medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.black.opacity(0.6), in: .capsule)
            }

            if let fatalMessage {
                PlaybackErrorPrompt(
                    message: fatalMessage,
                    onRetry: { retryFromPanel() },
                    onDismiss: { leave(.done) }
                )
            }
        }
        .background(.black)
        .task { await start() }
        .onDisappear { finish() }
        // Reaching the end is the one moment the system player has nothing to
        // say. Left alone it holds the last frame, which is a full-screen
        // sheet whose only remaining control is a swipe.
        // A fault mid-stream is a fatal stop AVPlayer announces but never
        // recovers from on its own; every one climbs the ladder.
        .onReceive(NotificationCenter.default.publisher(for: .AVPlayerItemFailedToPlayToEndTime)) { notification in
            let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            fault(error?.localizedDescription ?? "Playback failed")
        }
        .onReceive(NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime)) { _ in
            guard !ended else { return }
            ended = true
            let duration = player?.currentItem?.duration.seconds ?? 0
            let end = duration.isFinite ? duration : 0
            // Reporting the end position is what marks the episode complete,
            // which is what moves Up Next on to the following one.
            reporter?.report(position: end, duration: end, event: "ended")
            // Nothing to ask about, so do not ask.
            if request.nextEpisodeLabel == nil { leave(.done) }
        }
    }

    private func leave(_ exit: PlayerExit) {
        onExit(exit)
        dismiss()
    }

    private func start() async {
        // The cleaned manifest, not the raw source: ads are already cut and
        // segments point straight at the CDN. AVPlayer cannot run the browser's
        // segment filter, so this endpoint is what keeps native playback
        // ad-free.
        let url = model.api.manifestURL(target: request.directUrl)
        let asset = AVURLAsset(asset: url, headers: model.api.authorizedRequest(url).allHTTPHeaderFields ?? [:])
        let item = AVPlayerItem(asset: asset)
        let created = AVPlayer(playerItem: item)

        if request.resumeAtSeconds > 0 {
            await created.seek(to: CMTime(seconds: Double(request.resumeAtSeconds), preferredTimescale: 1))
        }

        let reporter = ProgressReporter(api: model.api, request: request)
        created.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 5, preferredTimescale: 1),
            queue: .main
        ) { time in
            let duration = created.currentItem?.duration.seconds ?? 0
            reporter.report(
                position: time.seconds,
                duration: duration.isFinite ? duration : 0,
                event: "progress"
            )
        }

        self.reporter = reporter
        self.player = created
        self.lastGoodPosition = Double(request.resumeAtSeconds)
        created.play()

        // Playback continues with the screen locked, which needs the session
        // category set before it starts rather than after.
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)

        await monitor(created)
    }

    /// The watchdog. AVPlayer reports some deaths (the failed-to-play
    /// notification) but not all: an item can fail while loading, and a
    /// stream can sit buffering forever against a source that stopped
    /// answering — the same black screen to the person holding the phone.
    private func monitor(_ player: AVPlayer) async {
        var stallSeconds = 0.0
        var stallAnchor = -1.0
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard !ended else { continue }

            if player.currentItem?.status == .failed, fatalMessage == nil, recoveringLabel == nil {
                fault(player.currentItem?.error?.localizedDescription ?? "Playback failed")
                continue
            }

            let position = player.currentTime().seconds
            if player.rate > 0, position > lastGoodPosition {
                // Healthy playback clears the banner, remembers where the
                // picture is, and after a minute forgives the ladder.
                recoveringLabel = nil
                lastGoodPosition = position
                if let at = lastFaultAt, Date().timeIntervalSince(at) > 60 {
                    recovery.reset()
                    lastFaultAt = nil
                }
            }

            if player.timeControlStatus == .waitingToPlayAtSpecifiedRate, fatalMessage == nil {
                if position == stallAnchor {
                    stallSeconds += 2
                } else {
                    stallAnchor = position
                    stallSeconds = 0
                }
                if stallSeconds >= 45 {
                    stallSeconds = 0
                    fault("Stream stalled")
                }
            } else {
                stallSeconds = 0
                stallAnchor = -1
            }
        }
    }

    /// One rung up the ladder, carrying the position so a recovered stream
    /// resumes where the picture died rather than at zero.
    private func fault(_ message: String) {
        guard let player, fatalMessage == nil else { return }
        lastFaultAt = Date()
        let current = player.currentTime().seconds
        let position = max(lastGoodPosition, current.isFinite ? current : 0)
        switch recovery.next() {
        case .retry:
            recoveringLabel = "Reconnecting…"
            replaceItem(on: player, tier: recovery.tier, position: position)
        case .switchToRelay:
            recoveringLabel = "Switching to the server relay…"
            replaceItem(on: player, tier: 1, position: position)
        case .giveUp:
            recoveringLabel = nil
            fatalMessage = message
        }
    }

    private func replaceItem(on player: AVPlayer, tier: Int, position: Double) {
        let url = tier == 0
            ? model.api.manifestURL(target: request.directUrl)
            : model.api.streamURL(target: request.directUrl)
        let asset = AVURLAsset(asset: url, headers: model.api.authorizedRequest(url).allHTTPHeaderFields ?? [:])
        player.replaceCurrentItem(with: AVPlayerItem(asset: asset))
        if position > 0 {
            player.seek(to: CMTime(seconds: position, preferredTimescale: 1))
        }
        player.play()
    }

    private func retryFromPanel() {
        guard let player else { return }
        fatalMessage = nil
        recovery.reset()
        recoveringLabel = "Reconnecting…"
        replaceItem(on: player, tier: 0, position: lastGoodPosition)
    }

    private func finish() {
        guard let player else { return }
        let duration = player.currentItem?.duration.seconds ?? 0
        reporter?.report(
            position: player.currentTime().seconds,
            duration: duration.isFinite ? duration : 0,
            event: "pause"
        )
        player.pause()
    }
}

/// Writes watch progress back to the server.
///
/// `progressPercent` and `isCompleted` are derived server-side from what is sent
/// here, so this only reports where playback actually is. The same shape the
/// Android clients send, deliberately: what the devices report has to be
/// identical or Up Next disagrees with itself depending on where an episode was
/// last touched.
@MainActor
final class ProgressReporter {
    private let api: APIClient
    private let request: PlaybackRequest
    private var lastBucket = -1

    private let reportEverySeconds = 15

    init(api: APIClient, request: PlaybackRequest) {
        self.api = api
        self.request = request
        Task {
            // The server probes this source first next time, so the one
            // actually chosen tends to be ready before the rest.
            await api.rememberSourcePreference(
                providerKey: request.providerKey,
                title: request.title,
                mediaType: request.mediaType,
                sourceLabel: request.sourceLabel
            )
        }
    }

    func report(position: Double, duration: Double, event: String) {
        guard position.isFinite, position >= 0 else { return }
        let seconds = Int(position)
        let bucket = seconds / reportEverySeconds
        // Ticking every second would be a request per second for no benefit;
        // the shelf only needs to be roughly right.
        if event == "progress", bucket == lastBucket { return }
        lastBucket = bucket

        let update = ProgressUpdate(
            providerKey: request.providerKey,
            mediaType: request.mediaType,
            title: request.title,
            posterUrl: request.posterUrl,
            itemUrl: request.itemUrl,
            seasonUrl: request.seasonUrl,
            seasonLabel: request.seasonLabel,
            episodeLabel: request.episodeLabel,
            sourceLabel: request.sourceLabel,
            durationSeconds: duration > 0 ? Int(duration) : (request.durationSeconds ?? 0),
            positionSeconds: seconds,
            event: event
        )
        Task { try? await api.putProgress(update) }
    }
}

/// The end of the recovery ladder: a real error screen with a retry, never a
/// silent black rectangle.
private struct PlaybackErrorPrompt: View {
    let message: String
    let onRetry: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            VStack(spacing: 2) {
                Text("Playback stopped")
                    .font(.title3.weight(.semibold))
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
            }

            HStack(spacing: 12) {
                Button {
                    onRetry()
                } label: {
                    Label("Try again", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .buttonBorderShape(.capsule)

                Button("Back", action: onDismiss)
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .buttonBorderShape(.capsule)
                    .tint(.secondary)
            }
        }
        .padding(28)
        .frame(maxWidth: 360)
        .background(.regularMaterial, in: .rect(cornerRadius: 20))
        .shadow(radius: 30)
    }
}

/// Offered when the episode ends.
///
/// Two choices and no countdown. A countdown that starts the next episode
/// unless it is stopped is the behaviour that keeps playing to a room nobody
/// is in; being asked costs one tap.
private struct UpNextPrompt: View {
    let label: String
    let onPlay: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            VStack(spacing: 2) {
                Text("Up Next")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text("Episode \(label)")
                    .font(.title3.weight(.semibold))
            }

            HStack(spacing: 12) {
                Button {
                    onPlay()
                } label: {
                    Label("Play", systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .buttonBorderShape(.capsule)

                Button("Done", action: onDismiss)
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .buttonBorderShape(.capsule)
                    .tint(.secondary)
            }
        }
        .padding(28)
        .frame(maxWidth: 360)
        .background(.regularMaterial, in: .rect(cornerRadius: 20))
        .shadow(radius: 30)
    }
}

/// The system player, hosted.
private struct PlayerContainer: UIViewControllerRepresentable {
    let player: AVPlayer
    let onDone: () -> Void

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.allowsPictureInPicturePlayback = true
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onDone: onDone) }

    final class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        private let onDone: () -> Void
        init(onDone: @escaping () -> Void) { self.onDone = onDone }
    }
}

private extension AVURLAsset {
    /// AVPlayer has no header API of its own; options are the only way to put a
    /// bearer token on the manifest request, and the second request a master
    /// playlist causes carries them too.
    convenience init(asset url: URL, headers: [String: String]) {
        self.init(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
    }
}
