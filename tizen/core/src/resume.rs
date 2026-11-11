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
        .enumerate()
        .map(|(seq, p)| {
            (
                progress_key(p.season_url.as_deref(), p.episode_label.as_deref()),
                WatchProgress { seq, ..p.clone() },
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
    // Kotlin's maxByOrNull keeps the *first* maximum in the map's insertion
    // order (the server's list order); a HashMap has no order, so ties are
    // broken on `seq` explicitly — otherwise two clients could resume the
    // same title differently.
    let latest = progress
        .values()
        .filter(|p| p.season_url.as_deref().is_some_and(|s| !s.trim().is_empty()))
        .max_by(|a, b| when(a).cmp(when(b)).then(b.seq.cmp(&a.seq)));
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
    // Kotlin sorts descending (stable) and takes the first: among equal
    // timestamps that is the earliest in episode order. `max_by` keeps the
    // last maximum, so iterate in reverse.
    let latest = episodes
        .iter()
        .rev()
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

#[cfg(test)]
mod tests {
    //! Same cases and names as ResumeRulesTest.kt. Its five `UserCode.fromScan`
    //! / `forDisplay` cases are not ported: the television only displays the
    //! code the server already formatted and never scans one, so the Tizen
    //! core has no UserCode module.

    use super::*;

    const SEASON: &str = "http://x/season-1";

    fn episodes() -> Vec<String> {
        vec!["EP1".into(), "EP2".into(), "EP3".into()]
    }

    #[derive(Clone)]
    struct P {
        episode: &'static str,
        season_url: Option<&'static str>,
        completed: bool,
        position: f64,
        percent: f64,
        watched_at: &'static str,
    }

    fn p(episode: &'static str) -> P {
        P {
            episode,
            season_url: Some(SEASON),
            completed: false,
            position: 0.0,
            percent: 0.0,
            watched_at: "2026-08-01T00:00:00.000Z",
        }
    }

    impl P {
        fn season(mut self, s: Option<&'static str>) -> Self { self.season_url = s; self }
        fn completed(mut self) -> Self { self.completed = true; self }
        fn position(mut self, v: f64) -> Self { self.position = v; self }
        fn percent(mut self, v: f64) -> Self { self.percent = v; self }
        fn at(mut self, v: &'static str) -> Self { self.watched_at = v; self }
        fn build(self) -> WatchProgress {
            WatchProgress {
                provider_key: "movieffm".into(),
                title: "Show".into(),
                item_url: "http://x/item".into(),
                season_url: self.season_url.map(String::from),
                episode_label: Some(self.episode.into()),
                duration_seconds: 2820.0,
                position_seconds: self.position,
                progress_percent: self.percent,
                is_completed: self.completed,
                last_watched_at: Some(self.watched_at.into()),
                ..Default::default()
            }
        }
    }

    fn map(entries: Vec<P>) -> HashMap<String, WatchProgress> {
        progress_map(&entries.into_iter().map(P::build).collect::<Vec<_>>())
    }

    fn sref(label: &str, url: &str) -> SeasonRef {
        SeasonRef { label: label.into(), url: url.into() }
    }
    fn s1() -> SeasonRef { sref("Season 1", "http://x/season-1") }
    fn s5() -> SeasonRef { sref("Season 5", "http://x/season-5") }
    fn seasons() -> Vec<SeasonRef> { vec![s1(), sref("Season 2", "http://x/season-2"), s5()] }

    // ── which season ────────────────────────────────────────────────────────

    #[test]
    fn nothing_watched_opens_the_first_season() {
        let seasons = seasons();
        assert_eq!(resume_season(&seasons, &HashMap::new(), None), Some(&s1()));
    }

    #[test]
    fn the_season_watched_most_recently_wins() {
        let seasons = seasons();
        let progress = map(vec![
            p("EP3").season(Some("http://x/season-1")).at("2026-08-01T00:00:00.000Z"),
            p("EP6").season(Some("http://x/season-5")).at("2026-08-20T00:00:00.000Z"),
        ]);
        assert_eq!(resume_season(&seasons, &progress, None), Some(&s5()));
    }

    /// The reported bug: a favourite made on season one pinned every later visit to it.
    #[test]
    fn progress_beats_the_season_a_favourite_remembers() {
        let seasons = seasons();
        let progress = map(vec![p("EP6").season(Some("http://x/season-5")).at("2026-08-20T00:00:00.000Z")]);
        assert_eq!(resume_season(&seasons, &progress, Some("http://x/season-1")), Some(&s5()));
    }

    #[test]
    fn the_remembered_season_is_used_when_nothing_has_been_watched() {
        let seasons = seasons();
        assert_eq!(resume_season(&seasons, &HashMap::new(), Some("http://x/season-5")), Some(&s5()));
    }

    #[test]
    fn a_season_that_no_longer_exists_falls_through_to_the_first() {
        let seasons = seasons();
        let progress = map(vec![p("EP1").season(Some("http://x/season-gone"))]);
        assert_eq!(resume_season(&seasons, &progress, Some("http://x/also-gone")), Some(&s1()));
    }

    #[test]
    fn progress_carrying_no_season_is_ignored_rather_than_blanking_the_choice() {
        let seasons = seasons();
        let progress = map(vec![p("EP1").season(None).at("2026-08-20T00:00:00.000Z")]);
        assert_eq!(resume_season(&seasons, &progress, Some("http://x/season-5")), Some(&s5()));
    }

    #[test]
    fn no_seasons_at_all_resumes_nothing() {
        assert_eq!(resume_season(&[], &HashMap::new(), Some("http://x/season-1")), None);
    }

    // ── which episode ───────────────────────────────────────────────────────

    #[test]
    fn nothing_watched_starts_at_the_first_episode() {
        assert_eq!(resume_episode(&episodes(), Some(SEASON), &HashMap::new()), Some("EP1".into()));
    }

    #[test]
    fn an_unfinished_episode_is_resumed_not_skipped() {
        let progress = map(vec![p("EP2").position(600.0).percent(21.0)]);
        assert_eq!(resume_episode(&episodes(), Some(SEASON), &progress), Some("EP2".into()));
    }

    #[test]
    fn a_finished_episode_advances_to_the_next() {
        let progress = map(vec![p("EP2").completed().position(2820.0).percent(100.0)]);
        assert_eq!(resume_episode(&episodes(), Some(SEASON), &progress), Some("EP3".into()));
    }

    #[test]
    fn the_most_recently_watched_episode_decides_not_the_furthest_along() {
        let progress = map(vec![
            p("EP3").completed().at("2026-08-01T10:00:00.000Z"),
            // Watched later, and only part way — this is where the user is.
            p("EP1").position(300.0).percent(10.0).at("2026-08-02T09:00:00.000Z"),
        ]);
        assert_eq!(resume_episode(&episodes(), Some(SEASON), &progress), Some("EP1".into()));
    }

    #[test]
    fn finishing_the_last_episode_means_the_season_is_done() {
        let progress = map(vec![p("EP3").completed()]);
        assert_eq!(resume_episode(&episodes(), Some(SEASON), &progress), None);
    }

    #[test]
    fn progress_from_another_season_is_ignored() {
        let other = map(vec![p("EP2").season(Some("http://x/season-2")).completed()]);
        assert_eq!(resume_episode(&episodes(), Some(SEASON), &other), Some("EP1".into()));
    }

    #[test]
    fn a_series_with_no_season_url_still_resumes() {
        let progress = map(vec![p("EP2").season(None).position(100.0).percent(4.0)]);
        assert_eq!(resume_episode(&episodes(), None, &progress), Some("EP2".into()));
    }

    #[test]
    fn a_renamed_episode_label_starts_the_season_over_rather_than_failing() {
        let progress = map(vec![p("EP2 (old label)").completed()]);
        assert_eq!(resume_episode(&episodes(), Some(SEASON), &progress), Some("EP1".into()));
    }

    #[test]
    fn no_episodes_means_nothing_to_resume() {
        assert_eq!(resume_episode(&[], Some(SEASON), &HashMap::new()), None);
    }

    // ── where in the episode ────────────────────────────────────────────────

    #[test]
    fn resuming_rewinds_a_little() {
        assert_eq!(resume_position_seconds(Some(&p("EP1").position(600.0).build())), 570.0);
    }

    #[test]
    fn a_position_inside_the_rewind_window_starts_from_the_beginning() {
        assert_eq!(resume_position_seconds(Some(&p("EP1").position(12.0).build())), 0.0);
        assert_eq!(resume_position_seconds(None), 0.0);
    }

    #[test]
    fn rewatching_a_finished_episode_starts_from_the_beginning() {
        // Deliberately unlike the web player, which would land 30s from the end.
        let finished = p("EP1").completed().position(2820.0).percent(100.0).build();
        assert_eq!(resume_position_seconds(Some(&finished)), 0.0);
    }

    // ── what plays next ─────────────────────────────────────────────────────

    #[test]
    fn up_next_is_offered_only_near_the_end() {
        assert!(!should_offer_up_next(2820.0, 2600.0));
        assert!(should_offer_up_next(2820.0, 2701.0));
        // A seek backwards withdraws the offer again.
        assert!(!should_offer_up_next(2820.0, 2700.0));
        // Unknown duration cannot be near anything.
        assert!(!should_offer_up_next(0.0, 100.0));
    }

    #[test]
    fn up_next_is_the_following_episode() {
        assert_eq!(up_next(&episodes(), Some("EP1"), &[], None), Some(UpNext::Episode("EP2".into())));
    }

    #[test]
    fn the_last_episode_rolls_over_to_the_next_season() {
        let seasons = vec![sref("S1", SEASON), sref("S2", "http://x/season-2")];
        let next = up_next(&episodes(), Some("EP3"), &seasons, Some(SEASON));
        assert_eq!(next, Some(UpNext::Season(sref("S2", "http://x/season-2"))));
    }

    #[test]
    fn the_last_episode_of_the_last_season_ends() {
        let seasons = vec![sref("S1", SEASON)];
        assert_eq!(up_next(&episodes(), Some("EP3"), &seasons, Some(SEASON)), None);
    }

    #[test]
    fn a_provider_with_no_season_list_simply_ends() {
        assert_eq!(up_next(&episodes(), Some("EP3"), &[], None), None);
    }

    #[test]
    fn an_episode_that_is_not_in_the_list_has_no_successor() {
        assert_eq!(up_next(&episodes(), Some("EP99"), &[], None), None);
    }

    // ── season status ───────────────────────────────────────────────────────

    #[test]
    fn season_status_reflects_the_episodes_touched() {
        assert_eq!(season_status(Some(SEASON), &HashMap::new()), SeasonStatus::Unwatched);
        assert_eq!(
            season_status(Some(SEASON), &map(vec![p("EP1").completed(), p("EP2").completed()])),
            SeasonStatus::Completed
        );
        assert_eq!(
            season_status(Some(SEASON), &map(vec![p("EP1").completed(), p("EP2").percent(40.0)])),
            SeasonStatus::InProgress
        );
        // Started but with nothing actually watched yet reads as untouched.
        assert_eq!(season_status(Some(SEASON), &map(vec![p("EP1").percent(0.0)])), SeasonStatus::Unwatched);
    }

    #[test]
    fn season_status_only_counts_its_own_season() {
        let other = map(vec![p("EP1").season(Some("http://x/season-2")).completed()]);
        assert_eq!(season_status(Some(SEASON), &other), SeasonStatus::Unwatched);
    }

    #[test]
    fn previous_episode_steps_back_within_the_season() {
        assert_eq!(previous_episode(&episodes(), Some("EP2")), Some("EP1".into()));
        assert_eq!(previous_episode(&episodes(), Some("EP3")), Some("EP2".into()));
    }

    #[test]
    fn previous_episode_is_null_at_the_front_edge_and_for_unknown_episodes() {
        assert_eq!(previous_episode(&episodes(), Some("EP1")), None);
        assert_eq!(previous_episode(&episodes(), Some("EP9")), None);
        assert_eq!(previous_episode(&episodes(), None), None);
    }

    #[test]
    fn tie_on_last_watched_resumes_earliest_episode_like_kotlin() {
        let at = Some("2026-11-11T07:00:00.000Z".to_string());
        let rows = vec![
            WatchProgress { season_url: Some("s".into()), episode_label: Some("EP3".into()), last_watched_at: at.clone(), ..Default::default() },
            WatchProgress { season_url: Some("s".into()), episode_label: Some("EP1".into()), last_watched_at: at.clone(), ..Default::default() },
        ];
        let map = progress_map(&rows);
        let eps: Vec<String> = ["EP1", "EP2", "EP3"].iter().map(|s| s.to_string()).collect();
        for _ in 0..20 {
            assert_eq!(resume_episode(&eps, Some("s"), &map).as_deref(), Some("EP1"));
        }
    }

    #[test]
    fn tie_on_last_watched_resumes_first_listed_season_like_kotlin() {
        let at = Some("2026-11-11T07:00:00.000Z".to_string());
        let rows = vec![
            WatchProgress { season_url: Some("s2".into()), episode_label: Some("a".into()), last_watched_at: at.clone(), ..Default::default() },
            WatchProgress { season_url: Some("s1".into()), episode_label: Some("b".into()), last_watched_at: at.clone(), ..Default::default() },
        ];
        let seasons = vec![SeasonRef { label: "1".into(), url: "s1".into() }, SeasonRef { label: "2".into(), url: "s2".into() }];
        for _ in 0..20 {
            let map = progress_map(&rows);
            assert_eq!(resume_season(&seasons, &map, None).map(|s| s.url.as_str()), Some("s2"));
        }
    }
}
