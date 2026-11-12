package com.streamhub.tv.player

/**
 * What to do when playback dies (`tizen/app/src/player/ladder.rs`): retry the
 * same URL, then the server relay, then the next source on its clean
 * manifest, then give up visibly. `:core`'s RecoveryLadder lacks the
 * next-source rung and `select`, so the player carries its own.
 *
 * A network outage is not a source failure and never climbs it — the caller
 * checks reachability first. A minute of healthy playback earns [forgive].
 */
enum class Tier {
    /** `/api/manifest?target=directUrl`: ads cut, segments straight from the CDN. */
    Manifest,

    /** The source's `proxyUrl`: everything through the server, not ad-filtered. */
    Relay,
}

sealed interface Step {
    data object Retry : Step
    data object SwitchToRelay : Step

    /** Move to this source index, on [Tier.Manifest]. */
    data class NextSource(val index: Int) : Step

    /** Nothing left right now. More sources may still be streaming in. */
    data object Exhausted : Step
}

class Ladder(source: Int) {
    var source: Int = source
        private set
    var tier: Tier = Tier.Manifest
        private set
    private var retriesUsed = 0
    private val retriesPerTier = 1

    /** Called on each fatal error with how many sources are known now. */
    fun next(sourcesKnown: Int): Step {
        if (retriesUsed < retriesPerTier) {
            retriesUsed += 1
            return Step.Retry
        }
        if (tier == Tier.Manifest) {
            tier = Tier.Relay
            retriesUsed = 0
            return Step.SwitchToRelay
        }
        if (source + 1 < sourcesKnown) {
            select(source + 1)
            return Step.NextSource(source)
        }
        return Step.Exhausted
    }

    /** After sustained healthy playback. */
    fun forgive() {
        tier = Tier.Manifest
        retriesUsed = 0
    }

    /** The viewer picked a source by hand (or 重試 restarts at 0). */
    fun select(index: Int) {
        source = index
        tier = Tier.Manifest
        retriesUsed = 0
    }
}
