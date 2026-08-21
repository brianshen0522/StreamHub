import SwiftUI

@Observable
@MainActor
final class DetailModel {
    var title: String
    var posterUrl: String?
    var isMovie = false
    var seasons: [SeasonRef] = []
    var selectedSeason: SeasonRef?
    var episodes: [String] = []
    var selectedEpisode: String?
    var sources: [Source] = []
    var completedEpisodes: Set<String> = []
    var loading = true
    var loadingSources = false
    var error: String?
    var favorite: Favorite?

    private let selection: MediaSelection
    private var progress: [String: WatchProgress] = [:]
    private var movieStreams: [RawStream] = []
    private var sourcesTask: Task<Void, Never>?

    init(selection: MediaSelection) {
        self.selection = selection
        self.title = selection.title
        self.posterUrl = selection.posterUrl
    }

    func load(_ api: APIClient) async {
        loading = true
        error = nil
        do {
            // Progress first: it decides which episode opens, and fetching it
            // after would mean opening the wrong one and correcting it.
            progress = ResumeRules.progressMap(
                (try? await api.progress(providerKey: selection.provider, itemUrl: selection.itemUrl)) ?? []
            )
            favorite = (try? await api.favorites())?.first {
                $0.providerKey == selection.provider && $0.itemUrl == selection.itemUrl
            }

            let detail = try await api.item(
                provider: selection.provider,
                url: selection.itemUrl,
                title: selection.title,
                mediaType: selection.mediaType,
                posterUrl: selection.posterUrl
            )
            loading = false

            switch detail {
            case .seasons(let title, let poster, let seasons):
                self.title = title.isEmpty ? self.title : title
                self.posterUrl = poster ?? posterUrl
                self.seasons = seasons
                // Not the first season — the one being watched. Opening a
                // five-season show at season one is only right for someone who
                // has never seen it.
                if let season = ResumeRules.resumeSeason(seasons: seasons, progress: progress) {
                    await selectSeason(season, api: api)
                }

            case .episodes(let title, let poster, let sourceUrl, let episodes):
                self.title = title.isEmpty ? self.title : title
                self.posterUrl = poster ?? posterUrl
                self.episodes = episodes
                self.selectedSeason = sourceUrl.map { SeasonRef(label: "", url: $0) }
                self.completedEpisodes = completed(episodes, seasonUrl: sourceUrl)
                let resume = ResumeRules.resumeEpisode(episodes: episodes, seasonUrl: sourceUrl, progress: progress)
                // A fully watched season resumes nowhere; open the first episode
                // rather than leaving the screen inert.
                if let episode = resume ?? episodes.first { selectEpisode(episode, api: api) }

            case .movie(let title, let poster, let streams):
                self.title = title.isEmpty ? self.title : title
                self.posterUrl = poster ?? posterUrl
                self.isMovie = true
                self.movieStreams = streams
                loadMovieSources(api)
            }
        } catch let failure as StreamHubError {
            loading = false
            error = failure.message
        } catch {
            loading = false
            self.error = "Could not reach the server."
        }
    }

    func selectSeason(_ season: SeasonRef, api: APIClient) async {
        selectedSeason = season
        episodes = []
        selectedEpisode = nil
        sources = []
        guard let list = try? await api.episodes(provider: selection.provider, sourceUrl: season.url) else {
            error = "Could not load episodes."
            return
        }
        episodes = list
        completedEpisodes = completed(list, seasonUrl: season.url)
        let resume = ResumeRules.resumeEpisode(episodes: list, seasonUrl: season.url, progress: progress)
        if let episode = resume ?? list.first { selectEpisode(episode, api: api) }
    }

    func selectEpisode(_ episode: String, api: APIClient) {
        selectedEpisode = episode
        sources = []
        loadingSources = true

        sourcesTask?.cancel()
        sourcesTask = Task {
            let preferred = try? await api.sourcePreference(
                providerKey: selection.provider,
                title: title,
                mediaType: selection.mediaType ?? "unknown"
            )
            let stream = api.sources(
                provider: selection.provider,
                seasonUrl: selectedSeason?.url ?? "",
                episode: episode,
                preferred: preferred ?? nil
            )
            // Sources land one at a time as each health probe finishes, so they
            // are appended rather than waited for. A stream that fails partway
            // still leaves everything that already arrived usable.
            do {
                for try await source in stream {
                    sources = rank(sources + [source])
                    loadingSources = false
                }
            } catch {}
            loadingSources = false
        }
    }

