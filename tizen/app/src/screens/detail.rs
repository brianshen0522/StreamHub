//! A title, ready to play (android/tv detail/DetailScreen.kt). Everything is
//! arranged around one press: Play starts the episode the viewer would resume,
//! at the position they would resume, and is focused only once that is known
//! — a Play button focused while still inert swallows the first OK.
//!
//! `/api/item` is polymorphic: `seasons` (movieffm hub → season rail plus
//! `/api/episodes`), `episodes` (a season or single-page series), `streams`
//! (a movie). Sources are the player's job (`PlayTarget.source = None`).

use std::collections::HashMap;

use leptos::prelude::*;
use leptos::task::spawn_local;
use serde_json::{json, Value};
use streamhub_core::entry::{entry_episode, entry_season, EntryPoint, EpisodePick};
use streamhub_core::models::{SeasonRef, WatchProgress};
use streamhub_core::resume::{progress_key, progress_map, resume_position_seconds};

use crate::api;
use crate::focus;
use crate::nav::{self, DetailTarget, PlayTarget, Route};
use crate::screens::common::{self, array, clock, media_type, opt, s, unavailable_label, Heart, Load};
use crate::screens::search;

#[derive(Clone, Debug, PartialEq)]
enum Phase {
    Loading,
    Ready,
    /// `/api/item` answered 404: the provider dropped the title.
    Gone,
    Failed(String),
}

#[derive(Clone, Copy, Debug, PartialEq)]
enum Kind {
    Movie,
    Series,
}

fn strings(v: &Value, key: &str) -> Vec<String> {
    array(v, key).iter().filter_map(|e| e.as_str().map(str::to_string)).collect()
}

fn offline_or(e: &api::ApiError) -> String {
    common::friendly_error(e)
}

#[derive(Clone, Copy)]
struct State {
    alive: RwSignal<bool>,
    phase: RwSignal<Phase>,
    kind: RwSignal<Kind>,
    title: RwSignal<String>,
    poster: RwSignal<String>,
    media: RwSignal<String>,
    description: RwSignal<String>,
    detail_url: RwSignal<String>,
    seasons: RwSignal<Vec<SeasonRef>>,
    season: RwSignal<Option<SeasonRef>>,
    /// `seasonUrl` from the item itself (single-page series), the progress key.
    item_season_url: RwSignal<Option<String>>,
    episodes: RwSignal<Load<Vec<String>>>,
    episode: RwSignal<Option<String>>,
    progress: RwSignal<HashMap<String, WatchProgress>>,
    favorites: RwSignal<Vec<Value>>,
    fav_busy: RwSignal<bool>,
    fav_error: RwSignal<Option<String>>,
    /// Bumped by every season request; an answer for an older one is
    /// dropped (S2 then S1 quickly must not leave S2's episodes under S1).
    season_seq: StoredValue<u64>,
}

impl State {
    fn dead(&self) -> bool {
        self.alive.try_get_untracked() != Some(true)
    }

    /// The season URL progress and favourites are keyed by.
    fn season_key(&self) -> Option<String> {
        self.season
            .get_untracked()
            .map(|s| s.url)
            .or_else(|| self.item_season_url.get_untracked())
            .filter(|u| !u.is_empty())
    }

    fn position_for(&self, episode: Option<&str>) -> f64 {
        let key = progress_key(self.season_key().as_deref(), episode);
        self.progress.with_untracked(|p| resume_position_seconds(p.get(&key)))
    }
}

