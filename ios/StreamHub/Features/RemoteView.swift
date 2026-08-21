import SwiftUI

/// The phone as a remote.
///
/// Everything here is a picture of state the television owns, which is what
/// makes the two hard parts hard. The reported position arrives about once a
/// second, so the bar would tick in visible steps unless it is advanced locally
/// between reports; and a scrub has to keep showing where the thumb was left
/// until the television confirms it got there, or the bar snaps back to a stale
/// position for a second and reads as a failed seek.
struct RemoteView: View {
    @Environment(AppModel.self) private var model
    @Environment(CastController.self) private var cast
    @Environment(\.dismiss) private var dismiss

    @State private var scrubbing: Double?
    @State private var pendingSeek: Double?
    @State private var reported: Double = 0
    @State private var reportedAt = Date()

    var body: some View {
        NavigationStack {
            if let target = cast.target {
                content(target)
                    .navigationTitle(target.deviceName)
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Done") { dismiss() }
                        }
                    }
            } else {
                ContentUnavailableView(
                    "Not Connected",
                    systemImage: "airplayvideo",
                    description: Text("The device is no longer reachable.")
                )
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Done") { dismiss() }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func content(_ target: CastReceiver) -> some View {
        let state = target.state
        let duration = Double(state?.durationMs ?? 0) / 1000
        let live = livePosition(state)
        let shown = scrubbing ?? pendingSeek ?? live

        VStack(spacing: 24) {
            RemoteImage(url: model.posterURL(state?.posterUrl))
                .aspectRatio(2 / 3, contentMode: .fit)
                .frame(maxHeight: 320)
                .clipShape(.rect(cornerRadius: 14))
                .shadow(radius: 18, y: 8)
                .padding(.top, 12)

            VStack(spacing: 4) {
                Text(state?.title ?? "Nothing Playing")
                    .font(.title3.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                let detail = [state?.episodeLabel.map { "Episode \($0)" }, state?.subtitle]
                    .compactMap { $0 }
                    .joined(separator: " · ")
                if !detail.isEmpty {
                    Text(detail)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            VStack(spacing: 4) {
                Slider(
                    value: Binding(
                        get: { min(shown, max(duration, 1)) },
                        set: { scrubbing = $0 }
                    ),
                    in: 0...max(duration, 1),
                    onEditingChanged: { editing in
                        guard !editing, let position = scrubbing else { return }
                        scrubbing = nil
                        pendingSeek = position
                        cast.seek(toMs: Int(position * 1000))
                    }
                )
                .disabled(state == nil || cast.lost)

                HStack {
                    Text(timeLabel(shown))
                    Spacer()
                    Text(timeLabel(duration))
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }

            HStack(spacing: 36) {
                // Every control here is an icon, so each carries its own label:
                // an unlabelled icon button is silent under VoiceOver, which
                // makes the remote unusable rather than merely awkward.
                Button {
                    cast.seek(toMs: Int(max(live - 10, 0) * 1000))
                } label: {
                    Image(systemName: "gobackward.10").font(.system(size: 28))
                }
                .accessibilityLabel("Back 10 Seconds")

                Button {
                    if state?.paused == true { cast.resume() } else { cast.pause() }
                } label: {
                    Image(systemName: state?.paused == true ? "play.fill" : "pause.fill")
                        .font(.system(size: 34))
                        .frame(width: 72, height: 72)
                        .background(Color.streamHubAccent, in: .circle)
                        .foregroundStyle(.white)
                }
                .accessibilityLabel(state?.paused == true ? "Resume" : "Pause")
                // The bar behind this sheet carries the same control, so the
                // two need telling apart by something other than their glyph.
                .accessibilityIdentifier("remotePlayPause")

                Button {
                    cast.seek(toMs: Int((live + 10) * 1000))
                } label: {
                    Image(systemName: "goforward.10").font(.system(size: 28))
                }
                .accessibilityLabel("Forward 10 Seconds")
            }
            .foregroundStyle(.primary)
            .disabled(state == nil || cast.lost)

            Spacer()

            HStack(spacing: 12) {
                Button("Stop", role: .destructive) {
                    cast.stopAndDisconnect()
                    dismiss()
                }
                .buttonStyle(.bordered)
                .controlSize(.large)

                // Neutral, not accented. Red already means the transport and
                // means Stop; a third red control on one screen and none of
                // them is the obvious one.
                Button("Play on iPhone") {
                    cast.disconnect()
                    dismiss()
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .tint(.secondary)
            }
        }
        .padding(.horizontal, 28)
        .padding(.bottom, 24)
        .onChange(of: state?.positionMs) { _, new in
            reported = Double(new ?? 0) / 1000
            reportedAt = Date()
            if let pending = pendingSeek, abs(reported - pending) < 2 {
                pendingSeek = nil
            }
        }
    }

    /// The reported position, advanced locally between reports.
    ///
    /// Only while playing: a paused position that crept forward would be a lie
    /// about the television.
    private func livePosition(_ state: CastPlaybackState?) -> Double {
        guard let state else { return 0 }
        let base = Double(state.positionMs) / 1000
        guard !state.paused else { return base }
        return base + Date().timeIntervalSince(reportedAt)
    }
}
