import SwiftUI

@Observable
@MainActor
final class SearchModel {
    var results: [ProviderResults] = []
    var searching = false
    var searched = false
    var error: String?

    func search(_ api: APIClient, query: String, providers: Set<String>) async {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        searching = true
        error = nil
        do {
            results = try await api.search(query: trimmed, providers: providers).results
        } catch {
            self.error = "Could not reach the server."
        }
        searching = false
        searched = true
    }
}

/// Search, grouped by provider.
///
/// The scope bar filters which providers are asked, which is the search-native
/// place for it — a separate filter control would be a second thing to find.
/// A provider that failed still gets its row with the error, because
/// `/api/search` answers 200 either way and silence would read as "no results"
/// instead of "this one is down".
struct SearchView: View {
    @Environment(AppModel.self) private var model
    @State private var search = SearchModel()
    @State private var query = ""
    @State private var scope: Scope = .all

    private enum Scope: String, CaseIterable, Identifiable {
        case all, movieffm, tv777 = "777tv", dramasq
        var id: String { rawValue }
        var title: String { self == .all ? "All" : rawValue }
        var providers: Set<String> { self == .all ? [] : [rawValue] }
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 28) {
                if let error = search.error {
                    InlineErrorView(message: error)
                }

                ForEach(search.results) { group in
                    if let failure = group.error {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(group.provider).font(.title3.weight(.bold))
                            Text(failure).font(.subheadline).foregroundStyle(.secondary)
                        }
                        .padding(.horizontal, 20)
                    } else if !group.items.isEmpty {
                        Shelf(title: group.provider, subtitle: "\(group.items.count) results") {
                            ForEach(group.items) { item in
                                NavigationLink(value: MediaSelection(
                                    provider: item.provider,
                                    itemUrl: item.url,
                                    title: item.title,
                                    mediaType: item.mediaType,
                                    posterUrl: item.posterUrl
                                )) {
                                    ArtworkCard(
                                        title: item.title,
                                        posterURL: model.posterURL(item.posterUrl)
                                    )
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }

                if search.searched, !search.searching,
                   search.results.allSatisfy({ $0.items.isEmpty }), search.error == nil {
                    EmptyStateView(
                        title: "No Results",
                        message: "Nothing matched “\(query)”.",
                        systemImage: "magnifyingglass"
                    )
                    .padding(.top, 60)
                }
            }
            .padding(.vertical, 8)
        }
        // Reaching for a result is the end of typing. Without this the field
        // stays focused over the results, and the first tap on a poster only
        // puts the keyboard away.
        .scrollDismissesKeyboard(.immediately)
        .overlay {
            if search.searching { ProgressView() }
        }
        .navigationTitle("Search")
        .navigationDestination(for: MediaSelection.self) { DetailView(selection: $0) }
        .searchable(text: $query, prompt: "Films and Series")
        .searchScopes($scope) {
            ForEach(Scope.allCases) { scope in
                Text(scope.title).tag(scope)
            }
        }
        .onSubmit(of: .search) {
            Task { await search.search(model.api, query: query, providers: scope.providers) }
        }
        .onChange(of: scope) { _, _ in
            guard search.searched else { return }
            Task { await search.search(model.api, query: query, providers: scope.providers) }
        }
    }
}
