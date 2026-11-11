//! Home: what you were watching, what you saved, what you watched. Rows, not a
//! grid — left/right stays inside one idea, up/down changes it (android/tv
//! home/HomeScreen.kt). Search and sign-out sit above the rails.
//!
//! Look (tizen/DESIGN.md "Home"): a full-bleed backdrop of the focused item,
//! two <img> layers cross-fading after a 200 ms debounce; the hero text over
//! it; the rows column moved by focus.rs so the focused row sits on a fixed
//! line; the top bar slides away while the remote is in the rows.

use std::sync::atomic::Ordering;

use leptos::prelude::*;
use leptos::task::spawn_local;
use serde_json::{json, Value};

use crate::api;
use crate::focus::{self, KeyHandler};
use crate::keys::Key;
use crate::nav::{self, DetailTarget, Route};
use crate::screens::common::{
    array, episode_caption, ensure_provider_names, favorite_toggle_with_toast, friendly_error, num, opt, poster_src,
    provider_name, s, toast, unavailable_label, CardData, FavoriteKey, Load, PosterCard, Rail,
    Skeletons, Toast,
};
use crate::screens::detail;
use crate::session;

const HISTORY_LIMIT: usize = 40;

#[derive(Clone, Debug, PartialEq, Default)]
struct Hero {
    title: String,
    line: String,
    meta: String,
    poster: String,
    badge: Option<String>,
    /// 0–100 for a continue item's bar.
    progress: Option<f64>,
    /// A quiet line on what OK does (continue items).
    hint: Option<String>,
}

/// Where a library row opens. Only history pins its season and episode; a
/// favourite's or a continue row's is a fallback that progress overrides
/// (CLAUDE.md "Where a title opens").
fn target_from_row(row: &Value, from_history: bool) -> DetailTarget {
    DetailTarget {
        provider: s(row, "providerKey"),
        url: s(row, "itemUrl"),
        title: s(row, "title"),
        poster_url: s(row, "posterUrl"),
        media_type: s(row, "mediaType"),
        season_url: opt(row, "seasonUrl"),
        episode_label: opt(row, "episodeLabel"),
        from_history,
        row: Some(row.clone()),
    }
}

fn row_id(prefix: &str, row: &Value) -> String {
    match opt(row, "id") {
        Some(id) => format!("{prefix}:{id}"),
        None => format!(
            "{prefix}:{}:{}:{}:{}",
            s(row, "providerKey"),
            s(row, "itemUrl"),
            s(row, "seasonUrl"),
            s(row, "episodeLabel")
        ),
    }
}

fn continue_card(row: &Value) -> CardData {
    let ep = episode_caption(&s(row, "episodeLabel"));
    let next_up = row.get("nextUp").and_then(Value::as_bool).unwrap_or(false);
    let remaining = (num(row, "durationSeconds") - num(row, "positionSeconds")).max(0.0);
    let subtitle = if next_up {
        format!("{ep} 已看完 · 接著看下一集")
    } else {
        let left = if remaining > 0.0 { format!("剩 {} 分鐘", (remaining / 60.0).ceil() as u64) } else { String::new() };
        [ep.clone(), left].into_iter().filter(|x| !x.is_empty()).collect::<Vec<_>>().join(" · ")
    };
    CardData {
        id: row_id("cw", row),
        title: s(row, "title"),
        subtitle,
        poster: s(row, "posterUrl"),
        // A finished episode carries no progress into the next one.
        progress: if next_up { None } else { Some(num(row, "progressPercent")) },
        badge: unavailable_label(row).map(String::from),
        chip: next_up.then(|| "下一集".to_string()),
        provider: None,
    }
}

fn favorite_card(row: &Value) -> CardData {
    let season = s(row, "seasonLabel");
    let season = if season.is_empty() { season } else { streamhub_core::labels::season_parts(&season).0 };
    let sub = [season, episode_caption(&s(row, "episodeLabel"))]
        .into_iter()
        .filter(|x| !x.is_empty())
        .collect::<Vec<_>>()
        .join(" · ");
    CardData {
        id: row_id("fav", row),
        title: s(row, "title"),
        subtitle: sub,
        poster: s(row, "posterUrl"),
        progress: None,
        badge: unavailable_label(row).map(String::from),
        chip: None,
        provider: Some(s(row, "providerKey")),
    }
}

