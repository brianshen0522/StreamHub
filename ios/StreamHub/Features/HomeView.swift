import SwiftUI

@Observable
@MainActor
final class HomeModel {
    var continueWatching: [ContinueItem] = []
    var favorites: [Favorite] = []
    var loading = true
    var error: String?

    func load(_ api: APIClient) async {
        error = nil
        do {
            async let watching = api.continueWatching()
            async let saved = api.favorites()
            continueWatching = try await watching
            favorites = try await saved
            loading = false
        } catch {
            loading = false
            self.error = "Could not reach the server."
        }
    }
}

/// What you were watching, and what you saved.
///
/// Shelves in the order the TV app uses them: Up Next first, because on a
/// device you pick up for ten minutes it is what you want nine times out of ten.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    @State private var home = HomeModel()

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 28) {
                if let error = home.error {
                    InlineErrorView(message: error) {
                        Task { await home.load(model.api) }
                    }
                }

                if !home.continueWatching.isEmpty {
                    Shelf(title: "Up Next") {
                        ForEach(home.continueWatching) { entry in
                            NavigationLink(value: selection(entry)) {
                                ArtworkCard(
                                    title: entry.title,
                                    subtitle: entry.episodeLabel.map { "Episode \($0)" },
                                    posterURL: model.posterURL(entry.posterUrl),
                                    progress: entry.fraction
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                if !home.favorites.isEmpty {
                    Shelf(title: "Saved") {
                        ForEach(home.favorites) { favorite in
                            NavigationLink(value: selection(favorite)) {
                                ArtworkCard(
                                    title: favorite.title,
                                    posterURL: model.posterURL(favorite.posterUrl)
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                if !home.loading, home.continueWatching.isEmpty, home.favorites.isEmpty, home.error == nil {
                    EmptyStateView(
                        title: "Nothing Here Yet",
                        message: "Search to find something to watch.",
                        systemImage: "magnifyingglass"
                    )
                    .padding(.top, 60)
                }
            }
            .padding(.vertical, 8)
        }
        .navigationTitle("Watch Now")
        .toolbar { ToolbarItem(placement: .topBarTrailing) { CastButton() } }
        .navigationDestination(for: MediaSelection.self) { DetailView(selection: $0) }
        .task { await home.load(model.api) }
        .refreshable { await home.load(model.api) }
    }

    private func selection(_ entry: ContinueItem) -> MediaSelection {
        MediaSelection(
            provider: entry.providerKey,
            itemUrl: entry.itemUrl,
            title: entry.title,
            mediaType: entry.mediaType,
            posterUrl: entry.posterUrl
        )
    }

    private func selection(_ favorite: Favorite) -> MediaSelection {
        MediaSelection(
            provider: favorite.providerKey,
            itemUrl: favorite.itemUrl,
            title: favorite.title,
            mediaType: favorite.mediaType,
            posterUrl: favorite.posterUrl
        )
    }
}
