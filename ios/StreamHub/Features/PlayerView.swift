import AVKit
import SwiftUI

/// Playback.
///
/// `AVPlayerViewController`, not a hand-built player. It brings the transport,
/// the gestures, AirPlay, Picture in Picture, background audio and the
/// lock-screen and Control Centre integration with no work at all, and every
/// one of those would otherwise be a permanent maintenance cost for a worse
/// result.
///
/// What this view adds is the two things the system controller cannot know:
/// where to resume, and reporting progress back to the account.
struct PlayerView: View {
    let request: PlaybackRequest

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var player: AVPlayer?
    @State private var reporter: ProgressReporter?

    var body: some View {
        Group {
            if let player {
                PlayerContainer(player: player, onDone: { dismiss() })
            } else {
                ZStack {
                    Color.black
                    ProgressView().tint(.white)
                }
            }
        }
        .background(.black)
        .task { await start() }
        .onDisappear { finish() }
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
        created.play()

        // Playback continues with the screen locked, which needs the session
        // category set before it starts rather than after.
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
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
