//! Where a title opens, given the entry point it was opened from. Mirrors the
//! season/episode choice in frontend/src/App.jsx `handleSelectItem` (see
//! CLAUDE.md "Where a title opens"): only a history row (`from_history`)
//! pins its season and episode; anything else offers them as a fallback and
//! watch progress decides.

use std::collections::HashMap;

use crate::models::{SeasonRef, WatchProgress};
use crate::resume::{progress_key, resume_episode, resume_season};

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct EntryPoint<'a> {
    pub season_url: Option<&'a str>,
    pub episode_label: Option<&'a str>,
    pub from_history: bool,
}

fn norm(s: Option<&str>) -> &str {
    s.unwrap_or("")
}

/// A history row's own season when the hub still lists it, else the first;
/// otherwise the season holding the latest progress, the entry's season as a
/// fallback, then the first.
pub fn entry_season<'a>(
    seasons: &'a [SeasonRef],
    progress: &HashMap<String, WatchProgress>,
    entry: &EntryPoint,
) -> Option<&'a SeasonRef> {
    if entry.from_history {
        if let Some(url) = entry.season_url.filter(|u| !u.is_empty()) {
            return seasons.iter().find(|s| s.url == url).or_else(|| seasons.first());
        }
    }
    resume_season(seasons, progress, entry.season_url.filter(|u| !u.is_empty()))
}

#[derive(Clone, Debug, PartialEq)]
pub enum EpisodePick {
    Episode(String),
    /// Everything in this season is finished: the caller moves to the next
    /// season, or back to the start when there is none.
    SeasonFinished,
    /// No episodes at all.
    Empty,
}

/// The entry's episode is honoured when it pins one viewing, or while it
/// cannot contradict progress: on the season it was recorded against, and only
/// if that season has never been watched. A completed one is never reopened.
pub fn entry_episode(
    episodes: &[String],
    season_url: Option<&str>,
    progress: &HashMap<String, WatchProgress>,
    entry: &EntryPoint,
) -> EpisodePick {
    if episodes.is_empty() {
        return EpisodePick::Empty;
    }
    let season_watched = progress
        .values()
        .any(|p| norm(p.season_url.as_deref()) == norm(season_url));
    let use_passed =
        entry.from_history || (!season_watched && norm(season_url) == norm(entry.season_url));
    if use_passed {
        if let Some(ep) = entry.episode_label.filter(|e| !e.is_empty()) {
            let done = progress
                .get(&progress_key(season_url, Some(ep)))
                .is_some_and(|p| p.is_completed);
            if !done && episodes.iter().any(|e| e == ep) {
                return EpisodePick::Episode(ep.to_string());
            }
        }
    }
    match resume_episode(episodes, season_url, progress) {
        Some(ep) => EpisodePick::Episode(ep),
        None => EpisodePick::SeasonFinished,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::resume::progress_map;

    fn row(season: &str, ep: &str, done: bool, at: &str) -> WatchProgress {
        WatchProgress {
            season_url: Some(season.into()),
            episode_label: Some(ep.into()),
            is_completed: done,
            progress_percent: if done { 100.0 } else { 40.0 },
            last_watched_at: Some(at.into()),
            ..Default::default()
        }
    }

    fn seasons() -> Vec<SeasonRef> {
        vec![
            SeasonRef { label: "S1".into(), url: "https://x/s1".into() },
            SeasonRef { label: "S2".into(), url: "https://x/s2".into() },
        ]
    }

    fn eps() -> Vec<String> {
        vec!["EP1".into(), "EP2".into(), "EP3".into()]
    }

    #[test]
    fn favourite_season_does_not_override_progress() {
        let p = progress_map(&[row("https://x/s2", "EP1", false, "2026-01-02T00:00:00.000Z")]);
        let entry = EntryPoint { season_url: Some("https://x/s1"), episode_label: Some("EP1"), from_history: false };
        assert_eq!(entry_season(&seasons(), &p, &entry).unwrap().url, "https://x/s2");
    }

    #[test]
    fn history_pins_its_season_and_episode() {
        let p = progress_map(&[row("https://x/s2", "EP3", false, "2026-01-02T00:00:00.000Z")]);
        let entry = EntryPoint { season_url: Some("https://x/s1"), episode_label: Some("EP2"), from_history: true };
        assert_eq!(entry_season(&seasons(), &p, &entry).unwrap().url, "https://x/s1");
        assert_eq!(
            entry_episode(&eps(), Some("https://x/s1"), &p, &entry),
            EpisodePick::Episode("EP2".into())
        );
    }

    #[test]
    fn favourite_episode_only_on_unwatched_season() {
        let entry = EntryPoint { season_url: Some(""), episode_label: Some("EP2"), from_history: false };
        let none = HashMap::new();
        assert_eq!(entry_episode(&eps(), None, &none, &entry), EpisodePick::Episode("EP2".into()));
        let p = progress_map(&[row("", "EP3", false, "2026-01-02T00:00:00.000Z")]);
        assert_eq!(entry_episode(&eps(), None, &p, &entry), EpisodePick::Episode("EP3".into()));
    }

    #[test]
    fn finished_season_is_reported() {
        let p = progress_map(&[row("", "EP3", true, "2026-01-02T00:00:00.000Z")]);
        assert_eq!(entry_episode(&eps(), None, &p, &EntryPoint::default()), EpisodePick::SeasonFinished);
        assert_eq!(entry_episode(&[], None, &p, &EntryPoint::default()), EpisodePick::Empty);
    }

    #[test]
    fn next_after_completed() {
        let p = progress_map(&[row("", "EP1", true, "2026-01-02T00:00:00.000Z")]);
        assert_eq!(entry_episode(&eps(), None, &p, &EntryPoint::default()), EpisodePick::Episode("EP2".into()));
    }
}
