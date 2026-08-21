import SwiftUI

// MARK: - Artwork loading

/// A small in-memory cache, so scrolling a shelf back and forth does not refetch.
@MainActor
final class ArtworkCache {
    static let shared = ArtworkCache()
    private var images: [URL: UIImage] = [:]
    private let limit = 200

    func image(for url: URL) -> UIImage? { images[url] }

    func store(_ image: UIImage, for url: URL) {
        if images.count >= limit { images.removeAll() }
        images[url] = image
    }
}

/// Artwork behind authentication.
///
/// Not `AsyncImage`: posters sit behind auth and AsyncImage cannot set headers,
/// so it would need the token in the query string. The server does accept one
/// there — `<img>` in a browser has no other option — but a native client does,
/// and keeping credentials out of URLs keeps them out of caches and proxy logs.
struct RemoteImage: View {
    let url: URL?
    @Environment(AppModel.self) private var model
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Rectangle().fill(.fill.quaternary)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            }
        }
        .task(id: url) { await load() }
    }

    private func load() async {
        guard let url else { return }
        if let cached = ArtworkCache.shared.image(for: url) {
            image = cached
            return
        }
        let request = model.api.authorizedRequest(url)
        guard let (data, _) = try? await URLSession.shared.data(for: request),
              let decoded = UIImage(data: data) else { return }
        ArtworkCache.shared.store(decoded, for: url)
        image = decoded
    }
}

// MARK: - Shelves

/// A horizontal shelf, the unit the whole app is built from.
///
/// Shelves rather than a grid because that is how a catalogue of unequal
/// importance reads: what matters is at the top, and each row stays one idea
/// wide. It is also what makes the same screen work on an iPad without a second
/// layout — the row simply shows more.
struct Shelf<Content: View>: View {
    let title: String
    var subtitle: String?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.title3.weight(.bold))
                if let subtitle {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 20)

            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 14) {
                    content
                }
                .padding(.horizontal, 20)
                // Cards lift on press; without room they clip against the
                // scroll view's own bounds at the first and last item.
                .padding(.vertical, 4)
            }
            .scrollIndicators(.hidden)
        }
    }
}

/// Poster artwork with its title underneath.
struct ArtworkCard: View {
    let title: String
    var subtitle: String?
    let posterURL: URL?
    var progress: Double?
    var width: CGFloat = 132

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            RemoteImage(url: posterURL)
                .frame(width: width, height: width * 3 / 2)
                .clipShape(.rect(cornerRadius: 10))
                .overlay {
                    // A hairline, the way the TV app separates artwork from a
                    // dark background when the poster itself is dark.
                    RoundedRectangle(cornerRadius: 10)
                        .strokeBorder(.white.opacity(0.12), lineWidth: 0.5)
                }
                .overlay(alignment: .bottom) {
                    if let progress, progress > 0 {
                        ProgressView(value: min(max(progress, 0), 1))
                            .progressViewStyle(.linear)
                            .tint(.white)
                            .scaleEffect(y: 0.6, anchor: .bottom)
                            .padding(.horizontal, 6)
                            .padding(.bottom, 6)
                    }
                }

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.footnote.weight(.medium))
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(width: width, alignment: .leading)
        }
        .contentShape(.rect)
    }
}

// MARK: - Buttons

/// The prominent capsule the TV app puts on every title.
///
/// Stock `.borderedProminent` at `.large`, not a hand-drawn control: the system
/// one already has the press behaviour, the Dynamic Type scaling and the
/// contrast handling that a custom capsule would have to reimplement badly.
struct PlayButton: View {
    var title: String = "Play"
    var systemImage: String = "play.fill"
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.headline)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .buttonBorderShape(.capsule)
    }
}

/// The circular icon button that sits beside Play — add to library, cast, share.
struct CircleIconButton: View {
    let systemImage: String
    var accessibilityLabel: String
    var isOn: Bool = false
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 17, weight: .semibold))
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.circle)
        .tint(isOn ? .streamHubAccent : .primary)
        .accessibilityLabel(accessibilityLabel)
    }
}

// MARK: - States

struct EmptyStateView: View {
    let title: String
    let message: String
    var systemImage: String = "play.slash"

    var body: some View {
        ContentUnavailableView(title, systemImage: systemImage, description: Text(message))
    }
}

struct InlineErrorView: View {
    let message: String
    var retry: (() -> Void)?

    var body: some View {
        VStack(spacing: 12) {
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if let retry {
                Button("Try Again", action: retry)
                    .buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
    }
}

// MARK: - Formatting

extension Int {
    /// "24 min" — the only duration form any of these screens needs.
    var minutesLabel: String? {
        guard self > 0 else { return nil }
        return "\(self / 60) min"
    }
}

func timeLabel(_ seconds: Double) -> String {
    guard seconds.isFinite, seconds > 0 else { return "0:00" }
    let total = Int(seconds)
    let hours = total / 3600
    let minutes = (total % 3600) / 60
    let secs = total % 60
    return hours > 0
        ? String(format: "%d:%02d:%02d", hours, minutes, secs)
        : String(format: "%d:%02d", minutes, secs)
}