/// Load a season's episodes and pick the one to open. `initial` applies the
/// entry point and rolls a finished season over to the next (as the web does);
/// a season picked on the rail resumes within itself.
async fn open_season(st: State, target: DetailTarget, season: SeasonRef, initial: bool) {
    st.season_seq.update_value(|n| *n += 1);
    let mine = st.season_seq.get_value();
    st.season.set(Some(season.clone()));
    st.episodes.set(Load::Loading);
    st.episode.set(None);
    let path = format!(
        "/episodes{}",
        api::query(&[("provider", &target.provider), ("sourceUrl", &season.url)])
    );
    let result = api::get(&path).await;
    // Gone, or another season was picked while this one loaded: the newer
    // request owns the rail.
    if st.dead() || st.season_seq.try_get_value() != Some(mine) {
        return;
    }
    let eps = match result {
        Ok(v) => strings(&v, "episodes"),
        Err(e) => {
            st.episodes.set(Load::Failed(offline_or(&e)));
            return;
        }
    };
    let entry = EntryPoint {
        season_url: if initial { target.season_url.as_deref() } else { None },
        episode_label: if initial { target.episode_label.as_deref() } else { None },
        from_history: initial && target.from_history,
    };
    let pick = st.progress.with_untracked(|p| entry_episode(&eps, Some(&season.url), p, &entry));
    match pick {
        EpisodePick::Episode(ep) => {
            st.episodes.set(Load::Ready(eps));
            st.episode.set(Some(ep));
        }
        EpisodePick::Empty => st.episodes.set(Load::Ready(eps)),
        EpisodePick::SeasonFinished => {
            let seasons = st.seasons.get_untracked();
            let index = seasons.iter().position(|s| s.url == season.url);
            let next = index.and_then(|i| seasons.get(i + 1)).cloned();
            match (initial, next) {
                // Finished this season: open the next one at its start.
                (true, Some(next)) => Box::pin(open_season(st, target, next, false)).await,
                // Everything watched: back to the very beginning.
                (true, None) if index != Some(0) && !seasons.is_empty() => {
                    Box::pin(open_season(st, target, seasons[0].clone(), false)).await
                }
                _ => {
                    st.episode.set(eps.first().cloned());
                    st.episodes.set(Load::Ready(eps));
                }
            }
        }
    }
}

async fn load(st: State, target: DetailTarget) {
    st.phase.set(Phase::Loading);
    // Progress first: it decides which season and episode open.
    let progress_path = format!(
        "/me/progress{}",
        api::query(&[("providerKey", &target.provider), ("itemUrl", &target.url)])
    );
    let item_path = format!(
        "/item{}",
        api::query(&[
            ("provider", &target.provider),
            ("url", &target.url),
            ("title", &target.title),
            ("mediaType", if target.media_type.is_empty() { "unknown" } else { &target.media_type }),
            ("posterUrl", &target.poster_url),
        ])
    );
    let (progress, item, favs) =
        futures::join!(api::get(&progress_path), api::get(&item_path), api::get("/me/favorites"));
    if st.dead() {
        return;
    }
    let rows: Vec<WatchProgress> = progress
        .ok()
        .map(|v| array(&v, "progress"))
        .unwrap_or_default()
        .into_iter()
        .filter_map(|v| serde_json::from_value(v).ok())
        .collect();
    st.progress.set(progress_map(&rows));
    if let Ok(f) = favs {
        st.favorites.set(
            array(&f, "favorites")
                .into_iter()
                .filter(|r| s(r, "providerKey") == target.provider && s(r, "itemUrl") == target.url)
                .collect(),
        );
    }
    let item = match item {
        Ok(v) => v,
        Err(e) if e.status == 404 => {
            st.phase.set(Phase::Gone);
            return;
        }
        Err(e) => {
            st.phase.set(Phase::Failed(offline_or(&e)));
            return;
        }
    };
    if let Some(t) = opt(&item, "title") {
        st.title.set(t);
    }
    if let Some(p) = opt(&item, "posterUrl") {
        st.poster.set(p);
    }
    if let Some(m) = opt(&item, "mediaType") {
        st.media.set(m);
    }
    st.description.set(
        opt(&item, "description").or_else(|| opt(&item, "summary")).unwrap_or_default(),
    );
    st.detail_url.set(
        opt(&item, "detailUrl").or_else(|| opt(&item, "seasonUrl")).unwrap_or_else(|| target.url.clone()),
    );

    let seasons: Vec<SeasonRef> = array(&item, "seasons")
        .into_iter()
        .filter_map(|v| serde_json::from_value(v).ok())
        .filter(|s: &SeasonRef| !s.url.is_empty())
        .collect();
    let entry = EntryPoint {
        season_url: target.season_url.as_deref(),
        episode_label: target.episode_label.as_deref(),
        from_history: target.from_history,
    };

    if !seasons.is_empty() {
        st.kind.set(Kind::Series);
        let chosen = st.progress.with_untracked(|p| entry_season(&seasons, p, &entry).cloned());
        st.seasons.set(seasons.clone());
        st.phase.set(Phase::Ready);
        if let Some(season) = chosen.or_else(|| seasons.first().cloned()) {
            open_season(st, target, season, true).await;
        }
    } else if item.get("episodes").is_some() {
        st.kind.set(Kind::Series);
        let season_url = opt(&item, "seasonUrl");
        st.item_season_url.set(season_url.clone());
        let eps = strings(&item, "episodes");
        let pick = st.progress.with_untracked(|p| entry_episode(&eps, season_url.as_deref(), p, &entry));
        st.episode.set(match pick {
            EpisodePick::Episode(ep) => Some(ep),
            _ => eps.first().cloned(),
        });
        st.episodes.set(Load::Ready(eps));
        st.phase.set(Phase::Ready);
    } else {
        st.kind.set(Kind::Movie);
        st.episodes.set(Load::Ready(Vec::new()));
        st.phase.set(Phase::Ready);
    }
}

