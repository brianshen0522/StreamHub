import Foundation

/// Where a title picks up, which episode plays next, and when a season is done.
///
/// A port of `android/core/.../resume/ResumeRules.kt`, which is itself a port of
/// what the web player does. These rules are not obvious and they already exist.
/// Two clients disagreeing about which episode to resume is worse than either
/// rule being wrong, so behaviour changes belong in all three places at once.
///
/// "Completed" is decided by the *server* when progress is recorded (at or past
/// 95%, or within 90 seconds of the end) and arrives on the row. Nothing here
/// recomputes it.
enum ResumeRules {

    /// Playback restarts slightly before where it stopped, because the last few
    /// seconds before someone closed a player are rarely seconds they took in.
    static let resumeRewindSeconds = 30

    /// How long before the end the next episode is offered.
    static let upNextLeadSeconds = 120

    /// Progress rows are identified by season and episode together: a label like
    /// "EP1" repeats across seasons, and a series with no seasons has neither
    /// part.
    static func progressKey(seasonUrl: String?, episodeLabel: String?) -> String {
        "\(seasonUrl ?? "")::\(episodeLabel ?? "")"
    }

    static func progressMap(_ entries: [WatchProgress]) -> [String: WatchProgress] {
        var map: [String: WatchProgress] = [:]
        for entry in entries {
            map[progressKey(seasonUrl: entry.seasonUrl, episodeLabel: entry.episodeLabel)] = entry
        }
        return map
    }

    /// The episode to open when a title is picked.
    ///
    /// Nothing watched yet starts at the first episode. Otherwise the most
    /// recently watched episode decides: if it was finished the next one plays,
    /// and if that was the last episode the season is done and this returns nil.
    static func resumeEpisode(
        episodes: [String],
        seasonUrl: String?,
        progress: [String: WatchProgress]
    ) -> String? {
        guard let first = episodes.first else { return nil }

        let watched = episodes
            .compactMap { progress[progressKey(seasonUrl: seasonUrl, episodeLabel: $0)] }
            .sorted { epochMillis($0.lastWatchedAt) > epochMillis($1.lastWatchedAt) }

        guard let latest = watched.first else { return first }
        if !latest.isCompleted { return latest.episodeLabel }

        // firstIndex can miss if the provider renamed a label since it was
        // watched; treating that as "start over" beats skipping ahead.
        guard let index = episodes.firstIndex(of: latest.episodeLabel ?? "") else { return first }
        let next = episodes.index(after: index)
        return next < episodes.endIndex ? episodes[next] : nil
    }

    /// Where to seek on open. Zero means start from the beginning rather than
    /// seek — a rewind that lands at or before the start is not worth doing.
    ///
    /// A finished episode restarts from zero, matching the Android client. The
    /// web applies the rewind unconditionally, so deliberately reopening a
    /// watched episode drops you 30 seconds from the end; that is a bug to fix
    /// on the web rather than reproduce here.
    static func resumePositionSeconds(_ progress: WatchProgress?) -> Int {
        guard let progress else { return 0 }
        if progress.isCompleted { return 0 }
        return max(0, progress.positionSeconds - resumeRewindSeconds)
    }

    /// True once playback is close enough to the end to offer the next episode.
    static func shouldOfferUpNext(durationSeconds: Int, positionSeconds: Int) -> Bool {
        guard durationSeconds > 0 else { return false }
        return durationSeconds - positionSeconds < upNextLeadSeconds
    }

    /// What follows the episode playing now: the next episode, or the next
    /// season when this was the last one. Only providers that expose a season
    /// list can roll over; the rest simply end.
    static func upNext(
        episodes: [String],
        currentEpisode: String?,
        seasons: [SeasonRef] = [],
        currentSeasonUrl: String? = nil
    ) -> UpNext? {
        guard let current = currentEpisode,
              let index = episodes.firstIndex(of: current) else { return nil }

        if index < episodes.index(before: episodes.endIndex) {
            return .episode(label: episodes[episodes.index(after: index)])
        }

        guard let seasonIndex = seasons.firstIndex(where: { $0.url == currentSeasonUrl }),
              seasonIndex < seasons.index(before: seasons.endIndex) else { return nil }
        return .season(seasons[seasons.index(after: seasonIndex)])
    }

    private static func epochMillis(_ timestamp: String?) -> Double {
        guard let timestamp else { return -.greatestFiniteMagnitude }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: timestamp) { return date.timeIntervalSince1970 }
        formatter.formatOptions = [.withInternetDateTime]
        if let date = formatter.date(from: timestamp) { return date.timeIntervalSince1970 }
        return -.greatestFiniteMagnitude
    }
}

enum UpNext: Sendable, Equatable {
    case episode(label: String)
    case season(SeasonRef)
}
