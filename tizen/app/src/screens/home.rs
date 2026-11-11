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
use crate::screens::common::{array, num, opt, s, unavailable_label, CardData, Load, PosterCard, Rail, Skeletons};
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
    let ep = s(row, "episodeLabel");
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
    }
}

fn favorite_card(row: &Value) -> CardData {
    let sub = [s(row, "seasonLabel"), s(row, "episodeLabel")]
        .into_iter()
        .filter(|x| !x.is_empty())
        .collect::<Vec<_>>()
        .join(" · ");
    CardData {
        id: row_id("fav", row),
        title: s(row, "title"),
        subtitle: if sub.is_empty() { s(row, "providerKey") } else { sub },
        poster: s(row, "posterUrl"),
        progress: None,
        badge: unavailable_label(row).map(String::from),
        chip: None,
    }
}

fn history_card(row: &Value) -> CardData {
    let duration = num(row, "durationSeconds");
    let date = s(row, "watchedAt").chars().take(10).collect::<String>().replace('-', "/");
    let sub = [s(row, "episodeLabel"), date].into_iter().filter(|x| !x.is_empty()).collect::<Vec<_>>().join(" · ");
    CardData {
        id: row_id("his", row),
        title: s(row, "title"),
        subtitle: sub,
        poster: s(row, "posterUrl"),
        progress: (duration > 0.0).then(|| num(row, "positionSeconds") / duration * 100.0),
        badge: unavailable_label(row).map(String::from),
        chip: None,
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

fn hero_of(card: &CardData, row: &Value) -> Hero {
    let meta = s(row, "providerKey");
    // A favourite with no episode uses the provider as its card subtitle; the
    // hero already prints the provider under it, so it showed twice.
    let line = if card.subtitle == meta { String::new() } else { card.subtitle.clone() };
    Hero {
        title: card.title.clone(),
        line,
        meta,
        poster: card.poster.clone(),
        badge: card.badge.clone(),
        progress: card.progress.filter(|p| *p > 0.0),
    }
}

#[component]
fn LibraryRail(
    title: &'static str,
    group: &'static str,
    rows: RwSignal<Load<Vec<Value>>>,
    hero: RwSignal<Option<Hero>>,
    make: fn(&Value) -> CardData,
    from_history: bool,
) -> impl IntoView {
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
                            let h = hero_of(&card, &row);
                            let target = target_from_row(&row, from_history);
                            view! {
                                <PosterCard
                                    card=card
                                    on_open=move |_| nav::push(Route::Detail(target.clone()))
                                    on_focus=move |_| hero.set(Some(h.clone()))
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

async fn load_rows(path: &str, key: &str) -> Load<Vec<Value>> {
    match api::get(path).await {
        Ok(v) => Load::Ready(array(&v, key)),
        Err(e) => Load::Failed(if e.is_offline() { "無法連線到伺服器".into() } else { e.message }),
    }
}

/// Wait this long after the last focus change before swapping the backdrop,
/// so scrubbing along a rail doesn't decode a poster per card.
const BACKDROP_DEBOUNCE_MS: u32 = 200;

/// Two stacked <img> layers; the back one takes the new picture and fades in
/// over the front once it has loaded. Never more than two images in the DOM.
#[component]
pub fn Backdrop(#[prop(into)] poster: Signal<String>, alive: RwSignal<bool>) -> impl IntoView {
    let layers = RwSignal::new([String::new(), String::new()]);
    let front = RwSignal::new(0usize);
    let generation = StoredValue::new(0u64);
    Effect::new(move |_| {
        let raw = poster.get();
        let url = api::poster_url(&raw);
        generation.update_value(|g| *g += 1);
        let mine = generation.get_value();
        gloo_timers::callback::Timeout::new(BACKDROP_DEBOUNCE_MS, move || {
            if alive.try_get_untracked() != Some(true) || generation.try_get_value() != Some(mine) {
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
        })
        .forget();
    });
    let layer = move |i: usize| {
        view! {
            <img
                class="tv-backdrop-img"
                class:is-shown=move || front.get() == i && layers.with(|l| !l[i].is_empty())
                src=move || layers.with(|l| l[i].clone())
                alt=""
                on:load=move |_| {
                    if layers.with_untracked(|l| !l[i].is_empty()) {
                        front.set(i);
                    }
                }
            />
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
    let confirming = RwSignal::new(false);
    // True while the remote is in the rows: the top bar steps aside.
    let browsing = RwSignal::new(false);
    let alive = RwSignal::new(true);
    on_cleanup(move || alive.set(false));

    let load = move || {
        spawn_local(async move {
            let (c, f, h) = futures::join!(
                load_rows("/me/continue-watching", "items"),
                load_rows("/me/favorites", "favorites"),
                load_rows("/me/history", "history"),
            );
            if alive.try_get_untracked() != Some(true) {
                return;
            }
            // The hero starts on what is most likely wanted: the first thing
            // to continue, else the first favourite.
            if hero.get_untracked().is_none() {
                let first = match (&c, &f) {
                    (Load::Ready(cs), _) if !cs.is_empty() => Some((continue_card(&cs[0]), cs[0].clone())),
                    (_, Load::Ready(fs)) if !fs.is_empty() => Some((favorite_card(&fs[0]), fs[0].clone())),
                    _ => None,
                };
                if let Some((card, row)) = first {
                    hero.set(Some(hero_of(&card, &row)));
                }
            }
            cont.set(c);
            favs.set(f);
            hist.set(match h {
                Load::Ready(rows) => Load::Ready(dedupe_history(rows)),
                other => other,
            });
            // Back on Home lands on the card it left from; a first visit lands
            // on the first card rather than the Search button.
            gloo_timers::callback::Timeout::new(0, move || {
                if !focus::restore() {
                    let untouched = focus::focused().is_none() || focus::focused_matches("[data-action=search]");
                    if untouched {
                        focus::focus_selector(".tv-rows [data-focusable]");
                    }
                }
            })
            .forget();
        });
    };
    load();
    // Finishing an episode on the phone shows up here without the remote.
    Effect::new(move |prev: Option<u64>| {
        let version = crate::realtime::library_version().get();
        if prev.is_some_and(|p| p != version) {
            load();
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
        if key == Key::Back && confirming.get_untracked() {
            confirming.set(false);
            focus::focus_later("[data-action=signout]");
            return true;
        }
        false
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
        <div class="tv-screen tv-home" class:is-browsing=move || browsing.get() data-screen="home">
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
                            {(!h.meta.is_empty()).then(|| view! { <span class="tv-chip-plain">{h.meta.clone()}</span> })}
                            {h.badge.map(|b| view! { <span class="tv-badge tv-badge-warn">{b}</span> })}
                        </p>
                        {h.progress.map(|p| {
                            let w = format!("transform:scaleX({:.3})", (p / 100.0).clamp(0.02, 1.0));
                            view! { <div class="tv-hero-progress"><div class="tv-hero-progress-fill" style=w></div></div> }
                        })}
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
                    <LibraryRail title="繼續觀看" group="home-continue" rows=cont hero=hero make=continue_card from_history=false/>
                    <LibraryRail title="我的收藏" group="home-favorites" rows=favs hero=hero make=favorite_card from_history=false/>
                    <LibraryRail title="觀看紀錄" group="home-history" rows=hist hero=hero make=history_card from_history=true/>
                    <Show when=all_empty>
                        <div class="tv-empty">"還沒有內容。按「搜尋」找點東西來看吧。"</div>
                    </Show>
                </div>
            </div>
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