thread_local! {
    /// A title the Play key was pressed on elsewhere (a Home or Search card):
    /// Detail resolves where to resume, then replaces itself with the player.
    static AUTOPLAY: std::cell::RefCell<Option<(String, String)>> = const { std::cell::RefCell::new(None) };
}

/// Ask the next Detail for (provider, url) to start playing as soon as it
/// knows what and where — the Play key on a card. Call before pushing Detail.
pub fn request_autoplay(provider: &str, url: &str) {
    AUTOPLAY.with(|a| *a.borrow_mut() = Some((provider.to_string(), url.to_string())));
}

fn take_autoplay(provider: &str, url: &str) -> bool {
    AUTOPLAY.with(|a| {
        let mut a = a.borrow_mut();
        let hit = a.as_ref().is_some_and(|(p, u)| p == provider && u == url);
        if hit || a.is_some() {
            *a = None;
        }
        hit
    })
}

/// `replace`: the player takes Detail's place in the stack (autoplay), so
/// Back from it returns to where Play was pressed.
fn play(st: State, target: &DetailTarget, episode: Option<String>, from_start: bool, replace: bool) {
    let is_movie = st.kind.get_untracked() == Kind::Movie;
    if !is_movie && episode.is_none() {
        return;
    }
    let start = if from_start { 0.0 } else { st.position_for(episode.as_deref()) };
    let episodes = match st.episodes.get_untracked() {
        Load::Ready(e) => e,
        _ => Vec::new(),
    };
    let season_url = st.season_key().unwrap_or_default();
    let season_label = st
        .seasons
        .with_untracked(|seasons| seasons.iter().find(|s| s.url == season_url).map(|s| s.label.clone()))
        .unwrap_or_default();
    let media_type = if is_movie { "movie".to_string() } else { "tv".to_string() };
    let route = Route::Player(PlayTarget {
        provider: target.provider.clone(),
        item_url: target.url.clone(),
        season_url,
        title: st.title.get_untracked(),
        poster_url: st.poster.get_untracked(),
        media_type,
        season_label,
        episode_label: episode.unwrap_or_default(),
        episodes,
        source: None,
        start_seconds: Some(start),
        from_cast: false,
    });
    if replace {
        nav::replace(route);
    } else {
        nav::push(route);
    }
}

