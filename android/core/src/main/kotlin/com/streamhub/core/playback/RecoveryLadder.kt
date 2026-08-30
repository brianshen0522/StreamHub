package com.streamhub.core.playback

/**
 * What to do when playback dies mid-stream.
 *
 * The report that motivated this: an episode plays for a while, then the
 * screen goes black and nothing responds. That is a player that hit a fatal
 * error — a segment the CDN stopped answering, a connection reset, a decoder
 * fault — with nobody listening: the player sits in its idle state, the
 * surface shows the last (or no) frame, and pressing play does nothing
 * because an errored player needs to be re-prepared, not resumed.
 *
 * The ladder tries the cheapest thing first and escalates:
 *
 *  1. **Retry in place** — re-prepare the same source at the same position.
 *     Most mid-stream faults are transient network hiccups and this is all
 *     they need.
 *  2. **Switch to the relay** — the same stream fetched through the server's
 *     proxy instead of straight from the CDN. The server often reaches a CDN
 *     the television cannot (different network path, different TLS stack).
 *     The relayed stream is not ad-filtered, which is the accepted price of
 *     the picture coming back.
 *  3. **Give up visibly** — a real error screen with a retry control, never
 *     a silent black rectangle.
 *
 * A stretch of healthy playback earns forgiveness: [reset] puts the ladder
 * back at the bottom, so an error at minute 40 is treated as fresh rather
 * than inheriting the strikes of one at minute 2.
 */
class RecoveryLadder(private val retriesPerTier: Int = 1) {

    enum class Step {
        /** Re-prepare the current source and resume at the same position. */
        RETRY,
        /** Move to the server relay and resume there. */
        SWITCH_TO_RELAY,
        /** Stop pretending: show the error and offer a manual retry. */
        GIVE_UP,
    }

    /** 0 = the clean CDN manifest, 1 = the server relay. */
    var tier: Int = 0
        private set

    private var retriesUsed = 0

    /** Called on each fatal error; returns the next thing worth trying. */
    fun next(): Step = when {
        retriesUsed < retriesPerTier -> {
            retriesUsed += 1
            Step.RETRY
        }
        tier == 0 -> {
            tier = 1
            retriesUsed = 0
            Step.SWITCH_TO_RELAY
        }
        else -> Step.GIVE_UP
    }

    /** Back to the bottom — after sustained healthy playback or a manual retry. */
    fun reset() {
        tier = 0
        retriesUsed = 0
    }
}
