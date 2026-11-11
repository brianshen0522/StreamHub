//! Search, one request per provider (like frontend App.jsx runProviderSearch),
//! each shown as its own rail as it answers, in the server's ranked order.
//! A provider that failed is still a row with its error: `/api/search`
//! answers 200 either way, and silence would read as "no results".
//!
//! The query and results outlive the screen (the router remounts it on Back
//! from Detail) and are dropped once Search leaves the navigation stack.

use std::cell::{Cell, RefCell};

use futures::future::{select, Either};
use gloo_timers::future::TimeoutFuture;
use leptos::prelude::*;
use leptos::task::spawn_local;
use serde_json::Value;

use crate::api;
use crate::focus;
use crate::nav::{self, DetailTarget, Route};
use crate::keys::Key;
use crate::screens::common::{
    array, favorite_toggle_with_toast, friendly_error, opt, s, CardData, FavoriteKey, Load, PosterCard, Rail, Skeletons, Toast,
};
use crate::screens::detail;

/// The server gives each site 8 s and movieffm makes two rounds; past this
/// the site is not coming back.
const DEADLINE_MS: u32 = 20_000;
const RECENT_KEY: &str = "streamhub.tizen.recentSearches";
const RECENT_MAX: usize = 8;
/// Cards per page of a result rail, and how near the end focus may get
/// before the next page is added.
const PAGE: usize = 30;
const PREFETCH: usize = 6;

#[derive(Clone, Debug, PartialEq)]
struct Group {
    key: String,
    name: String,
    state: Load<Vec<Value>>,
}

thread_local! {
    static QUERY: ArcRwSignal<String> = ArcRwSignal::new(String::new());
    static GROUPS: ArcRwSignal<Vec<Group>> = ArcRwSignal::new(Vec::new());
    static NOTICE: ArcRwSignal<Option<String>> = ArcRwSignal::new(None);
    static SEQ: Cell<u64> = const { Cell::new(0) };
    static PENDING: RefCell<bool> = const { RefCell::new(false) };
    /// How many cards each provider's rail had grown to, so coming back from
    /// Detail can land on a card past the first page.
    static SHOWN: RefCell<std::collections::HashMap<String, usize>> = RefCell::new(Default::default());
}

fn shown_for(provider: &str) -> usize {
    SHOWN.with(|m| m.borrow().get(provider).copied()).unwrap_or(PAGE).max(PAGE)
}

fn remember_shown(provider: &str, n: usize) {
    SHOWN.with(|m| m.borrow_mut().insert(provider.to_string(), n));
}

fn query() -> ArcRwSignal<String> {
    QUERY.with(|s| s.clone())
}
fn groups() -> ArcRwSignal<Vec<Group>> {
    GROUPS.with(|s| s.clone())
}
fn notice() -> ArcRwSignal<Option<String>> {
    NOTICE.with(|s| s.clone())
}

/// Open Search with `title` typed in and searched (the way out of a title a
/// provider dropped). Call before `nav::push(Route::Search)`.
pub fn prefill(title: &str) {
    reset();
    query().set(title.to_string());
    PENDING.with(|p| *p.borrow_mut() = true);
}

fn reset() {
    SHOWN.with(|m| m.borrow_mut().clear());
    SEQ.with(|s| s.set(s.get() + 1));
    query().set(String::new());
    groups().set(Vec::new());
    notice().set(None);
}

fn storage() -> Option<web_sys::Storage> {
    web_sys::window()?.local_storage().ok().flatten()
}

fn recent() -> Vec<String> {
    let mut list: Vec<String> = storage()
        .and_then(|s| s.get_item(RECENT_KEY).ok().flatten())
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default();
    list.truncate(RECENT_MAX);
    list
}

fn remember(q: &str) {
    let mut list = recent();
    list.retain(|x| x != q);
    list.insert(0, q.to_string());
    list.truncate(RECENT_MAX);
    if let (Some(s), Ok(raw)) = (storage(), serde_json::to_string(&list)) {
        let _ = s.set_item(RECENT_KEY, &raw);
    }
}

fn set_group(seq: u64, key: &str, state: Load<Vec<Value>>) {
    if SEQ.with(|s| s.get()) != seq {
        return;
    }
    groups().update(|gs| {
        if let Some(g) = gs.iter_mut().find(|g| g.key == key) {
            g.state = state;
        }
    });
}