fn history_card(row: &Value) -> CardData {
    let duration = num(row, "durationSeconds");
    let date = s(row, "watchedAt").chars().take(10).collect::<String>().replace('-', "/");
    let sub = [episode_caption(&s(row, "episodeLabel")), date].into_iter().filter(|x| !x.is_empty()).collect::<Vec<_>>().join(" · ");
    CardData {
        id: row_id("his", row),
        title: s(row, "title"),
        subtitle: sub,
        poster: s(row, "posterUrl"),
        progress: (duration > 0.0).then(|| num(row, "positionSeconds") / duration * 100.0),
        badge: unavailable_label(row).map(String::from),
        chip: None,
        provider: None,
    }
}

/// History holds one row per viewing; consecutive repeats of the same episode
/// say nothing new on a rail.
fn dedupe_history(rows: Vec<Value>) -> Vec<Value> {
    let mut seen = std::collections::HashSet::new();
    rows.into_iter()
        .filter(|r| {
            seen.insert(format!(
                "{}|{}|{}|{}",
                s(r, "providerKey"),
                s(r, "itemUrl"),
                s(r, "seasonUrl"),
                s(r, "episodeLabel")
            ))
        })
        .take(HISTORY_LIMIT)
        .collect()
}

fn hero_of(card: &CardData, row: &Value, kind: RailKind) -> Hero {
    let key = s(row, "providerKey");
    // A favourite with no episode uses the provider as its card subtitle; the
    // hero already prints the provider under it, so it showed twice.
    let line = card.subtitle.clone();
    let next_up = row.get("nextUp").and_then(Value::as_bool).unwrap_or(false);
    Hero {
        title: card.title.clone(),
        line,
        meta: key,
        poster: card.poster.clone(),
        badge: card.badge.clone(),
        progress: card.progress.filter(|p| *p > 0.0),
        hint: (kind == RailKind::Continue).then(|| {
            if next_up { "按 OK 播放下一集".to_string() } else { "按 OK 繼續播放".to_string() }
        }),
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
enum RailKind {
    Continue,
    Favorites,
    History,
}

/// The long-press menu on a card.
#[derive(Clone, Debug, PartialEq)]
struct Menu {
    kind: RailKind,
    row: Value,
    target: DetailTarget,
}

fn favorite_key(row: &Value) -> FavoriteKey {
    FavoriteKey {
        provider: s(row, "providerKey"),
        item_url: s(row, "itemUrl"),
        title: s(row, "title"),
        poster: s(row, "posterUrl"),
        media_type: s(row, "mediaType"),
        season_url: s(row, "seasonUrl"),
        episode_label: s(row, "episodeLabel"),
    }
}

#[component]
fn LibraryRail(
    title: &'static str,
    group: &'static str,
    rows: RwSignal<Load<Vec<Value>>>,
    hero: RwSignal<Option<Hero>>,
    make: fn(&Value) -> CardData,
    kind: RailKind,
    menu: RwSignal<Option<Menu>>,
    #[prop(into)] reload: Callback<()>,
) -> impl IntoView {
    let from_history = kind == RailKind::History;
    move || match rows.get() {
        Load::Loading => view! {
            <Rail title=title group=group>
                <Skeletons count=8/>
            </Rail>
        }
        .into_any(),
        Load::Failed(e) => view! { <Rail title=title group=group caption=e error=true>{()}</Rail> }.into_any(),
        Load::Ready(list) if list.is_empty() => ().into_any(),
        Load::Ready(list) => {
            let count = list.len().to_string();
            view! {
                <Rail title=title group=group note=count>
                    {list
                        .into_iter()
                        .map(|row| {
                            let card = make(&row);
                            let h = hero_of(&card, &row, kind);
                            let target = target_from_row(&row, from_history);
                            let t_open = target.clone();
                            let t_play = target.clone();
                            let fav = favorite_key(&row);
                            let fav_row = row.clone();
                            let on_long = (kind != RailKind::History).then(|| {
                                let row = row.clone();
                                let target = target.clone();
                                Callback::new(move |_: ()| {
                                    menu.set(Some(Menu { kind, row: row.clone(), target: target.clone() }));
                                    focus::focus_later("[data-menu-first]");
                                })
                            });
                            view! {
                                <PosterCard
                                    card=card
                                    on_open=move |_| nav::push(Route::Detail(t_open.clone()))
                                    on_focus=move |_| hero.set(Some(h.clone()))
                                    on_play=move |_| {
                                        detail::request_autoplay(&t_play.provider, &t_play.url);
                                        nav::push(Route::Detail(t_play.clone()));
                                    }
                                    on_long=on_long
                                    on_favorite=move |_| {
                                        if kind == RailKind::Favorites {
                                            remove_favorite(fav_row.clone(), reload);
                                        } else {
                                            favorite_toggle_with_toast(fav.clone(), move || reload.run(()));
                                        }
                                    }
                                />
                            }
                        })
                        .collect_view()}
                </Rail>
            }
            .into_any()
        }
    }
}

/// Un-favourite one favourites row.
fn remove_favorite(row: Value, reload: Callback<()>) {
    spawn_local(async move {
        let path = format!("/me/favorites/{}", js_sys::encode_uri_component(&s(&row, "id")));
        match api::request("DELETE", &path, None).await {
            Ok(_) => toast(format!("已取消收藏：{}", s(&row, "title"))),
            Err(e) => toast(friendly_error(&e)),
        }
        reload.run(());
    });
}

/// Drop a title from 繼續觀看: the card stands for the whole title, so the
/// progress goes by (providerKey, title) — the contract's `scope: "title"`.
fn remove_continue(row: Value, reload: Callback<()>) {
    spawn_local(async move {
        // itemUrl is ignored by the title scope but still required by the route.
        let body = json!({
            "scope": "title",
            "providerKey": s(&row, "providerKey"),
            "itemUrl": s(&row, "itemUrl"),
            "title": s(&row, "title"),
        });
        match api::request("DELETE", "/me/progress", Some(&body)).await {
            Ok(_) => toast(format!("已從繼續觀看移除：{}", s(&row, "title"))),
            Err(e) => toast(friendly_error(&e)),
        }
        reload.run(());
    });
}

async fn load_rows(path: &str, key: &str) -> Load<Vec<Value>> {
    match api::get(path).await {
        Ok(v) => Load::Ready(array(&v, key)),
        Err(e) => Load::Failed(friendly_error(&e)),
    }
}

/// Wait this long after the last focus change before swapping the backdrop,
/// so scrubbing along a rail doesn't decode a poster per card.
const BACKDROP_DEBOUNCE_MS: u32 = 200;
/// Waits between automatic retries of a failed Home load.
const RETRY_BACKOFF_MS: [u32; 5] = [2_000, 4_000, 8_000, 15_000, 30_000];
/// Quiet time after a library event before Home refetches.
const LIBRARY_DEBOUNCE_MS: u32 = 1000;

/// Two stacked <img> layers; the back one takes the new picture and fades in
/// over the front once it has loaded. Never more than two images in the DOM.
#[component]
pub fn Backdrop(#[prop(into)] poster: Signal<String>, alive: RwSignal<bool>) -> impl IntoView {
    let layers = RwSignal::new([String::new(), String::new()]);
    let front = RwSignal::new(0usize);
    // One pending swap at a time: a new focus replaces (and so cancels) the
    // previous timer rather than leaking one closure per focus change.
    let timer = StoredValue::new_local(None::<gloo_timers::callback::Timeout>);
    Effect::new(move |_| {
        let raw = poster.get();
        let url = poster_src(&raw);
        let swap = gloo_timers::callback::Timeout::new(BACKDROP_DEBOUNCE_MS, move || {
            if alive.try_get_untracked() != Some(true) {
                return;
            }
            let shown = front.get_untracked();
            if layers.with_untracked(|l| l[shown] == url) {
                return;
            }
            let back = 1 - shown;
            if url.is_empty() {
                layers.update(|l| l[back].clear());
                front.set(back);
            } else {
                // Flipped by the image's load event, so it never fades in empty.
                layers.update(|l| l[back] = url);
            }
        });
        timer.set_value(Some(swap));
    });
    // Each layer: the poster at its own ratio, right-aligned at full height
    // (no upscaled 16:9 crop), over a faint full-screen wash of the same
    // image; the scrim fades the poster's edges into it.
    let layer = move |i: usize| {
        let src = move || layers.with(|l| l[i].clone());
        view! {
            <div
                class="tv-backdrop-layer"
                class:is-shown=move || front.get() == i && layers.with(|l| !l[i].is_empty())
            >
                <img class="tv-backdrop-wash" src=src alt=""/>
                <img
                    class="tv-backdrop-img"
                    src=src
                    alt=""
                    on:load=move |_| {
                        if layers.with_untracked(|l| !l[i].is_empty()) {
                            front.set(i);
                        }
                    }
                />
            </div>
        }
    };
    view! {
        <div class="tv-backdrop" aria-hidden="true">
            {layer(0)}
            {layer(1)}
            <div class="tv-scrim"></div>
        </div>
    }
}

#[component]
pub fn HomeScreen() -> impl IntoView {
    let cont = RwSignal::new(Load::<Vec<Value>>::Loading);
    let favs = RwSignal::new(Load::<Vec<Value>>::Loading);
    let hist = RwSignal::new(Load::<Vec<Value>>::Loading);
    let hero = RwSignal::new(None::<Hero>);
    ensure_provider_names();
    let confirming = RwSignal::new(false);
    let menu = RwSignal::new(None::<Menu>);
    // True while the remote is in the rows: the top bar steps aside.
    let browsing = RwSignal::new(false);
    let alive = RwSignal::new(true);
    on_cleanup(move || alive.set(false));

    // Loads are numbered: an older answer landing after a newer one is
    // dropped. Rails are only replaced when their rows changed, so a library
    // event that changes nothing moves nothing on screen.
    let seq = StoredValue::new(0u64);
    // Until the first answer, the screen is `[data-loading]`: the focus
    // watchdog then leaves focus alone instead of parking it on 搜尋.
    let first_loading = RwSignal::new(true);
    let keys_at_mount = focus::key_count();
    // A failed rail (server down, Wi-Fi not up yet at power-on) retries by
    // itself with backoff; any success resets it.
    let retry_tick = RwSignal::new(0u64);
    let retry_attempt = StoredValue::new(0usize);
    let retry_timer = StoredValue::new_local(None::<gloo_timers::callback::Timeout>);
    let load = move || {
        seq.update_value(|n| *n += 1);
        let mine = seq.get_value();
        spawn_local(async move {
            let (c, f, h) = futures::join!(
                load_rows("/me/continue-watching", "items"),
                load_rows("/me/favorites", "favorites"),
                load_rows("/me/history", "history"),
            );
            if alive.try_get_untracked() != Some(true) || seq.try_get_value() != Some(mine) {
                return;
            }
            // The hero starts on what is most likely wanted: the first thing
            // to continue, else the first favourite.
            if hero.get_untracked().is_none() {
                let first = match (&c, &f) {
                    (Load::Ready(cs), _) if !cs.is_empty() => Some((continue_card(&cs[0]), cs[0].clone(), RailKind::Continue)),
                    (_, Load::Ready(fs)) if !fs.is_empty() => Some((favorite_card(&fs[0]), fs[0].clone(), RailKind::Favorites)),
                    _ => None,
                };
                if let Some((card, row, kind)) = first {
                    hero.set(Some(hero_of(&card, &row, kind)));
                }
            }
            let h = match h {
                Load::Ready(rows) => Load::Ready(dedupe_history(rows)),
                other => other,
            };
            first_loading.set(false);
            let failed = [&c, &f, &h].iter().any(|r| matches!(r, Load::Failed(_)));
            if failed {
                let n = retry_attempt.get_value();
                retry_attempt.set_value(n + 1);
                let delay = RETRY_BACKOFF_MS[n.min(RETRY_BACKOFF_MS.len() - 1)];
                retry_timer.set_value(Some(gloo_timers::callback::Timeout::new(delay, move || {
                    let _ = retry_tick.try_update(|t| *t += 1);
                })));
            } else {
                retry_attempt.set_value(0);
                retry_timer.set_value(None);
            }
            for (signal, value) in [(cont, c), (favs, f), (hist, h)] {
                if signal.with_untracked(|cur| cur != &value) {
                    signal.set(value);
                }
            }
            gloo_timers::callback::Timeout::new(0, move || {
                if alive.try_get_untracked() != Some(true) {
                    return;
                }
                // Arriving (nothing focused yet, nothing pressed): the card
                // the viewer left from, else the first card. Later: focus
                // moves only if what the viewer was on disappeared (a rail
                // reloaded: the same card if it is still there).
                let no_keys = focus::key_count() == keys_at_mount;
                let lost = focus::focused().is_none();
                if (no_keys || lost) && focus::restore() {
                    return;
                }
                if focus::focused().is_none() {
                    focus::focus_selector(".tv-rows [data-focusable]");
                }
            })
            .forget();
        });
    };
    load();
    let reload = Callback::new(move |_: ()| load());
    Effect::new(move |prev: Option<u64>| {
        let tick = retry_tick.get();
        if prev.is_some_and(|p| p != tick) {
            load();
        }
        tick
    });
    // Finishing an episode on the phone shows up here without the remote.
    // A phone playing on the account sends one every ~15 s, often in bursts:
    // wait for a second of quiet, then load once.
    let pending = StoredValue::new_local(None::<gloo_timers::callback::Timeout>);
    Effect::new(move |prev: Option<u64>| {
        let version = crate::realtime::library_version().get();
        if prev.is_some_and(|p| p != version) {
            pending.set_value(Some(gloo_timers::callback::Timeout::new(LIBRARY_DEBOUNCE_MS, move || {
                if alive.try_get_untracked() == Some(true) {
                    load();
                }
            })));
        }
        version
    });

    // Back while asking "sign out?" answers "no".
    let live = focus::screen_alive();
    let live_in = live.clone();
    let handler: KeyHandler = std::rc::Rc::new(move |key| {
        if !live_in.load(Ordering::Relaxed) {
            return false;
        }
        if key != Key::Back {
            return false;
        }
        if menu.get_untracked().is_some() {
            menu.set(None);
            return true;
        }
        if confirming.get_untracked() {
            confirming.set(false);
            focus::focus_later("[data-action=signout]");
            return true;
        }
        // Back walks home first: to the first card, rails at rest; only from
        // there does it reach the exit prompt.
        focus::back_to(".tv-rows [data-focusable]")
    });
    focus::set_screen_handler(Some(handler));
    on_cleanup(move || live.store(false, Ordering::Relaxed));

    let sign_out = move || {
        let refresh = session::current().map(|s| s.refresh_token).unwrap_or_default();
        spawn_local(async move {
            let body = json!({ "refreshToken": refresh });
            let _ = api::request("POST", "/auth/logout", Some(&body)).await;
            session::set(None);
        });
    };

    let name = session::current().map(|s| s.display_name()).unwrap_or_default();
    let all_empty = move || {
        [cont, favs, hist].iter().all(|r| matches!(r.get(), Load::Ready(ref v) if v.is_empty()))
    };
    let backdrop = Signal::derive(move || hero.with(|h| h.as_ref().map(|h| h.poster.clone()).unwrap_or_default()));

    view! {
        <div
            class="tv-screen tv-home"
            class:is-browsing=move || browsing.get()
            data-screen="home"
            data-loading=move || first_loading.get().then_some("")
        >
            <Backdrop poster=backdrop alive=alive/>
            <header class="tv-topbar" on:focusin=move |_| browsing.set(false)>
                <span class="tv-brand">"StreamHub"</span>
                <span class="tv-topbar-user">{name}</span>
                <div class="tv-topbar-actions">
                    <button
                        class="tv-btn tv-btn-primary"
                        data-focusable=""
                        data-autofocus=""
                        data-action="search"
                        data-focus-id="home-search"
                        on:click=move |_| nav::push(Route::Search)
                    >
                        <svg class="tv-icon" viewBox="0 0 24 24" aria-hidden="true">
                            <circle cx="10.5" cy="10.5" r="6.5"/>
                            <path d="m20 20-4.6-4.6"/>
                        </svg>
                        "搜尋"
                    </button>
                    <button
                        class="tv-btn"
                        data-focusable=""
                        data-action="signout"
                        data-focus-id="home-signout"
                        on:click=move |_| {
                            confirming.set(true);
                            focus::focus_later("[data-action=signout-cancel]");
                        }
                    >
                        "登出"
                    </button>
                </div>
            </header>
            <div class="tv-hero">
                {move || match hero.get() {
                    Some(h) => view! {
                        <h1 class="tv-hero-title">{h.title}</h1>
                        <p class="tv-hero-meta">
                            {(!h.line.is_empty()).then(|| view! { <span class="tv-hero-line">{h.line.clone()}</span> })}
                            {(!h.meta.is_empty()).then(|| {
                                let key = h.meta.clone();
                                view! { <span class="tv-chip-plain">{move || provider_name(&key)}</span> }
                            })}
                            {h.badge.map(|b| view! { <span class="tv-badge tv-badge-warn">{b}</span> })}
                        </p>
                        {h.progress.map(|p| {
                            let w = format!("transform:scaleX({:.3})", (p / 100.0).clamp(0.02, 1.0));
                            view! { <div class="tv-hero-progress"><div class="tv-hero-progress-fill" style=w></div></div> }
                        })}
                        {h.hint.map(|t| view! { <p class="tv-hero-hint">{t}</p> })}
                    }
                    .into_any(),
                    None => view! {
                        <h1 class="tv-hero-title">"歡迎回來"</h1>
                        <p class="tv-hero-meta"><span class="tv-hero-line">"選一部繼續看，或按「搜尋」找新片。"</span></p>
                    }
                    .into_any(),
                }}
            </div>
            <div class="tv-rows-viewport">
                <div class="tv-rows" data-row-column="" data-row-anchor="0" on:focusin=move |_| browsing.set(true)>
                    <LibraryRail title="繼續觀看" group="home-continue" rows=cont hero=hero make=continue_card kind=RailKind::Continue menu=menu reload=reload/>
                    <LibraryRail title="我的收藏" group="home-favorites" rows=favs hero=hero make=favorite_card kind=RailKind::Favorites menu=menu reload=reload/>
                    <LibraryRail title="觀看紀錄" group="home-history" rows=hist hero=hero make=history_card kind=RailKind::History menu=menu reload=reload/>
                    <Show when=all_empty>
                        <div class="tv-empty">"還沒有內容。按「搜尋」找點東西來看吧。"</div>
                    </Show>
                </div>
            </div>
            {move || menu.get().map(|m| {
                let title = s(&m.row, "title");
                let row = m.row.clone();
                let target = m.target.clone();
                let primary = match m.kind {
                    RailKind::Continue => view! {
                        <button
                            class="tv-btn"
                            data-focusable=""
                            data-autofocus=""
                            data-menu-first=""
                            data-action="menu-remove"
                            on:click=move |_| {
                                menu.set(None);
                                remove_continue(row.clone(), reload);
                            }
                        >
                            "從清單移除"
                        </button>
                    }
                    .into_any(),
                    _ => view! {
                        <button
                            class="tv-btn"
                            data-focusable=""
                            data-autofocus=""
                            data-menu-first=""
                            data-action="menu-unfavorite"
                            on:click=move |_| {
                                menu.set(None);
                                remove_favorite(row.clone(), reload);
                            }
                        >
                            "取消收藏"
                        </button>
                    }
                    .into_any(),
                };
                view! {
                    <div class="tv-modal-backdrop">
                        <div class="tv-modal tv-menu" data-focus-scope="" role="dialog" data-menu="">
                            <h2 class="tv-modal-title">{title}</h2>
                            <div class="tv-modal-actions">
                                {primary}
                                <button
                                    class="tv-btn"
                                    data-focusable=""
                                    data-action="menu-details"
                                    on:click=move |_| {
                                        menu.set(None);
                                        nav::push(Route::Detail(target.clone()));
                                    }
                                >
                                    "查看詳情"
                                </button>
                            </div>
                        </div>
                    </div>
                }
            })}
            <Toast/>
            <Show when=move || confirming.get()>
                <div class="tv-modal-backdrop">
                    <div class="tv-modal" data-focus-scope="" role="dialog">
                        <h2 class="tv-modal-title">"要登出這台電視嗎？"</h2>
                        <p class="tv-modal-text">"登出後，需要再配對一次或輸入密碼才能使用。"</p>
                        <div class="tv-modal-actions">
                            // Focus lands on staying signed in: a reflexive
                            // second OK cancels rather than signs out.
                            <button
                                class="tv-btn"
                                data-focusable=""
                                data-autofocus=""
                                data-action="signout-cancel"
                                on:click=move |_| {
                                    confirming.set(false);
                                    focus::focus_later("[data-action=signout]");
                                }
                            >
                                "取消"
                            </button>
                            <button
                                class="tv-btn tv-btn-danger"
                                data-focusable=""
                                data-action="signout-confirm"
                                on:click=move |_| sign_out()
                            >
                                "登出"
                            </button>
                        </div>
                    </div>
                </div>
            </Show>
        </div>
    }
}
