import SwiftUI

/// Saved titles and what has been watched.
///
/// A grid here rather than shelves: a library is browsed by scanning everything,
/// not by following an editor's ordering, and the column count comes from the
/// size class so Split View and Stage Manager resize it without a device check.
struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var favorites: [Favorite] = []
    @State private var history: [WatchHistoryEntry] = []
    @State private var tab: Tab = .saved
    @State private var loading = true

    private enum Tab: String, CaseIterable, Identifiable {
        case saved = "Saved", history = "History"
        var id: String { rawValue }
    }

    private var columns: [GridItem] {
        let count = sizeClass == .compact ? 3 : 6
        return Array(repeating: GridItem(.flexible(), spacing: 14), count: count)
    }

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 18) {
                if tab == .saved {
                    ForEach(favorites) { favorite in
                        NavigationLink(value: MediaSelection(
                            provider: favorite.providerKey,
                            itemUrl: favorite.itemUrl,
                            title: favorite.title,
                            mediaType: favorite.mediaType,
                            posterUrl: favorite.posterUrl
                        )) {
                            gridCard(title: favorite.title, poster: favorite.posterUrl, subtitle: nil)
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button("Remove from Saved", systemImage: "heart.slash", role: .destructive) {
                                Task {
                                    try? await model.api.removeFavorite(id: favorite.id)
                                    await load()
                                }
                            }
                        }
                    }
                } else {
                    ForEach(history) { entry in
                        NavigationLink(value: MediaSelection(
                            provider: entry.providerKey,
                            itemUrl: entry.itemUrl,
                            title: entry.title,
                            mediaType: entry.mediaType,
                            posterUrl: entry.posterUrl
                        )) {
                            gridCard(
                                title: entry.title,
                                poster: entry.posterUrl,
                                subtitle: entry.episodeLabel.map { "Episode \($0)" }
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)

            if !loading, currentIsEmpty {
                EmptyStateView(
                    title: tab == .saved ? "Nothing Saved" : "Nothing Watched",
                    message: tab == .saved
                        ? "Titles you save appear here."
                        : "Titles you watch appear here.",
                    systemImage: tab == .saved ? "heart" : "clock"
                )
                .padding(.top, 40)
            }
        }
        .navigationTitle("Library")
        .navigationDestination(for: MediaSelection.self) { DetailView(selection: $0) }
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker("", selection: $tab) {
                    ForEach(Tab.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 240)
            }
            ToolbarItem(placement: .topBarTrailing) { CastButton() }
        }
        .task { await load() }
        .refreshable { await load() }
    }

    private var currentIsEmpty: Bool {
        tab == .saved ? favorites.isEmpty : history.isEmpty
    }

    @ViewBuilder
    private func gridCard(title: String, poster: String?, subtitle: String?) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            RemoteImage(url: model.posterURL(poster))
                .aspectRatio(2 / 3, contentMode: .fill)
                .clipShape(.rect(cornerRadius: 10))
            Text(title)
                .font(.caption.weight(.medium))
                .lineLimit(2)
                .multilineTextAlignment(.leading)
            if let subtitle {
                Text(subtitle).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
        }
    }

    private func load() async {
        favorites = (try? await model.api.favorites()) ?? []
        history = (try? await model.api.history()) ?? []
        loading = false
    }
}

/// Deliberately thin.
///
/// No password change and no device management: those exist in the admin
/// console and the phone's own settings, and rebuilding them here would be a
/// second place to keep correct for no gain. What belongs here is who is signed
/// in, which build this is, and the way out.
struct SettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List {
            Section {
                LabeledContent("Signed in as") {
                    Text(model.session?.user.displayName ?? model.session?.user.username ?? "—")
                }
                // No server address. It is not a secret — it is compiled into
                // the app and the device is talking to it — but it is
                // deployment detail with nothing a viewer can do about it.
                LabeledContent("Version") {
                    Text(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—")
                }
            }

            Section {
                NavigationLink {
                    PairTvView()
                } label: {
                    Label("Connect a TV", systemImage: "tv.badge.wifi")
                }
            } footer: {
                Text("Sign a television in with the code it is showing, instead of typing a password on a remote.")
            }

            Section {
                Button("Sign Out", role: .destructive) { model.signOut() }
            }
        }
        .navigationTitle("Settings")
    }
}
