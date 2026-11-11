//! Wire models shared by the resume rules. Mirrors android/core model/Library.kt
//! and model/Media.kt. Unknown fields are ignored; absent ones default, because
//! the server omits rather than nulls in several places.

use serde::{Deserialize, Serialize};

/// One `/api/me/progress` row.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct WatchProgress {
    pub id: Option<String>,
    pub provider_key: String,
    pub media_type: String,
    pub title: String,
    pub poster_url: Option<String>,
    pub item_url: String,
    pub detail_url: Option<String>,
    pub season_url: Option<String>,
    pub season_label: Option<String>,
    pub episode_label: Option<String>,
    pub source_label: Option<String>,
    pub duration_seconds: f64,
    pub position_seconds: f64,
    pub progress_percent: f64,
    pub is_completed: bool,
    /// ISO-8601 UTC as the server writes it (`2026-11-11T07:00:00.000Z`).
    pub last_watched_at: Option<String>,
}

/// A season of a movieffm TV hub: `/api/item` `seasons[]`.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct SeasonRef {
    pub label: String,
    pub url: String,
}
