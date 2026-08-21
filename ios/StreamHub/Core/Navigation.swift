import Foundation

/// A title chosen somewhere, on its way to the detail screen.
struct MediaSelection: Hashable, Identifiable, Sendable {
    let provider: String
    let itemUrl: String
    let title: String
    var mediaType: String?
    var posterUrl: String?

    var id: String { provider + itemUrl }
}

/// Everything the player needs, resolved before it opens.
struct PlaybackRequest: Hashable, Identifiable, Sendable {
    let providerKey: String
    let mediaType: String
    let title: String
    var posterUrl: String?
    let itemUrl: String
    var seasonUrl: String?
    var seasonLabel: String?
    var episodeLabel: String?
    let sourceLabel: String
    let directUrl: String
    var durationSeconds: Int?
    var resumeAtSeconds: Int = 0
    /// What follows this episode, so the player can offer it. Nil at the end.
    var nextEpisodeLabel: String?

    var id: String { directUrl + (episodeLabel ?? "") }
}
