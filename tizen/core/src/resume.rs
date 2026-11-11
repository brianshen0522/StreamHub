//! Which season and episode a title resumes at, where in it, and what plays
//! next. A line-for-line port of android/core resume/ResumeRules.kt (whose
//! tests are ported below). The web app and iOS carry the same rules; change
//! all of them together — see CLAUDE.md.

use std::collections::HashMap;

use crate::models::{SeasonRef, WatchProgress};

/// Resume this far before where the viewer stopped, for context.
pub const RESUME_REWIND_SECONDS: f64 = 30.0;
/// Offer the next episode once less than this much remains.
pub const UP_NEXT_LEAD_SECONDS: f64 = 120.0;

/// `seasonUrl::episodeLabel`, absent parts as "". Episode labels are opaque
/// keys: never reformat one, or resume breaks on every other client.
pub fn progress_key(season_url: Option<&str>, episode_label: Option<&str>) -> String {
    format!("{}::{}", season_url.unwrap_or(""), episode_label.unwrap_or(""))
}

pub fn progress_map(entries: &[WatchProgress]) -> HashMap<String, WatchProgress> {
    entries
        .iter()
        .map(|p| {
            (
                progress_key(p.season_url.as_deref(), p.episode_label.as_deref()),
                p.clone(),
            )
        })
        .collect()
}

/// Comparable instant. The server always writes the same fixed-width ISO UTC
/// form, so the string orders correctly; absent sorts first (Kotlin uses
/// Long.MIN_VALUE for the same purpose).
fn when(p: &WatchProgress) -> &str {
    p.last_watched_at.as_deref().unwrap_or("")
}

/// The season holding the most recent progress, else the fallback (a
/// favourite's season), else the first.
pub fn resume_season<'a>(
    seasons: &'a [SeasonRef],
    progress: &HashMap<String, WatchProgress>,
    fallback_season_url: Option<&str>,
) -> Option<&'a SeasonRef> {
    if seasons.is_empty() {
        return None;
    }
    let latest = progress
        .values()
        .filter(|p| p.season_url.as_deref().is_some_and(|s| !s.trim().is_empty()))
        .max_by(|a, b| when(a).cmp(when(b)));
    latest
        .and_then(|row| seasons.iter().find(|s| Some(s.url.as_str()) == row.season_url.as_deref()))
        .or_else(|| fallback_season_url.and_then(|url| seasons.iter().find(|s| s.url == url)))
        .or_else(|| seasons.first())
}

/// The latest-watched episode if unfinished; the one after it if finished;
/// None when the finished one was the last. Nothing watched → the first.
pub fn resume_episode(
    episodes: &[String],
    season_url: Option<&str>,
    progress: &HashMap<String, WatchProgress>,
) -> Option<String> {
    if episodes.is_empty() {
        return None;
    }
    let latest = episodes
        .iter()
        .filter_map(|e| progress.get(&progress_key(season_url, Some(e))))
        .max_by(|a, b| when(a).cmp(when(b)));
    let Some(latest) = latest else {
        return episodes.first().cloned();
    };
    if !latest.is_completed {
        return latest.episode_label.clone();
    }
    let Some(index) = episodes.iter().position(|e| Some(e.as_str()) == latest.episode_label.as_deref()) else {
        return episodes.first().cloned();
    };
    episodes.get(index + 1).cloned()
}

/// Position minus 30 s, floored at 0. A completed episode restarts at 0
/// (deliberately unlike the web, which honours the position as given).
pub fn resume_position_seconds(progress: Option<&WatchProgress>) -> f64 {
    let Some(p) = progress else { return 0.0 };
    if p.is_completed {
        return 0.0;
    }
    (p.position_seconds - RESUME_REWIND_SECONDS).max(0.0)
}

pub fn should_offer_up_next(duration_seconds: f64, position_seconds: f64) -> bool {
    if duration_seconds <= 0.0 {
        return false;
    }
    duration_seconds - position_seconds < UP_NEXT_LEAD_SECONDS
}

#[derive(Clone, Debug, PartialEq)]
pub enum UpNext {
    Episode(String),
    /// The next season; its first episode is resolved after loading it.
    /// Only for providers that have a season list.
    Season(SeasonRef),
}

pub fn up_next(
    episodes: &[String],
    current_episode: Option<&str>,
    seasons: &[SeasonRef],
    current_season_url: Option<&str>,
) -> Option<UpNext> {
    let index = episodes.iter().position(|e| Some(e.as_str()) == current_episode)?;
    if index + 1 < episodes.len() {
        return Some(UpNext::Episode(episodes[index + 1].clone()));
    }
    let season_index = seasons.iter().position(|s| Some(s.url.as_str()) == current_season_url)?;
    seasons.get(season_index + 1).cloned().map(UpNext::Season)
}

/// Never crosses seasons.
pub fn previous_episode(episodes: &[String], current_episode: Option<&str>) -> Option<String> {
    let index = episodes.iter().position(|e| Some(e.as_str()) == current_episode)?;
    if index > 0 {
        Some(episodes[index - 1].clone())
    } else {
        None
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SeasonStatus {
    Unwatched,
    InProgress,
    Completed,
}

pub fn season_status(season_url: Option<&str>, progress: &HashMap<String, WatchProgress>) -> SeasonStatus {
    let want = season_url.unwrap_or("");
    let entries: Vec<&WatchProgress> = progress
        .values()
        .filter(|p| p.season_url.as_deref().unwrap_or("") == want)
        .collect();
    if entries.is_empty() {
        return SeasonStatus::Unwatched;
    }
    if entries.iter().all(|p| p.is_completed) {
        return SeasonStatus::Completed;
    }
    if entries.iter().any(|p| !p.is_completed && p.progress_percent > 0.0) {
        return SeasonStatus::InProgress;
    }
    SeasonStatus::Unwatched
}

// Tests: ported from android/core/src/test/.../resume/ResumeRulesTest.kt by the core agent.
