import Foundation

/// What to do when playback dies mid-stream.
///
/// The same ladder the Android players climb, in the same order, so a fault
/// feels identical on every device: retry the same source in place (most
/// mid-stream faults are transient), then fetch the stream through the server
/// relay instead of straight from the CDN (the server often reaches a CDN the
/// device cannot; the relayed stream is not ad-filtered, which is the accepted
/// price of the picture coming back), and only then give up — visibly, with a
/// retry control, never a silent black rectangle.
///
/// A stretch of healthy playback earns forgiveness: `reset()` puts the ladder
/// back at the bottom, so an error at minute 40 is treated as fresh rather
/// than inheriting the strikes of one at minute 2.
struct RecoveryLadder {
    enum Step {
        /// Re-prepare the current source and resume at the same position.
        case retry
        /// Move to the server relay and resume there.
        case switchToRelay
        /// Stop pretending: show the error and offer a manual retry.
        case giveUp
    }

    /// 0 = the cleaned CDN manifest, 1 = the server relay.
    private(set) var tier = 0
    private var retriesUsed = 0
    private let retriesPerTier: Int

    init(retriesPerTier: Int = 1) {
        self.retriesPerTier = retriesPerTier
    }

    /// Called on each fatal fault; returns the next thing worth trying.
    mutating func next() -> Step {
        if retriesUsed < retriesPerTier {
            retriesUsed += 1
            return .retry
        }
        if tier == 0 {
            tier = 1
            retriesUsed = 0
            return .switchToRelay
        }
        return .giveUp
    }

    /// Back to the bottom — after sustained healthy playback or a manual retry.
    mutating func reset() {
        tier = 0
        retriesUsed = 0
    }
}
