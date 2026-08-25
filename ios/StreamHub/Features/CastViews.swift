import SwiftUI

/// Opens the device picker. Renders nothing when there is nowhere to cast to.
///
/// A control that is always visible but usually does nothing teaches people to
/// ignore it; this one appearing is itself the signal that a television is on.
struct CastButton: View {
    @Environment(CastController.self) private var cast
    @State private var picking = false

    var body: some View {
        if !cast.controllable.isEmpty || cast.target != nil {
            Button { picking = true } label: {
                Image(systemName: cast.target == nil
                      ? "airplayvideo"
                      : "airplayvideo.badge.exclamationmark")
                    .symbolVariant(cast.target == nil ? .none : .fill)
            }
            .tint(cast.target == nil ? .primary : .streamHubAccent)
            .sheet(isPresented: $picking) { CastPicker() }
        }
    }
}

/// Picks where playback happens.
///
/// A sheet with detents rather than a menu, because it has to carry a second
/// section: televisions that are signed in but not running. Those cannot be
/// cast to, and saying so is the point — without that line the honest answer to
/// "why is my television missing" is invisible and the list reads as broken.
struct CastPicker: View {
    @Environment(AppModel.self) private var model
    @Environment(CastController.self) private var cast
    @Environment(\.dismiss) private var dismiss
    @State private var offline: [DeviceSession] = []

    var body: some View {
        NavigationStack {
            List {
                Section {
                    row(
                        name: "This iPhone",
                        detail: "Play here",
                        selected: cast.target == nil,
                        enabled: true
                    ) {
                        cast.disconnect()
                        dismiss()
                    }

                    ForEach(cast.controllable) { receiver in
                        row(
                            name: receiver.deviceName,
                            detail: receiver.state?.title.map { "Playing \($0)" } ?? "Ready",
                            selected: receiver.sessionId == cast.target?.sessionId,
                            enabled: true
                        ) {
                            cast.connect(to: receiver)
                            dismiss()
                        }
                    }
                } header: {
                    Text("Play On")
                }

                if !offline.isEmpty {
                    Section {
                        ForEach(offline) { device in
                            row(
                                name: device.deviceName,
                                detail: "Open StreamHub on this device",
                                selected: false,
                                enabled: false,
                                action: {}
                            )
                        }
                    } header: {
                        Text("Not Connected")
                    }
                }
            }
            .navigationTitle("Devices")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task { await loadOffline() }
    }

    @ViewBuilder
    private func row(
        name: String,
        detail: String,
        selected: Bool,
        enabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: "airplayvideo")
                    .font(.system(size: 18))
                    .foregroundStyle(selected ? Color.streamHubAccent : (enabled ? .primary : .secondary))
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text(name)
                        .foregroundStyle(enabled ? .primary : .secondary)
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if selected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(Color.streamHubAccent)
                }
            }
        }
        .disabled(!enabled)
    }

    private func loadOffline() async {
        let connected = Set(cast.receivers.map(\.sessionId))
        let sessions = (try? await model.api.sessions()) ?? []
        // One row per device, not one per sign-in: a television signed in a few
        // times has several live sessions, and rows sharing a name are
        // indistinguishable to whoever is reading them.
        var seen = Set<String>()
        offline = sessions
            .filter { $0.isTelevision && !connected.contains($0.id) }
            .sorted { ($0.lastSeenAt ?? "") > ($1.lastSeenAt ?? "") }
            .filter { seen.insert($0.deviceName).inserted }
            .prefix(3)
            .map { $0 }
    }
}

/// The strip that keeps a remote session reachable from anywhere.
///
/// Modelled on the system's own Now Playing bar: material background, artwork,
/// two lines, one transport control, and the whole strip opens the full remote.
struct CastBar: View {
    @Environment(AppModel.self) private var model
    @Environment(CastController.self) private var cast
    @State private var showingRemote = false

    var body: some View {
        if let target = cast.target {
            Button { showingRemote = true } label: {
                HStack(spacing: 12) {
                    RemoteImage(url: model.posterURL(target.state?.posterUrl))
                        .frame(width: 36, height: 36)
                        .clipShape(.rect(cornerRadius: 6))

                    VStack(alignment: .leading, spacing: 1) {
                        Text(target.state?.title ?? target.deviceName)
                            .font(.subheadline.weight(.medium))
                            .lineLimit(1)
                        Text(cast.lost
                             ? "\(target.deviceName) disconnected"
                             : (target.state?.title != nil ? "On \(target.deviceName)" : "Ready to play"))
                            .font(.caption)
                            .foregroundStyle(cast.lost ? .red : .secondary)
                            .lineLimit(1)
                    }

                    Spacer()

                    if !cast.lost, target.state?.title != nil {
                        Button {
                            if target.state?.paused == true { cast.resume() } else { cast.pause() }
                        } label: {
                            Image(systemName: target.state?.paused == true ? "play.fill" : "pause.fill")
                                .font(.system(size: 18))
                                .frame(width: 40, height: 40)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(target.state?.paused == true ? "Resume" : "Pause")
                        .accessibilityIdentifier("castBarPlayPause")
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
            }
            .buttonStyle(.plain)
            .background(.bar)
            .sheet(isPresented: $showingRemote) { RemoteView() }
        }
    }
}