    private func loadMovieSources(_ api: APIClient) {
        sourcesTask?.cancel()
        loadingSources = true
        sourcesTask = Task {
            let stream = api.checkSources(provider: selection.provider, streams: movieStreams)
            do {
                for try await source in stream {
                    sources = rank(sources + [source])
                    loadingSources = false
                }
            } catch {}
            loadingSources = false
        }
    }

    func toggleFavorite(_ api: APIClient) async {
        if let favorite {
            try? await api.removeFavorite(id: favorite.id)
            self.favorite = nil
        } else {
            try? await api.addFavorite(NewFavorite(
                providerKey: selection.provider,
                mediaType: selection.mediaType ?? "unknown",
                title: title,
                posterUrl: posterUrl,
                itemUrl: selection.itemUrl,
                seasonUrl: selectedSeason?.url,
                seasonLabel: selectedSeason?.label,
                episodeLabel: selectedEpisode
            ))
            favorite = (try? await api.favorites())?.first {
                $0.providerKey == selection.provider && $0.itemUrl == selection.itemUrl
            }
        }
    }

    func playback(for source: Source) -> PlaybackRequest {
        let existing = progress[ResumeRules.progressKey(
            seasonUrl: selectedSeason?.url,
            episodeLabel: selectedEpisode
        )]
        var next: String?
        if case .episode(let label)? = ResumeRules.upNext(
            episodes: episodes,
            currentEpisode: selectedEpisode
        ) {
            next = label
        }

        return PlaybackRequest(
            providerKey: selection.provider,
            mediaType: isMovie ? "movie" : "tv",
            title: title,
            posterUrl: posterUrl,
            itemUrl: selection.itemUrl,
            seasonUrl: selectedSeason?.url,
            seasonLabel: selectedSeason?.label.isEmpty == false ? selectedSeason?.label : nil,
            episodeLabel: selectedEpisode,
            sourceLabel: source.sourceLabel,
            directUrl: source.directUrl,
            durationSeconds: source.durationSeconds,
            resumeAtSeconds: ResumeRules.resumePositionSeconds(existing),
            nextEpisodeLabel: next
        )
    }

    /// Sources whose advertising was found and cut come first.
    ///
    /// A non-zero figure means the filter recognised this stream's ad breaks and
    /// removed them, so what plays is known to be clean. Zero says nothing
    /// either way. Stable, so the server's own ordering survives inside each
    /// group.
    private func rank(_ list: [Source]) -> [Source] {
        list.enumerated()
            .sorted { left, right in
                let a = left.element.adSeconds > 0
                let b = right.element.adSeconds > 0
                return a == b ? left.offset < right.offset : a && !b
            }
            .map(\.element)
    }

    private func completed(_ episodes: [String], seasonUrl: String?) -> Set<String> {
        Set(episodes.filter {
            progress[ResumeRules.progressKey(seasonUrl: seasonUrl, episodeLabel: $0)]?.isCompleted == true
        })
    }
}

/// A title, ready to play.
///
/// The hero is the TV app's: artwork, name, one line of metadata, and a
/// prominent Play beside a row of circular secondary actions. Play starts the
/// best source of the episode this account would resume, so the common case
/// never touches the episode or source rows below.
struct DetailView: View {
    let selection: MediaSelection

    @Environment(AppModel.self) private var model
    @Environment(CastController.self) private var cast
    @State private var detail: DetailModel
    @State private var playing: PlaybackRequest?
    @State private var startWhenReady = false

