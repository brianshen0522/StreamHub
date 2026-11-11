//! Pieces every screen uses: JSON field access, the poster card, rails and
//! load states.

use leptos::prelude::*;
use serde_json::Value;

use crate::api;

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
}

#[component]
pub fn PosterCard(
    card: CardData,
    #[prop(into)] on_open: Callback<()>,
    #[prop(optional, into)] on_focus: Option<Callback<()>>,
) -> impl IntoView {
    let src = api::poster_url(&card.poster);
    let has_poster = !src.is_empty();
    let initial = card.title.chars().next().map(String::from).unwrap_or_default();
    let unavailable = card.badge.is_some();
    view! {
        <div
            class="tv-card"
            class:is-unavailable=unavailable
            data-focusable=""
            data-focus-id=card.id.clone()
            tabindex="-1"
            on:click=move |_| on_open.run(())
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
            <div class="tv-card-sub">{card.subtitle.clone()}</div>
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
