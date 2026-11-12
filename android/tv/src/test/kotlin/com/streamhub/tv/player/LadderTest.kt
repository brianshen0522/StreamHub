package com.streamhub.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** `player/ladder.rs` tests, ported. */
class LadderTest {

    @Test
    fun climbsRetryRelayNextThenExhausts() {
        val l = Ladder(0)
        assertEquals(Step.Retry, l.next(2))
        assertEquals(Tier.Manifest, l.tier)
        assertEquals(Step.SwitchToRelay, l.next(2))
        assertEquals(Tier.Relay, l.tier)
        assertEquals(Step.Retry, l.next(2))
        assertEquals(Step.NextSource(1), l.next(2))
        assertEquals(1 to Tier.Manifest, l.source to l.tier)
        assertEquals(Step.Retry, l.next(2))
        assertEquals(Step.SwitchToRelay, l.next(2))
        assertEquals(Step.Retry, l.next(2))
        assertEquals(Step.Exhausted, l.next(2))
        // A source that streamed in late is still reachable.
        assertEquals(Step.NextSource(2), l.next(3))
    }

    @Test
    fun forgivenessRestartsOnTheSameSource() {
        val l = Ladder(0)
        l.next(1)
        l.next(1)
        l.forgive()
        assertEquals(0 to Tier.Manifest, l.source to l.tier)
        assertEquals(Step.Retry, l.next(1))
    }

    @Test
    fun selectStartsAFreshLadderOnThatSource() {
        val l = Ladder(0)
        l.next(3)
        l.next(3)
        l.select(2)
        assertEquals(2 to Tier.Manifest, l.source to l.tier)
        assertEquals(Step.Retry, l.next(3))
    }
}