fn run_search(q: String) {
    let q = q.trim().to_string();
    if q.is_empty() {
        return;
    }
    let seq = SEQ.with(|s| {
        s.set(s.get() + 1);
        s.get()
    });
    remember(&q);
    notice().set(Some("搜尋中…".into()));
    groups().set(Vec::new());
    spawn_local(async move {
        let providers = match api::get("/me/providers").await {
            Ok(v) => array(&v, "providers"),
            Err(e) => {
                if SEQ.with(|s| s.get()) == seq {
                    notice().set(Some(friendly_error(&e)));
                }
                return;
            }
        };
        if SEQ.with(|s| s.get()) != seq {
            return;
        }
        let list: Vec<Group> = providers
            .iter()
            .filter(|p| p.get("allowed").and_then(Value::as_bool).unwrap_or(true))
            .map(|p| Group {
                key: s(p, "key"),
                name: opt(p, "name").unwrap_or_else(|| s(p, "key")),
                state: Load::Loading,
            })
            .filter(|g| !g.key.is_empty())
            .collect();
        if list.is_empty() {
            notice().set(Some("這個帳號沒有可搜尋的來源".into()));
            return;
        }
        notice().set(None);
        groups().set(list.clone());
        for group in list {
            let q = q.clone();
            spawn_local(async move {
                let path = format!("/search{}", api::query(&[("q", &q), ("provider", &group.key)]));
                let request = Box::pin(api::get(&path));
                let deadline = Box::pin(TimeoutFuture::new(DEADLINE_MS));
                let state = match select(request, deadline).await {
                    Either::Left((Ok(v), _)) => {
                        let first = array(&v, "results").into_iter().next().unwrap_or(Value::Null);
                        match opt(&first, "error") {
                            // The provider's own failure, in the server's words.
                            Some(err) => Load::Failed(friendly_error(&api::ApiError { status: 502, message: err })),
                            None => Load::Ready(array(&first, "items")),
                        }
                    }
                    Either::Left((Err(e), _)) => {
                        Load::Failed(friendly_error(&e))
                    }
                    Either::Right(_) => Load::Failed("搜尋逾時".into()),
                };
                set_group(seq, &group.key, state);
            });
        }
    });
}

fn result_target(item: &Value, provider: &str) -> DetailTarget {
    DetailTarget {
        provider: opt(item, "provider").unwrap_or_else(|| provider.to_string()),
        url: s(item, "url"),
        title: s(item, "title"),
        poster_url: s(item, "posterUrl"),
        media_type: s(item, "mediaType"),
        ..Default::default()
    }
}

fn result_card(item: &Value, provider: &str) -> CardData {
    let sub = [s(item, "year"), match s(item, "mediaType").as_str() {
        "movie" => "電影".to_string(),
        "tv" => "影集".to_string(),
        _ => String::new(),
    }]
    .into_iter()
    .filter(|x| !x.is_empty())
    .collect::<Vec<_>>()
    .join(" · ");
    CardData {
        id: format!("s:{provider}:{}", s(item, "url")),
        title: s(item, "title"),
        subtitle: sub,
        poster: s(item, "posterUrl"),
        progress: None,
        badge: None,
        chip: None,
        provider: None,
    }
}

#[component]
fn ProviderRail(group: Group) -> impl IntoView {
    let key = group.key.clone();
    let rail_group = format!("search-{key}");
    match group.state {
        Load::Loading => view! {
            <Rail title=group.name provider=key group=rail_group>
                <Skeletons count=8/>
            </Rail>
        }
        .into_any(),
        // Empty and failed providers collapse to one caption line.
        Load::Failed(e) => view! {
            <Rail title=group.name provider=key group=rail_group caption=e error=true>{()}</Rail>
        }
        .into_any(),
        Load::Ready(items) if items.is_empty() => view! {
            <Rail title=group.name provider=key group=rail_group caption="沒有結果">{()}</Rail>
        }
        .into_any(),
        Load::Ready(items) => {
            let note = items.len().to_string();
            let total = items.len();
            let items = StoredValue::new(items);
            // A popular title answers with 240+ cards; rendering them all made
            // every key slow. Show a page and add the next one as focus nears
            // the end — holding Right never meets a wall, and appending is
            // keyed, so the focused card is never re-rendered.
            let shown = RwSignal::new(shown_for(&key).min(total));
            let grow = move |provider: &str| {
                let next = (shown.get_untracked() + PAGE).min(total);
                if next > shown.get_untracked() {
                    shown.set(next);
                    remember_shown(provider, next);
                }
            };
            view! {
                <Rail title=group.name provider=key.clone() group=rail_group note=note>
                    <For
                        each={move || (0..shown.get()).collect::<Vec<usize>>()}
                        key=|i| *i
                        children=move |i| {
                            let item = items.with_value(|v| v[i].clone());
                            let card = result_card(&item, &key);
                            let target = result_target(&item, &key);
                            let t_play = target.clone();
                            let fav = FavoriteKey {
                                provider: target.provider.clone(),
                                item_url: target.url.clone(),
                                title: target.title.clone(),
                                poster: target.poster_url.clone(),
                                media_type: target.media_type.clone(),
                                ..Default::default()
                            };
                            let k_focus = key.clone();
                            view! {
                                <PosterCard
                                    card=card
                                    on_open=move |_| nav::push(Route::Detail(target.clone()))
                                    on_focus=move |_| {
                                        if i + PREFETCH >= shown.get_untracked() {
                                            grow(&k_focus);
                                        }
                                    }
                                    on_play=move |_| {
                                        detail::request_autoplay(&t_play.provider, &t_play.url);
                                        nav::push(Route::Detail(t_play.clone()));
                                    }
                                    on_favorite=move |_| favorite_toggle_with_toast(fav.clone(), || {})
                                />
                            }
                        }
                    />
                </Rail>
            }
            .into_any()
        }
    }
}

