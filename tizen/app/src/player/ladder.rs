//! What to do when playback dies. android/core playback/RecoveryLadder.kt,
//! with one more rung, because this player knows every source that arrived:
//!
//! 1. retry the same URL in place (most faults are transient);
//! 2. switch to the server relay (`/api/stream`) — the server often reaches
//!    a CDN the set cannot; not ad-filtered, the accepted price;
//! 3. the next source, back on its clean manifest;
//! 4. give up visibly.
//!
//! A network outage is *not* a source failure and never climbs the ladder —
//! the caller checks reachability first. A minute of healthy playback earns
//! forgiveness ([`Ladder::forgive`]): back to rung 1 on the current source.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Tier {
    /// `/api/manifest?target=directUrl` — ads cut, segments straight from the CDN.
    Manifest,
    /// The source's `proxyUrl` — everything through the server.
    Relay,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Step {
    Retry,
    SwitchToRelay,
    /// Move to this source index, on [`Tier::Manifest`].
    NextSource(usize),
    /// Nothing left right now. More sources may still be streaming in.
    Exhausted,
}

#[derive(Clone, Debug)]
pub struct Ladder {
    pub source: usize,
    pub tier: Tier,
    retries_used: u32,
    retries_per_tier: u32,
}

impl Ladder {
    pub fn new(source: usize) -> Self {
        Ladder { source, tier: Tier::Manifest, retries_used: 0, retries_per_tier: 1 }
    }

    /// Called on each fatal error with how many sources are known now.
    pub fn next(&mut self, sources_known: usize) -> Step {
        if self.retries_used < self.retries_per_tier {
            self.retries_used += 1;
            return Step::Retry;
        }
        if self.tier == Tier::Manifest {
            self.tier = Tier::Relay;
            self.retries_used = 0;
            return Step::SwitchToRelay;
        }
        if self.source + 1 < sources_known {
            *self = Ladder::new(self.source + 1);
            return Step::NextSource(self.source);
        }
        Step::Exhausted
    }

    /// After sustained healthy playback or a manual retry.
    pub fn forgive(&mut self) {
        self.tier = Tier::Manifest;
        self.retries_used = 0;
    }

    /// The viewer picked a source by hand.
    pub fn select(&mut self, source: usize) {
        *self = Ladder::new(source);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn climbs_retry_relay_next_then_exhausts() {
        let mut l = Ladder::new(0);
        assert_eq!(l.next(2), Step::Retry);
        assert_eq!(l.tier, Tier::Manifest);
        assert_eq!(l.next(2), Step::SwitchToRelay);
        assert_eq!(l.tier, Tier::Relay);
        assert_eq!(l.next(2), Step::Retry);
        assert_eq!(l.next(2), Step::NextSource(1));
        assert_eq!((l.source, l.tier), (1, Tier::Manifest));
        assert_eq!(l.next(2), Step::Retry);
        assert_eq!(l.next(2), Step::SwitchToRelay);
        assert_eq!(l.next(2), Step::Retry);
        assert_eq!(l.next(2), Step::Exhausted);
        // A source that streamed in late is still reachable.
        assert_eq!(l.next(3), Step::NextSource(2));
    }

    #[test]
    fn forgiveness_restarts_on_the_same_source() {
        let mut l = Ladder::new(0);
        l.next(1);
        l.next(1);
        l.forgive();
        assert_eq!((l.source, l.tier), (0, Tier::Manifest));
        assert_eq!(l.next(1), Step::Retry);
    }
}