fn toggle_favorite(st: State, target: DetailTarget) {
    if st.fav_busy.get_untracked() {
        return;
    }
    st.fav_busy.set(true);
    st.fav_error.set(None);
    let existing = st.favorites.get_untracked();
    spawn_local(async move {
        let result = if existing.is_empty() {
            // Keyed (providerKey, itemUrl, seasonUrl, episodeLabel), absent as
            // "": sent exactly as the progress rows carry them.
            let poster = st.poster.get_untracked();
            let body = json!({
                "providerKey": target.provider,
                "mediaType": media_type(&st.media.get_untracked()),
                "title": st.title.get_untracked(),
                "posterUrl": if poster.starts_with("http") { poster } else { String::new() },
                "itemUrl": target.url,
                "detailUrl": st.detail_url.get_untracked(),
                "seasonUrl": st.season_key().unwrap_or_default(),
                "seasonLabel": st.season.get_untracked().map(|s| s.label).unwrap_or_default(),
                "episodeLabel": st.episode.get_untracked().unwrap_or_default(),
            });
            api::request("POST", "/me/favorites", Some(&body))
                .await
                .map(|v| v.get("favorite").cloned().into_iter().collect::<Vec<_>>())
        } else {
            // The heart stands for the title: un-hearting removes every
            // favourite row of it, whichever episode it was tapped on.
            let mut failed = None;
            for row in &existing {
                let path = format!("/me/favorites/{}", js_sys::encode_uri_component(&s(row, "id")));
                if let Err(e) = api::request("DELETE", &path, None).await {
                    failed = Some(e);
                }
            }
            match failed {
                Some(e) => Err(e),
                None => Ok(Vec::new()),
            }
        };
        if st.dead() {
            return;
        }
        st.fav_busy.set(false);
        match result {
            Ok(rows) => st.favorites.set(rows),
            Err(e) => st.fav_error.set(Some(offline_or(&e))),
        }
    });
}

