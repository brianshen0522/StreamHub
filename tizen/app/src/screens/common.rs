//! Pieces every screen uses: JSON field access, the poster card, rails and
//! load states.

use leptos::prelude::*;
use serde_json::Value;

use crate::api;
use crate::focus::{self, Intent};

/// `/api/poster` for a provider poster, with the *current* access token.
/// Reading it inside a reactive closure subscribes to the session, so the URL
/// follows token refreshes (the token rides in the query: `<img>` can't send
/// headers).
pub fn poster_src(target: &str) -> String {
    crate::session::signal().track();
    api::poster_url(target)
}

/// A string field, "" when absent or not a string.
pub fn s(v: &Value, key: &str) -> String {
    v.get(key).and_then(Value::as_str).unwrap_or("").to_string()
}

pub fn num(v: &Value, key: &str) -> f64 {
    v.get(key).and_then(Value::as_f64).unwrap_or(0.0)
}

pub fn flag(v: &Value, key: &str) -> bool {
    v.get(key).and_then(Value::as_bool).unwrap_or(false)
}

pub fn opt(v: &Value, key: &str) -> Option<String> {
    Some(s(v, key)).filter(|x| !x.is_empty())
}

pub fn array(v: &Value, key: &str) -> Vec<Value> {
    v.get(key).and_then(Value::as_array).cloned().unwrap_or_default()
}

/// The server's `mediaType`, coerced to what the write schemas accept.
pub fn media_type(raw: &str) -> &'static str {
    match raw {
        "movie" => "movie",
        "tv" => "tv",
        _ => "unknown",
    }
}

