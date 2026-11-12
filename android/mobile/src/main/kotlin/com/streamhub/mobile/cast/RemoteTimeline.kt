package com.streamhub.mobile.cast

import kotlin.math.max

/**
 * The remote's picture of where the television is, between its reports.
 *
 * Pure and clock-driven so the two rules CASTING.md §1 calls easy to get wrong
 * can be tested without a screen:
 *
 * - **Interpolation.** A receiver reports about once a second; between
 *   reports the position advances on the phone's clock, and only while the
 *   television is playing (not paused, not buffering, not gone).
 * - **The seek hold.** After a seek the bar shows the target, not the last
 *   report, until a report lands near it or [HOLD_MS] passes. Repeated
 *   ±10 s taps build on the held target, so three quick taps move thirty
 *   seconds rather than ten three times over.
 */
data class RemoteTimeline(
    val reportedMs: Long = 0,
    /** Phone clock at which [reportedMs] was true. */
    val anchoredAt: Long = 0,
    val playing: Boolean = false,
    val durationMs: Long = 0,
    val pendingSeekMs: Long? = null,
    val pendingSince: Long = 0,
) {

    /** Where the television is now, by interpolation from the last report. */
    fun positionAt(now: Long): Long {
        val advanced = if (playing) reportedMs + max(0L, now - anchoredAt) else reportedMs
        return clamp(advanced)
    }

    /** What the bar shows: the held seek target while there is one, else [positionAt]. */
    fun shownAt(now: Long): Long {
        val pending = pendingSeekMs
        if (pending == null || now - pendingSince >= HOLD_MS) return positionAt(now)
        // A held target advances too when the television is playing, or a
        // seek made while playing would sit frozen for up to four seconds.
        return clamp(if (playing) pending + max(0L, now - pendingSince) else pending)
    }

    /**
     * Folds a report in. [playing] is the caller's verdict (reported intent,
     * not buffering, receiver reachable, no optimistic pause in flight).
     */
    fun report(positionMs: Long, durationMs: Long, playing: Boolean, now: Long): RemoteTimeline {
        var next = copy(durationMs = durationMs)

        val pending = pendingSeekMs
        if (pending != null) {
            val reachedFloor = pending - CONFIRM_SLACK_MS
            val reachedCeiling = pending + CONFIRM_SLACK_MS + max(0L, now - pendingSince)
            if (positionMs in reachedFloor..reachedCeiling || now - pendingSince >= HOLD_MS) {
                next = next.copy(pendingSeekMs = null)
            }
        }

        // The same report again (the receiver list is re-sent whenever any
        // device on the account changes) must not re-anchor: treating an old
        // position as fresh drags the bar backwards by however long ago it was
        // true.
        if (positionMs == reportedMs && playing == this.playing) return next

        // A report a little behind where the bar already is, while playing
        // throughout, is network latency rather than news. Rewinding for it
        // made the bar twitch back a few hundred milliseconds every second.
        val local = positionAt(now)
        if (playing && this.playing && positionMs < local && local - positionMs < JITTER_MS) {
            return next
        }
        return next.copy(reportedMs = positionMs, anchoredAt = now, playing = playing)
    }

    /** Freezes or restarts the clock without a report: an optimistic pause, a lost receiver. */
    fun withPlaying(playing: Boolean, now: Long): RemoteTimeline {
        if (playing == this.playing) return this
        return copy(reportedMs = positionAt(now), anchoredAt = now, playing = playing)
    }

    /** Holds [targetMs] on the bar until the television confirms it. */
    fun seekTo(targetMs: Long, now: Long): RemoteTimeline =
        copy(pendingSeekMs = clampSeek(targetMs), pendingSince = now)

    /** Relative to what is on screen, so repeated taps accumulate. */
    fun seekBy(deltaMs: Long, now: Long): RemoteTimeline = seekTo(shownAt(now) + deltaMs, now)

    /** The absolute position a seek ends up asking for. */
    fun clampSeek(targetMs: Long): Long {
        // Never past the end: the television clamps to a second short of it,
        // and a target beyond that would never be "confirmed".
        val ceiling = if (durationMs > 0) max(0L, durationMs - 1_000) else Long.MAX_VALUE
        return targetMs.coerceIn(0L, ceiling)
    }

    private fun clamp(ms: Long): Long =
        if (durationMs > 0) ms.coerceIn(0L, durationMs) else max(0L, ms)

    companion object {
        /** How long a seek target is held without confirmation. */
        const val HOLD_MS = 4_000L

        /** A report within this of the target counts as the seek arriving. */
        const val CONFIRM_SLACK_MS = 2_000L

        /** Backwards corrections smaller than this, mid-playback, are ignored. */
        const val JITTER_MS = 1_000L
    }
}