    init(selection: MediaSelection) {
        self.selection = selection
        _detail = State(initialValue: DetailModel(selection: selection))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                hero
                if !detail.seasons.isEmpty { seasonPicker }
                if !detail.episodes.isEmpty { episodeRow }
                if detail.sources.count > 1 { sourceList }
            }
            .padding(.vertical, 12)
        }
        .navigationTitle(detail.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { CastButton() } }
        .task { await detail.load(model.api) }
        .fullScreenCover(item: $playing) { request in
            PlayerView(request: request) { exit in
                guard case .playNext(let episode) = exit else { return }
                detail.selectEpisode(episode, api: model.api)
                startWhenReady = true
            }
            .ignoresSafeArea()
        }
        // Answering "play the next episode" by selecting it and stopping there
        // asks for a second tap to do the thing that was already asked for. It
        // waits rather than starting at once because the episode's sources
        // arrive one at a time over NDJSON.
        .onChange(of: detail.sources.first?.directUrl) { _, _ in
            guard startWhenReady, let best = detail.sources.first else { return }
            startWhenReady = false
            start(detail.playback(for: best))
        }
        .overlay {
            if detail.loading { ProgressView() }
            else if let error = detail.error {
                InlineErrorView(message: error) { Task { await detail.load(model.api) } }
            }
        }
    }

    private var hero: some View {
        HStack(alignment: .top, spacing: 18) {
            RemoteImage(url: model.posterURL(detail.posterUrl))
                .frame(width: 128, height: 192)
                .clipShape(.rect(cornerRadius: 12))
                .shadow(radius: 10, y: 5)

            VStack(alignment: .leading, spacing: 12) {
                Text(detail.title)
                    .font(.title2.weight(.bold))
                    .lineLimit(3)

                Text(metadata)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                HStack(spacing: 10) {
                    PlayButton(title: playTitle) { play() }
                        .disabled(detail.sources.isEmpty)

                    CircleIconButton(
                        systemImage: detail.favorite == nil ? "plus" : "checkmark",
                        accessibilityLabel: detail.favorite == nil ? "Add to Library" : "Remove from Library",
                        isOn: detail.favorite != nil
                    ) {
                        Task { await detail.toggleFavorite(model.api) }
                    }
                }

                if let best = detail.sources.first {
                    Text(describe(best))
                        .font(.footnote)
                        // Green, not red: ads having been removed is the thing
                        // that went right.
                        .foregroundStyle(best.adSeconds > 0 ? .green : .secondary)
                }
            }
        }
        .padding(.horizontal, 20)
    }

    private var metadata: String {
        var parts = [detail.isMovie ? "Film" : "Series"]
        if let episode = detail.selectedEpisode { parts.append("Episode \(episode)") }
        if let season = detail.selectedSeason?.label, !season.isEmpty { parts.append(season) }
        return parts.joined(separator: " · ")
    }

    private var playTitle: String {
        if detail.sources.isEmpty { return detail.loadingSources ? "Finding Sources…" : "No Source" }
        return cast.target == nil ? "Play" : "Play on TV"
    }

    private var seasonPicker: some View {
        Shelf(title: "Seasons") {
            ForEach(detail.seasons) { season in
                Button(season.label.isEmpty ? "Season" : season.label) {
                    Task { await detail.selectSeason(season, api: model.api) }
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .tint(season.url == detail.selectedSeason?.url ? .streamHubAccent : .primary)
            }
        }
    }

    private var episodeRow: some View {
        Shelf(title: "Episodes") {
            ForEach(detail.episodes, id: \.self) { episode in
                let selected = episode == detail.selectedEpisode
                // Filled for the episode in play, outlined for the rest. A
                // tinted-but-not-filled chip reads as accent-coloured text on a
                // dark chip, which at a glance is not obviously "this one".
                Group {
                    if selected {
                        Button { detail.selectEpisode(episode, api: model.api) } label: {
                            episodeLabel(episode)
                        }
                        .buttonStyle(.borderedProminent)
                    } else {
                        Button { detail.selectEpisode(episode, api: model.api) } label: {
                            episodeLabel(episode)
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .buttonBorderShape(.capsule)
                .tint(selected ? .streamHubAccent : .primary)
            }
        }
    }

    @ViewBuilder
    private func episodeLabel(_ episode: String) -> some View {
        HStack(spacing: 4) {
            Text(episode)
            // Watched is marked, not hidden: the list is also how someone
            // finds an episode to watch again.
            if detail.completedEpisodes.contains(episode) {
                Image(systemName: "checkmark").font(.caption2)
            }
        }
    }

    private var sourceList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Other Sources")
                .font(.title3.weight(.bold))
                .padding(.horizontal, 20)

            ForEach(detail.sources.dropFirst()) { source in
                Button {
                    start(detail.playback(for: source))
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(source.sourceLabel).foregroundStyle(.primary)
                            Text(describe(source))
                                .font(.caption)
                                .foregroundStyle(source.adSeconds > 0 ? .green : .secondary)
                        }
                        Spacer()
                        Image(systemName: "play.circle").foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                Divider().padding(.leading, 20)
            }
        }
    }

    private func describe(_ source: Source) -> String {
        [source.durationSeconds?.minutesLabel, source.adSeconds > 0 ? "\(source.adSeconds)s of ads removed" : nil]
            .compactMap { $0 }
            .joined(separator: " · ")
    }

    private func play() {
        guard let best = detail.sources.first else { return }
        start(detail.playback(for: best))
    }

    /// Where Play goes. Casting is app-wide state, so the decision belongs here
    /// rather than in each button: once a television is chosen, every Play in
    /// the app follows it until that choice is undone.
    private func start(_ request: PlaybackRequest) {
        if cast.target != nil, cast.play(request) { return }
        playing = request
    }
}
