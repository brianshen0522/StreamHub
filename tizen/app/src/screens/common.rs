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

/// A titled horizontal rail. Children are the cards; `group` names the focus
/// group the track gives them.
#[component]
pub fn Rail(
    #[prop(into)] title: String,
    #[prop(optional, into)] note: Option<String>,
    #[prop(optional, into)] provider: Option<String>,
    #[prop(into)] group: String,
    children: Children,
) -> impl IntoView {
    view! {
        <section class="tv-rail" data-provider=provider>
            <h2 class="tv-rail-title">
                {title}
                {note.map(|n| view! { <span class="tv-rail-note">{n}</span> })}
            </h2>
            <div class="tv-rail-track" data-focus-group=group>{children()}</div>
        </section>
    }
}