#[component]
pub fn DetailScreen(target: DetailTarget) -> impl IntoView {
    let st = State {
        alive: RwSignal::new(true),
        phase: RwSignal::new(Phase::Loading),
        kind: RwSignal::new(Kind::Series),
        title: RwSignal::new(target.title.clone()),
        poster: RwSignal::new(target.poster_url.clone()),
        media: RwSignal::new(target.media_type.clone()),
        description: RwSignal::new(String::new()),
        detail_url: RwSignal::new(target.url.clone()),
        seasons: RwSignal::new(Vec::new()),
        season: RwSignal::new(None),
        item_season_url: RwSignal::new(None),
        episodes: RwSignal::new(Load::Loading),
        episode: RwSignal::new(None),
        progress: RwSignal::new(HashMap::new()),
        favorites: RwSignal::new(Vec::new()),
        fav_busy: RwSignal::new(false),
        fav_error: RwSignal::new(None),
        season_seq: StoredValue::new(0),
    };
    on_cleanup(move || st.alive.set(false));
    spawn_local(load(st, target.clone()));

    let ready = Memo::new(move |_| {
        st.phase.get() == Phase::Ready
            && match st.kind.get() {
                Kind::Movie => true,
                Kind::Series => st.episode.get().is_some(),
            }
    });
    // Focus Play the first time it can actually play (or wherever focus was
    // when this screen was left, coming back from the player).
    // Copy handles, so the view's Fn closures can reach the target.
    let tv = StoredValue::new(target.clone());
    common::ensure_provider_names();
    Effect::new(move |done: Option<bool>| {
        if done == Some(true) {
            return true;
        }
        if !ready.get() {
            return false;
        }
        let t = tv.get_value();
        if take_autoplay(&t.provider, &t.url) {
            play(st, &t, st.episode.get_untracked(), false, true);
            return true;
        }
        gloo_timers::callback::Timeout::new(0, || {
            if !focus::restore() {
                focus::focus_selector("[data-action=play]");
            }
        })
        .forget();
        true
    });

    // Play/PlayPause anywhere on Detail plays (resume rules, like OK on 播放);
    // Back from deep in the rows returns to 播放 first, then leaves.
    let live = focus::screen_alive();
    let live_in = live.clone();
    let handler: focus::KeyHandler = std::rc::Rc::new(move |key| {
        if !live_in.load(std::sync::atomic::Ordering::Relaxed) {
            return false;
        }
        match key {
            crate::keys::Key::Play | crate::keys::Key::PlayPause => {
                if ready.get_untracked() {
                    play(st, &tv.get_value(), st.episode.get_untracked(), false, false);
                    return true;
                }
                false
            }
            crate::keys::Key::Back => {
                !focus::focused_within("[data-focus-group=detail-actions]") && focus::back_to("[data-action=play]")
            }
            _ => false,
        }
    });
    focus::set_screen_handler(Some(handler));
    on_cleanup(move || live.store(false, std::sync::atomic::Ordering::Relaxed));

    let badge = target.row.as_ref().and_then(unavailable_label);
    let gone_title = target.title.clone();
    let poster_src = move || common::poster_src(&st.poster.get());

    let provider_key = target.provider.clone();
    // 「MovieFFM · 影集 · 第 1 季 · 11 集」: display names and captions only.
    let chips = move || {
        let mut parts = vec![common::provider_name(&provider_key)];
        parts.push(match st.kind.get() {
            Kind::Movie => "電影".into(),
            Kind::Series => "影集".into(),
        });
        if st.seasons.with(|s| s.len() > 1) {
            if let Some(season) = st.season.get().filter(|s| !s.label.is_empty()) {
                parts.push(streamhub_core::labels::season_parts(&season.label).0);
            }
        }
        if st.kind.get() == Kind::Series {
            if let Load::Ready(e) = st.episodes.get() {
                if !e.is_empty() {
                    parts.push(format!("{} 集", e.len()));
                }
            }
        }
        parts.into_iter().map(|p| view! { <span class="tv-chip-plain">{p}</span> }).collect_view()
    };

    let play_label = move || {
        if !ready.get() {
            return match st.episodes.get() {
                Load::Failed(_) => "無法載入集數".to_string(),
                Load::Ready(ref e) if e.is_empty() && st.kind.get() == Kind::Series => "沒有可播放的集數".into(),
                _ => "載入中…".into(),
            };
        }
        let ep = st.episode.get();
        let _ = st.progress.get();
        let pos = st.position_for(ep.as_deref());
        let ep_text = ep.map(|e| format!(" {}", common::episode_caption(&e))).unwrap_or_default();
        if pos > 0.0 {
            format!("繼續播放{ep_text} · {}", clock(pos))
        } else {
            format!("播放{ep_text}")
        }
    };
    let can_restart = move || ready.get() && {
        let _ = st.progress.get();
        st.position_for(st.episode.get().as_deref()) > 0.0
    };

    let body = move || match st.phase.get() {
        Phase::Loading => view! { <div class="tv-detail-loading"><span class="tv-loading-bar"></span></div> }.into_any(),
        Phase::Gone => {
            let title = gone_title.clone();
            view! {
                <div class="tv-gone">
                    <h2 class="tv-gone-title">"已下架"</h2>
                    <p class="tv-gone-text">"這個來源已經不再提供這部片。到其他來源搜尋看看？"</p>
                    <button
                        class="tv-btn tv-btn-primary"
                        data-focusable=""
                        data-autofocus=""
                        data-action="search-elsewhere"
                        on:click=move |_| {
                            search::prefill(&title);
                            nav::push(Route::Search);
                        }
                    >
                        {format!("搜尋「{}」", gone_title)}
                    </button>
                </div>
            }
            .into_any()
        }
        Phase::Failed(msg) => {
            view! {
                <div class="tv-gone">
                    <p class="tv-error">{msg}</p>
                    <button
                        class="tv-btn tv-btn-primary"
                        data-focusable=""
                        data-autofocus=""
                        data-action="retry"
                        on:click=move |_| spawn_local(load(st, tv.get_value()))
                    >
                        "重試"
                    </button>
                </div>
            }
            .into_any()
        }
        Phase::Ready => ().into_any(),
    };

    let is_fav = Signal::derive(move || !st.favorites.get().is_empty());

    view! {
        <div class="tv-screen tv-detail" data-screen="detail">
            <div class="tv-backdrop tv-backdrop-detail" aria-hidden="true">
                {move || {
                    let src = poster_src();
                    (!src.is_empty()).then(|| view! {
                        <div class="tv-backdrop-layer is-shown">
                            <img class="tv-backdrop-wash" src=src.clone() alt=""/>
                            <img class="tv-backdrop-img" src=src alt=""/>
                        </div>
                    })
                }}
                <div class="tv-scrim"></div>
            </div>
            <div class="tv-detail-col" data-row-column="" data-row-anchor="720">
                <section class="tv-detail-info" data-row="">
                    <h1 class="tv-detail-title">{move || st.title.get()}</h1>
                    <p class="tv-detail-chips">
                        {chips}
                        {badge.map(|b| view! { <span class="tv-badge tv-badge-warn">{b}</span> })}
                    </p>
                    {move || {
                        let d = st.description.get();
                        (!d.is_empty()).then(|| view! { <p class="tv-detail-desc">{d}</p> })
                    }}
                    {body}
                    <Show when=move || st.phase.get() == Phase::Ready>
                        <div class="tv-detail-actions" data-focus-group="detail-actions">
                            <button
                                class="tv-btn tv-btn-primary tv-btn-play"
                                data-focusable=""
                                data-action="play"
                                data-focus-id="detail-play"
                                aria-disabled=move || (!ready.get()).then_some("true")
                                on:click=move |_| {
                                    if ready.get_untracked() {
                                        play(st, &tv.get_value(), st.episode.get_untracked(), false, false)
                                    }
                                }
                            >
                                <svg class="tv-icon tv-icon-fill" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 4.5v15l13-7.5z"/></svg>
                                <span class="tv-num">{play_label}</span>
                            </button>
                            <Show when=can_restart>
                                <button
                                    class="tv-btn"
                                    data-focusable=""
                                    data-action="restart"
                                    data-focus-id="detail-restart"
                                    on:click=move |_| play(st, &tv.get_value(), st.episode.get_untracked(), true, false)
                                >
                                    <svg class="tv-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12a8 8 0 1 0 2.4-5.7M4 4v4.5h4.5"/></svg>
                                    "從頭播放"
                                </button>
                            </Show>
                            <button
                                class="tv-btn"
                                class:is-active=move || is_fav.get()
                                data-focusable=""
                                data-action="favorite"
                                data-focus-id="detail-favorite"
                                on:click=move |_| toggle_favorite(st, tv.get_value())
                            >
                                <Heart filled=is_fav/>
                                {move || if is_fav.get() { "已收藏" } else { "加入收藏" }}
                            </button>
                        </div>
                        <p class="tv-error">{move || st.fav_error.get().unwrap_or_default()}</p>
                    </Show>
                </section>

                <Show when=move || { st.seasons.get().len() > 1 }>
                    <section class="tv-row tv-row-compact" data-row="">
                        <h2 class="tv-row-title">"季"</h2>
                        <div class="tv-rail">
                            <div class="tv-track tv-segmented" data-rail-track="" data-focus-group="detail-seasons">
                                {move || {
                                    let current = st.season.get().map(|s| s.url);
                                    st.seasons
                                        .get()
                                        .into_iter()
                                        .map(|season| {
                                            let selected = current.as_deref() == Some(season.url.as_str());
                                            // 「第 2 季」 over the site's "201510集全".
                                            let (main, rest) = if season.label.is_empty() {
                                                ("季".to_string(), String::new())
                                            } else {
                                                streamhub_core::labels::season_parts(&season.label)
                                            };
                                            let id = format!("season:{}", season.url);
                                            view! {
                                                <button
                                                    class="tv-btn tv-btn-seg"
                                                    class:is-selected=selected
                                                    class:has-sub=!rest.is_empty()
                                                    data-focusable=""
                                                    data-focus-id=id
                                                    on:click=move |_| {
                                                        if st.season.get_untracked().map(|s| s.url) != Some(season.url.clone()) {
                                                            spawn_local(open_season(st, tv.get_value(), season.clone(), false));
                                                        }
                                                    }
                                                >
                                                    <span class="tv-seg-main">{main}</span>
                                                    {(!rest.is_empty()).then(|| view! { <span class="tv-seg-sub">{rest.clone()}</span> })}
                                                </button>
                                            }
                                        })
                                        .collect_view()
                                }}
                            </div>
                        </div>
                    </section>
                </Show>

                <Show when=move || st.phase.get() == Phase::Ready && st.kind.get() == Kind::Series>
                    <section class="tv-row tv-row-compact" data-row="">
                        <h2 class="tv-row-title">
                            "集數"
                            {move || match st.episodes.get() {
                                Load::Ready(e) => Some(view! { <span class="tv-row-note">{e.len().to_string()}</span> }),
                                _ => None,
                            }}
                        </h2>
                        <div class="tv-rail">
                            <div class="tv-track tv-episodes" data-rail-track="" data-focus-group="detail-episodes">
                                {move || match st.episodes.get() {
                                    Load::Loading => view! {
                                        {(0..7).map(|_| view! { <div class="tv-ep tv-skeleton" aria-hidden="true"></div> }).collect_view()}
                                    }
                                    .into_any(),
                                    Load::Failed(e) => view! { <div class="tv-row-caption is-error">{e}</div> }.into_any(),
                                    Load::Ready(eps) => {
                                        let current = st.episode.get();
                                        let season = st.season_key();
                                        let progress = st.progress.get();
                                        eps.into_iter()
                                            .map(|ep| {
                                                let p = progress.get(&progress_key(season.as_deref(), Some(&ep)));
                                                let done = p.is_some_and(|p| p.is_completed);
                                                let partial = p.filter(|p| !p.is_completed && p.progress_percent > 0.0);
                                                let pct = partial.map(|p| p.progress_percent).unwrap_or(0.0);
                                                // Status only where there is one: in progress
                                                // (time left) or finished; unwatched is just the caption.
                                                let caption = if done {
                                                    Some("已看完".to_string())
                                                } else {
                                                    partial.map(|p| {
                                                        let left = (p.duration_seconds - p.position_seconds).max(0.0);
                                                        if p.duration_seconds > 0.0 {
                                                            format!("剩 {} 分鐘", (left / 60.0).ceil().max(1.0) as u64)
                                                        } else {
                                                            format!("看到 {}", clock(p.position_seconds))
                                                        }
                                                    })
                                                };
                                                let selected = current.as_deref() == Some(ep.as_str());
                                                let id = format!("ep:{ep}");
                                                let label = common::episode_caption(&ep);
                                                view! {
                                                    <button
                                                        class="tv-ep"
                                                        class:is-selected=selected
                                                        class:is-watched=done
                                                        data-focusable=""
                                                        data-focus-id=id
                                                        on:click=move |_| {
                                                            st.episode.set(Some(ep.clone()));
                                                            play(st, &tv.get_value(), Some(ep.clone()), false, false);
                                                        }
                                                    >
                                                        <span class="tv-ep-label">{label}</span>
                                                        {caption.map(|c| view! {
                                                            <span class="tv-ep-caption tv-num">
                                                                {done.then(|| view! {
                                                                    <svg class="tv-ep-check" viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12.5 4.5 4.5L19 7.5"/></svg>
                                                                })}
                                                                {c}
                                                            </span>
                                                        })}
                                                        {(pct > 0.0).then(|| {
                                                            let w = format!("transform:scaleX({:.3})", (pct / 100.0).clamp(0.03, 1.0));
                                                            view! { <span class="tv-ep-progress"><span class="tv-ep-progress-fill" style=w></span></span> }
                                                        })}
                                                    </button>
                                                }
                                            })
                                            .collect_view()
                                            .into_any()
                                    }
                                }}
                            </div>
                        </div>
                    </section>
                </Show>
            </div>
        </div>
    }
}
