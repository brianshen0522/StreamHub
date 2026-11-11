//! A navigation stack. Back pops; at the root (Home) Back asks to exit, which
//! is what Samsung's checklist requires (CO-US-05).

use leptos::prelude::*;
use serde_json::Value;

/// What the Detail screen opens. Built from a search result, a library row or
/// a cast command.
#[derive(Clone, Debug, PartialEq, Default)]
pub struct DetailTarget {
    pub provider: String,
    /// The item URL as the provider gave it (search result `url` / row `itemUrl`).
    pub url: String,
    pub title: String,
    pub poster_url: String,
    pub media_type: String,
    /// Season and episode the entry point carried. Only a *history* row means
    /// "take me to that viewing" (`from_history`); a favourite's is a fallback
    /// for a title with no progress — see CLAUDE.md "Where a title opens".
    pub season_url: Option<String>,
    pub episode_label: Option<String>,
    pub from_history: bool,
    /// The library row this came from, if any (for isUnavailable etc.).
    pub row: Option<Value>,
}

/// What the Player plays.
#[derive(Clone, Debug, PartialEq, Default)]
pub struct PlayTarget {
    pub provider: String,
    /// Progress/favourite key fields — must be sent exactly as received.
    pub item_url: String,
    pub season_url: String,
    pub title: String,
    pub poster_url: String,
    /// "movie" / "tv" as Detail resolved it, or "" when unknown (a cast
    /// `play`), in which case the player infers it. Stored on the progress
    /// row, so every other client reads it back.
    pub media_type: String,
    /// The season's display label (movieffm hubs), "" otherwise.
    pub season_label: String,
    /// "" for a movie.
    pub episode_label: String,
    /// Every episode label of the season in order, for next/previous. Empty for a movie.
    pub episodes: Vec<String>,
    /// The `/api/sources` (episodes) or `/api/check-sources` (movie) source
    /// line chosen, or None to let the player pick the first that arrives.
    pub source: Option<Value>,
    /// Where to start, seconds. None = resume rules decide (Detail computes it).
    pub start_seconds: Option<f64>,
    /// Started by a phone over the cast channel.
    pub from_cast: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Route {
    Home,
    Search,
    Detail(DetailTarget),
    Player(PlayTarget),
}

// Arc for the same reason as session.rs: a lazily created RwSignal would be
// owned, and disposed, by whichever component touched it first.
thread_local! {
    static STACK: ArcRwSignal<Vec<Route>> = ArcRwSignal::new(vec![Route::Home]);
}

pub fn stack() -> ArcRwSignal<Vec<Route>> {
    STACK.with(|s| s.clone())
}

pub fn current() -> Memo<Route> {
    let stack = stack();
    Memo::new(move |_| stack.with(|s| s.last().cloned().unwrap_or(Route::Home)))
}

pub fn push(route: Route) {
    stack().update(|s| s.push(route));
}

/// Replace the top entry (e.g. next episode, or a cast `play` while playing).
pub fn replace(route: Route) {
    stack().update(|s| {
        s.pop();
        s.push(route);
    });
}

/// Pop one level. Returns false at the root, where the caller asks to exit.
pub fn back() -> bool {
    let mut popped = false;
    stack().update(|s| {
        if s.len() > 1 {
            s.pop();
            popped = true;
        }
    });
    popped
}

pub fn reset() {
    stack().set(vec![Route::Home]);
}