#[component]
pub fn SearchScreen() -> impl IntoView {
    // Copy handles onto the global Arc signals, owned by this screen.
    let q = RwSignal::from(query());
    let gs = RwSignal::from(groups());
    let note = RwSignal::from(notice());
    let recents = RwSignal::new(recent());

    if PENDING.with(|p| std::mem::take(&mut *p.borrow_mut())) {
        run_search(q.get_untracked());
    }
    let had_results = !gs.get_untracked().is_empty();
    if had_results {
        gloo_timers::callback::Timeout::new(0, || {
            focus::restore();
        })
        .forget();
    }

    // Back from the results returns to the field first; Up from the first
    // row always lands on it, whatever is nearest.
    let live = focus::screen_alive();
    let live_in = live.clone();
    let handler: focus::KeyHandler = std::rc::Rc::new(move |key| {
        if !live_in.load(std::sync::atomic::Ordering::Relaxed) {
            return false;
        }
        match key {
            Key::Back => focus::focused_within(".tv-results") && focus::back_to("input[name=q]"),
            Key::Up if focus::focused_within(".tv-results .tv-rows > section:first-of-type") => {
                focus::focus_selector("input[name=q]")
            }
            _ => false,
        }
    });
    focus::set_screen_handler(Some(handler));
    on_cleanup(move || live.store(false, std::sync::atomic::Ordering::Relaxed));

    on_cleanup(|| {
        // Leaving for Detail keeps the results; leaving Search for good drops them.
        let still_stacked = nav::stack().with_untracked(|s| s.iter().any(|r| matches!(r, Route::Search)));
        if !still_stacked {
            reset();
        }
    });

    let submit = move || {
        run_search(q.get_untracked());
        recents.set(recent());
    };

    view! {
        <div class="tv-screen tv-search" data-screen="search">
            <form
                class="tv-search-bar"
                on:submit=move |ev: leptos::ev::SubmitEvent| {
                    ev.prevent_default();
                    submit();
                }
            >
                <div class="tv-search-pill">
                <svg class="tv-icon tv-search-icon" viewBox="0 0 24 24" aria-hidden="true">
                    <circle cx="10.5" cy="10.5" r="6.5"/>
                    <path d="m20 20-4.6-4.6"/>
                </svg>
                <input
                    class="tv-input tv-search-input"
                    name="q"
                    type="text"
                    autocomplete="off"
                    autocapitalize="off"
                    spellcheck="false"
                    enterkeyhint="search"
                    placeholder="輸入片名，按 OK 搜尋"
                    data-focusable=""
                    data-focus-id="search-input"
                    data-autofocus=(!had_results).then_some("")
                    prop:value=move || q.get()
                    on:input=move |ev| q.set(event_target_value(&ev))
                />
                </div>
                <button type="button" class="tv-btn" data-focusable="" data-action="search-submit" on:click=move |_| submit()>
                    "搜尋"
                </button>
            </form>
            <div class="tv-results">
                {move || note.get().map(|n| view! { <p class="tv-notice">{n}</p> })}
                <div class="tv-rows" data-row-column="" data-row-anchor="0">
                <Show when=move || gs.with(|g| g.is_empty()) && !recents.get().is_empty()>
                    <section class="tv-row" data-row="">
                        <h2 class="tv-row-title">"最近搜尋"</h2>
                        <div class="tv-rail">
                        <div class="tv-track tv-chips" data-rail-track="" data-focus-group="search-recent">
                            {move || {
                                recents
                                    .get()
                                    .into_iter()
                                    .map(|r| {
                                        let label = r.clone();
                                        view! {
                                            <button
                                                class="tv-btn tv-btn-chip"
                                                data-focusable=""
                                                data-focus-id=format!("recent:{r}")
                                                on:click=move |_| {
                                                    q.set(r.clone());
                                                    run_search(r.clone());
                                                    recents.set(recent());
                                                }
                                            >
                                                {label}
                                            </button>
                                        }
                                    })
                                    .collect_view()
                            }}
                        </div>
                        </div>
                    </section>
                </Show>
                <For
                    each=move || gs.get()
                    key=|g| (g.key.clone(), format!("{:?}", std::mem::discriminant(&g.state)), match &g.state { Load::Ready(v) => v.len(), _ => 0 })
                    children=|g| view! { <ProviderRail group=g/> }
                />
                </div>
            </div>
            <Toast/>
        </div>
    }
}