/// `1:05:09` / `5:09`.
pub fn clock(seconds: f64) -> String {
    let t = seconds.max(0.0) as u64;
    let (h, m, s) = (t / 3600, (t % 3600) / 60, t % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

#[derive(Clone, Debug, PartialEq)]
pub enum Load<T> {
    Loading,
    Ready(T),
    Failed(String),
}

/// Why a library row is marked, for the badge.
pub fn unavailable_label(row: &Value) -> Option<&'static str> {
    if !flag(row, "isUnavailable") {
        return None;
    }
    Some(match s(row, "unavailableReason").as_str() {
        "no-episodes" => "已無集數",
        _ => "已下架",
    })
}

/// What a card shows. `id` must be stable across reloads: it is the
/// `data-focus-id` focus memory returns to.
#[derive(Clone, Debug, PartialEq)]
pub struct CardData {
    pub id: String,
    pub title: String,
    pub subtitle: String,
    pub poster: String,
    /// 0–100, or None for no bar.
    pub progress: Option<f64>,
    pub badge: Option<String>,
    pub chip: Option<String>,
    /// Shown as the subtitle when `subtitle` is empty, by display name — read
    /// reactively, so it updates when the provider names arrive.
    pub provider: Option<String>,
}

#[component]
pub fn PosterCard(
    card: CardData,
    #[prop(into)] on_open: Callback<()>,
    #[prop(optional, into)] on_focus: Option<Callback<()>>,
    /// The Play key on this card (focus.rs `Intent::Play`).
    #[prop(optional, into)]
    on_play: Option<Callback<()>>,
    /// OK held 700 ms (`Intent::LongPress`); short OK still opens.
    #[prop(default = None)]
    on_long: Option<Callback<()>>,
    /// The red key (`Intent::Favorite`).
    #[prop(optional, into)]
    on_favorite: Option<Callback<()>>,
) -> impl IntoView {
    let flag = |b: bool| b.then_some("");
    let poster = card.poster.clone();
    let has_poster = !poster.trim().is_empty();
    // Re-derived when the session's token changes, so a card first shown
    // (or lazily loaded) hours later never asks with an expired token.
    let src = move || poster_src(&poster);
    let initial = card.title.chars().next().map(String::from).unwrap_or_default();
    let unavailable = card.badge.is_some();
    view! {
        <div
            class="tv-card"
            class:is-unavailable=unavailable
            data-focusable=""
            data-focus-id=card.id.clone()
            data-playable=flag(on_play.is_some())
            data-longpress=flag(on_long.is_some())
            data-favable=flag(on_favorite.is_some())
            tabindex="-1"
            on:click=move |_| match focus::intent() {
                Intent::Play => on_play.unwrap_or(on_open).run(()),
                Intent::LongPress => on_long.unwrap_or(on_open).run(()),
                Intent::Favorite => {
                    if let Some(f) = on_favorite {
                        f.run(())
                    }
                }
                Intent::Open => on_open.run(()),
            }
            on:focus=move |_| {
                if let Some(f) = on_focus {
                    f.run(())
                }
            }
        >
            <div class="tv-card-art">
                <div class="tv-card-fallback">{initial}</div>
                {has_poster.then(|| view! { <img class="tv-card-img" src=src loading="lazy" alt=""/> })}
                {card.badge.clone().map(|b| view! { <span class="tv-badge tv-badge-warn">{b}</span> })}
                {card.chip.clone().map(|c| view! { <span class="tv-chip">{c}</span> })}
                {card.progress.filter(|p| *p > 0.0).map(|p| {
                    let w = format!("width:{:.0}%", p.clamp(2.0, 100.0));
                    view! { <div class="tv-progress"><div class="tv-progress-fill" style=w></div></div> }
                })}
            </div>
            <div class="tv-card-title">{card.title.clone()}</div>
            <div class="tv-card-sub">
                {
                    let sub = card.subtitle.clone();
                    let provider = card.provider.clone();
                    move || match (&provider, sub.is_empty()) {
                        (Some(key), true) => provider_name(key),
                        _ => sub.clone(),
                    }
                }
            </div>
        </div>
    }
}

/// A titled row holding a horizontal rail. The track is moved by focus.rs
/// (`data-rail-track`); the row takes part in its column (`data-row`).
/// Children are the cards; `group` names the focus group the track gives them.
/// With `caption` the row collapses to its heading plus that one line (an
/// empty or failed provider) and has no track.
#[component]
pub fn Rail(
    #[prop(into)] title: String,
    #[prop(optional, into)] note: Option<String>,
    #[prop(optional, into)] provider: Option<String>,
    #[prop(optional, into)] caption: Option<String>,
    #[prop(optional)] error: bool,
    #[prop(into)] group: String,
    children: Children,
) -> impl IntoView {
    let collapsed = caption.is_some();
    view! {
        <section class="tv-row" class:is-collapsed=collapsed data-row="" data-provider=provider>
            <h2 class="tv-row-title">
                {title}
                {note.map(|n| view! { <span class="tv-row-note">{n}</span> })}
                {caption.map(|c| view! { <span class="tv-row-caption" class:is-error=error>{c}</span> })}
            </h2>
            {(!collapsed).then(|| view! {
                <div class="tv-rail">
                    <div class="tv-track" data-rail-track="" data-focus-group=group>{children()}</div>
                </div>
            })}
        </section>
    }
}

/// Placeholder cards with a shimmer while a rail loads.
#[component]
pub fn Skeletons(#[prop(default = 7)] count: usize) -> impl IntoView {
    (0..count)
        .map(|_| view! {
            <div class="tv-card tv-skeleton" aria-hidden="true">
                <div class="tv-card-art"></div>
                <div class="tv-skel-line"></div>
                <div class="tv-skel-line tv-skel-short"></div>
            </div>
        })
        .collect_view()
}

/// The heart, outlined or filled.
#[component]
pub fn Heart(#[prop(into)] filled: Signal<bool>) -> impl IntoView {
    view! {
        <svg class="tv-heart" class:is-filled=move || filled.get() viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 20.5s-7.5-4.6-9.6-9.2C1 8.1 3 4.5 6.6 4.5c2.1 0 3.6 1.2 5.4 3.2 1.8-2 3.3-3.2 5.4-3.2 3.6 0 5.6 3.6 4.2 6.8-2.1 4.6-9.6 9.2-9.6 9.2z"/>
        </svg>
    }
}

thread_local! {
    static TOAST: ArcRwSignal<Option<String>> = ArcRwSignal::new(None);
    static TOAST_GEN: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

/// Show a short message at the bottom of the screen for ~2 s.
pub fn toast(message: impl Into<String>) {
    let signal = TOAST.with(|t| t.clone());
    signal.set(Some(message.into()));
    let mine = TOAST_GEN.with(|g| {
        g.set(g.get() + 1);
        g.get()
    });
    gloo_timers::callback::Timeout::new(2200, move || {
        if TOAST_GEN.with(|g| g.get()) == mine {
            TOAST.with(|t| t.set(None));
        }
    })
    .forget();
}

/// Where `toast` messages appear; screens that use them render one.
#[component]
pub fn Toast() -> impl IntoView {
    let message = RwSignal::from(TOAST.with(|t| t.clone()));
    view! {
        {move || message.get().map(|m| view! { <div class="tv-toast" role="status" data-toast="">{m}</div> })}
    }
}

/// What a favourite is made from: a library row or a search result.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct FavoriteKey {
    pub provider: String,
    pub item_url: String,
    pub title: String,
    pub poster: String,
    pub media_type: String,
    pub season_url: String,
    pub episode_label: String,
}

/// The red key: un-favourite every row of this title if there is one,
/// otherwise favourite it (key fields sent as the row carries them, absent as
/// ""). Returns whether it is a favourite now.
pub async fn toggle_favorite(key: FavoriteKey) -> Result<bool, api::ApiError> {
    let list = api::get("/me/favorites").await?;
    let existing: Vec<Value> = array(&list, "favorites")
        .into_iter()
        .filter(|r| s(r, "providerKey") == key.provider && s(r, "itemUrl") == key.item_url)
        .collect();
    if !existing.is_empty() {
        for row in existing {
            let path = format!("/me/favorites/{}", js_sys::encode_uri_component(&s(&row, "id")));
            api::request("DELETE", &path, None).await?;
        }
        return Ok(false);
    }
    let body = serde_json::json!({
        "providerKey": key.provider,
        "mediaType": media_type(&key.media_type),
        "title": key.title,
        "posterUrl": if key.poster.starts_with("http") { key.poster.clone() } else { String::new() },
        "itemUrl": key.item_url,
        "seasonUrl": key.season_url,
        "episodeLabel": key.episode_label,
    });
    api::request("POST", "/me/favorites", Some(&body)).await?;
    Ok(true)
}

/// The red key on a card, with a toast for the outcome.
pub fn favorite_toggle_with_toast(key: FavoriteKey, after: impl FnOnce() + 'static) {
    leptos::task::spawn_local(async move {
        let title = key.title.clone();
        match toggle_favorite(key).await {
            Ok(true) => toast(format!("已加入收藏：{title}")),
            Ok(false) => toast(format!("已取消收藏：{title}")),
            Err(e) => toast(friendly_error(&e)),
        }
        after();
    });
}

/// The server and the network speak English (`Invalid credentials.`, `The
/// operation was aborted due to timeout`); the screen speaks zh-TW. Known
/// messages and statuses get Chinese copy; a message already in Chinese is
/// shown as it is.
pub fn friendly_error(e: &api::ApiError) -> String {
    let msg = e.message.trim();
    let lower = msg.to_ascii_lowercase();
    if msg.chars().any(|c| ('\u{4e00}'..='\u{9fff}').contains(&c)) {
        return msg.to_string();
    }
    if lower.contains("timeout") || lower.contains("timed out") || lower.contains("aborted") {
        return "連線逾時，請再試一次".into();
    }
    if e.is_offline() || lower.contains("failed to fetch") || lower.contains("network") {
        return "無法連線到伺服器".into();
    }
    if lower.contains("invalid credentials") {
        return "帳號或密碼不正確".into();
    }
    if lower.contains("admin") {
        return "管理員帳號無法在電視上觀看，請使用一般觀眾帳號。".into();
    }
    if lower.contains("too many") || e.status == 429 {
        let seconds: Option<u64> = lower
            .split(|c: char| !c.is_ascii_digit())
            .find(|t| !t.is_empty())
            .and_then(|t| t.parse().ok());
        return match seconds {
            Some(s) if lower.contains("second") && s >= 60 => format!("嘗試次數過多，請在 {} 分鐘後再試", s.div_ceil(60)),
            Some(s) if lower.contains("second") => format!("嘗試次數過多，請在 {s} 秒後再試"),
            Some(m) if lower.contains("minute") => format!("嘗試次數過多，請在 {m} 分鐘後再試"),
            _ => "嘗試次數過多，請稍後再試".into(),
        };
    }
    if lower.contains("expired") {
        return "代碼已過期，請重新取得".into();
    }
    if lower.contains("not found") || e.status == 404 {
        return "找不到這個內容".into();
    }
    // Unknown: Chinese first, the server's own words kept as the detail.
    let detail = if msg.is_empty() || msg.starts_with("伺服器錯誤") { String::new() } else { format!("：{msg}") };
    match e.status {
        401 => "登入已失效，請重新登入".into(),
        403 => "沒有權限".into(),
        400 => format!("要求的內容不正確{detail}"),
        500..=599 => format!("伺服器發生錯誤{detail}"),
        0 => "無法連線到伺服器".into(),
        s => format!("發生錯誤（{s}）{detail}"),
    }
}

thread_local! {
    static PROVIDER_NAMES: ArcRwSignal<std::collections::HashMap<String, String>> = ArcRwSignal::new(Default::default());
    static PROVIDERS_ASKED: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

/// Fetch provider display names once per session (screens call this on
/// mount; later calls are free). Signing out clears the flag via `reset`.
pub fn ensure_provider_names() {
    if PROVIDERS_ASKED.with(|a| a.replace(true)) {
        return;
    }
    leptos::task::spawn_local(async {
        match api::get("/me/providers?all=true").await {
            Ok(v) => {
                let map = array(&v, "providers")
                    .iter()
                    .filter_map(|p| Some((opt(p, "key")?, opt(p, "name")?)))
                    .collect();
                PROVIDER_NAMES.with(|n| n.set(map));
            }
            // Try again on the next screen.
            Err(_) => PROVIDERS_ASKED.with(|a| a.set(false)),
        }
    });
}

/// A provider's display name ("MovieFFM"), falling back to its key. Reactive:
/// read inside a view closure, it updates when the names arrive.
pub fn provider_name(key: &str) -> String {
    PROVIDER_NAMES.with(|n| n.with(|m| m.get(key).cloned())).unwrap_or_else(|| key.to_string())
}

/// Episode label for the screen (bare digits → 「第N集」). Display only.
pub fn episode_caption(label: &str) -> String {
    streamhub_core::labels::episode_caption(label)
}
