package com.streamhub.core.playback

import com.streamhub.core.playback.RecoveryLadder.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryLadderTest {

    @Test
    fun `first fault retries in place, second switches to the relay`() {
        val ladder = RecoveryLadder()
        assertEquals(Step.RETRY, ladder.next())
        assertEquals(Step.SWITCH_TO_RELAY, ladder.next())
        assertEquals(1, ladder.tier)
    }

    @Test
    fun `the relay gets its own retry before giving up`() {
        val ladder = RecoveryLadder()
        ladder.next() // retry clean
        ladder.next() // switch to relay
        assertEquals(Step.RETRY, ladder.next())
        assertEquals(Step.GIVE_UP, ladder.next())
    }

    @Test
    fun `giving up is stable — asking again does not restart the ladder`() {
        val ladder = RecoveryLadder()
        repeat(4) { ladder.next() }
        assertEquals(Step.GIVE_UP, ladder.next())
        assertEquals(Step.GIVE_UP, ladder.next())
    }

    @Test
    fun `reset forgives everything and returns to the clean source`() {
        val ladder = RecoveryLadder()
        repeat(4) { ladder.next() }
        ladder.reset()
        assertEquals(0, ladder.tier)
        assertEquals(Step.RETRY, ladder.next())
    }

    @Test
    fun `a wider tier allows that many in-place retries`() {
        val ladder = RecoveryLadder(retriesPerTier = 2)
        assertEquals(Step.RETRY, ladder.next())
        assertEquals(Step.RETRY, ladder.next())
        assertEquals(Step.SWITCH_TO_RELAY, ladder.next())
    }
}
