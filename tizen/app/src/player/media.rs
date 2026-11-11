//! Source discovery and the URLs and bodies the player sends. The rules are
//! shared/api/README.md's: a native player is handed `/api/manifest`, never the
//! raw CDN URL; the relay is the source's `proxyUrl` with the token in the
//! query; progress is integer seconds and never carries the derived fields.

use serde_json::{json, Value};
use web_sys::AbortSignal;

use crate::api::{self, ApiError};
use crate::config;
use crate::nav::PlayTarget;
use crate::session;

pub fn str_field<'a>(v: &'a Value, key: &str) -> &'a str {
    v.get(key).and_then(Value::as_str).unwrap_or("")
}

/// What Detail resolved; failing that (a cast `play`), a movie is whatever
/// has no episode label or list.
pub fn media_type(target: &PlayTarget) -> &'static str {
    match target.media_type.as_str() {
        "movie" => return "movie",
        "tv" => return "tv",
        _ => {}
    }
    if target.episode_label.is_empty() && target.episodes.is_empty() {
        "movie"
    } else {
        "tv"
    }
}

/// What `/api/sources` is fetched against: the season (movieffm) or the
/// title's own page (every other provider's detailUrl is its item URL).
pub fn episode_source_url(target: &PlayTarget) -> &str {
    if target.season_url.trim().is_empty() {
        &target.item_url
    } else {
        &target.season_url
    }
}

/// Tier 0: the cleaned manifest, ads cut, segments device-to-CDN.
pub fn manifest_url(source: &Value) -> Option<String> {
    let direct = direct_url(source)?;
    Some(api::media_url("/manifest", &[("target", direct)]))
}

pub fn direct_url(source: &Value) -> Option<&str> {
    let d = str_field(source, "directUrl");
    let d = if d.is_empty() { str_field(source, "url") } else { d };
    (!d.is_empty()).then_some(d)
}

/// Tier 1: the source's own `proxyUrl` (`/api/stream?target=…`), pinned to
/// `/api/v1`, made absolute, with an access token. Built from `directUrl` when
/// the line carried no proxyUrl (a cast `play` never does).
pub fn relay_url(source: &Value) -> Option<String> {
    let proxy = str_field(source, "proxyUrl");
    if proxy.is_empty() {
        return direct_url(source).map(|d| api::media_url("/stream", &[("target", d)]));
    }
    let token = session::current().map(|s| s.access_token).unwrap_or_default();
    Some(absolute_relay(&config::api_base(), proxy, &token))
}

fn absolute_relay(base: &str, proxy: &str, token: &str) -> String {
    let mut url = if proxy.starts_with("http://") || proxy.starts_with("https://") {
        proxy.to_string()
    } else if let Some(rest) = proxy.strip_prefix("/api/v1/") {
        format!("{base}/api/v1/{rest}")
    } else if let Some(rest) = proxy.strip_prefix("/api/") {
        format!("{base}/api/v1/{rest}")
    } else {
        format!("{base}{proxy}")
    };
    if !url.contains("accessToken=") {
        url.push(if url.contains('?') { '&' } else { '?' });
        url.push_str("accessToken=");
        url.push_str(&js_encode(token));
    }
    url
}

#[cfg(target_arch = "wasm32")]
fn js_encode(s: &str) -> String {
    String::from(js_sys::encode_uri_component(s))
}
#[cfg(not(target_arch = "wasm32"))]
fn js_encode(s: &str) -> String {
    s.to_string()
}

/// The `PUT /me/progress` body. Fields exactly as the entry point gave them;
/// `progressPercent` / `isCompleted` are the server's to derive.
pub fn progress_body(
    target: &PlayTarget,
    source_label: &str,
    position_ms: f64,
    duration_ms: f64,
    event: &str,
) -> Value {
    let duration = (duration_ms / 1000.0).round().max(0.0) as u64;
    let position = ((position_ms / 1000.0).round().max(0.0) as u64).min(duration);
    let mut body = json!({
        "providerKey": target.provider,
        "mediaType": media_type(target),
        "title": target.title,
        "posterUrl": absolute_or_empty(&target.poster_url),
        "itemUrl": target.item_url,
        // As the web derives it: the season page (movieffm), else the title's own.
        "detailUrl": episode_source_url(target),
        "seasonUrl": target.season_url,
        "episodeLabel": target.episode_label,
        "sourceLabel": source_label,
        "positionSeconds": position,
        "durationSeconds": duration,
        "event": event,
    });
    // Left out, not sent empty, when unknown (a cast `play`), so a label the
    // row already has is not overwritten.
    if !target.season_label.is_empty() {
        body["seasonLabel"] = Value::from(target.season_label.clone());
    }
    body
}

/// The server validates posterUrl as a URL or "": a provider's relative
/// poster path would 400 every progress save, so it is dropped instead.
pub fn absolute_or_empty(url: &str) -> &str {
    let u = url.trim();
    let lower = u.get(..8).unwrap_or(u).to_ascii_lowercase();
    if lower.starts_with("http://") || lower.starts_with("https://") {
        u
    } else {
        ""
    }
}

/// What the "controlled by" badge calls the driving device. A real name
/// (a native client's "Pixel 7", a browser's "Chrome on Mac") is kept; the
/// server's fallback "StreamHub (<kind>)" becomes something a viewer reads.
pub fn friendly_device(name: &str) -> String {
    let name = name.trim();
    let kind = name
        .strip_prefix("StreamHub (")
        .and_then(|rest| rest.strip_suffix(')'))
        .map(str::to_ascii_lowercase);
    match kind.as_deref() {
        Some("android") => "Android 手機".into(),
        Some("ios") => "iPhone".into(),
        Some("web") | Some("browser") => "瀏覽器".into(),
        Some("tv") => "電視".into(),
        Some(_) => "另一部裝置".into(),
        None if name.is_empty() || name == "Unknown device" => "另一部裝置".into(),
        None => name.to_string(),
    }
}

/// 「由 Android 手機控制」: a space only where Latin text meets the CJK frame.
pub fn driver_caption(name: &str) -> String {
    let n = friendly_device(name);
    let latin = |c: Option<char>| c.is_some_and(|c| c.is_ascii_alphanumeric());
    let before = if latin(n.chars().next()) { " " } else { "" };
    let after = if latin(n.chars().last()) { " " } else { "" };
    format!("由{before}{n}{after}控制")
}

/// The one display rule every screen uses (core `labels`): bare digits
/// become 「第N集」, anything else is shown as given. Never used as a key.
pub fn episode_caption(label: &str) -> String {
    streamhub_core::labels::episode_caption(label)
}

pub fn format_time(seconds: u64) -> String {
    let (h, m, s) = (seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

/// The saved source for this title, if any.
pub async fn preferred_label(target: &PlayTarget) -> Option<String> {
    let path = format!(
        "/me/source-preference{}",
        api::query(&[
            ("providerKey", &target.provider),
            ("mediaType", media_type(target)),
            ("title", &target.title),
        ])
    );
    let v = api::get(&path).await.ok()?;
    let label = v.get("preference")?.get("sourceLabel")?.as_str()?.trim().to_string();
    (!label.is_empty()).then_some(label)
}

pub async fn save_preference(target: &PlayTarget, source_label: &str) {
    if source_label.is_empty() || target.title.is_empty() {
        return;
    }
    let body = json!({
        "providerKey": target.provider,
        "mediaType": media_type(target),
        "title": target.title,
        "sourceLabel": source_label,
    });
    let _ = api::request("POST", "/me/source-preference", Some(&body)).await;
}

/// Streams every source that passes its probe into `on_line`, as it passes.
/// Episodes: `GET /sources`. Movies: `GET /item` for the raw streams, then
/// `POST /check-sources` — the same two calls the web app makes.
pub async fn stream_sources(
    target: &PlayTarget,
    preferred: Option<&str>,
    signal: &AbortSignal,
    on_line: impl FnMut(Value),
) -> Result<(), ApiError> {
    let preferred = preferred.unwrap_or("");
    if media_type(target) == "tv" {
        let path = format!(
            "/sources{}",
            api::query(&[
                ("provider", &target.provider),
                ("sourceUrl", episode_source_url(target)),
                ("episode", &target.episode_label),
                ("preferredLabel", preferred),
            ])
        );
        return api::ndjson(&path, Some(signal), on_line).await;
    }
    let item_path = format!(
        "/item{}",
        api::query(&[
            ("provider", &target.provider),
            ("url", &target.item_url),
            ("title", &target.title),
            ("mediaType", "movie"),
        ])
    );
    let item = api::get(&item_path).await?;
    let streams = item.get("streams").cloned().unwrap_or(Value::Array(vec![]));
    if streams.as_array().is_none_or(|a| a.is_empty()) {
        return Ok(());
    }
    let body = json!({ "streams": streams, "provider": target.provider, "preferredLabel": preferred });
    api::ndjson_with("POST", "/check-sources", Some(&body), Some(signal), on_line).await
}

/// Did the *server* answer? A playback error while it cannot be reached is an
/// outage, not a bad source, and must not climb the ladder.
pub async fn server_reachable() -> bool {
    let online = web_sys::window().map(|w| w.navigator().on_line()).unwrap_or(true);
    if !online {
        return false;
    }
    match api::public_request("GET", "/health", None).await {
        Ok(_) => true,
        Err(e) => !e.is_offline(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn target() -> PlayTarget {
        PlayTarget {
            provider: "dramasq".into(),
            item_url: "https://dramasq.io/drama/1.html".into(),
            title: "T".into(),
            episode_label: "第01集".into(),
            episodes: vec!["第01集".into(), "第02集".into()],
            ..Default::default()
        }
    }

    #[test]
    fn progress_is_integer_seconds_without_derived_fields() {
        let b = progress_body(&target(), "S1", 12_600.0, 1_500_400.0, "progress");
        assert_eq!(b["positionSeconds"], 13);
        assert_eq!(b["durationSeconds"], 1500);
        assert_eq!(b["mediaType"], "tv");
        assert_eq!(b["seasonUrl"], "");
        assert_eq!(b["detailUrl"], "https://dramasq.io/drama/1.html");
        assert!(b.get("progressPercent").is_none());
        assert!(b.get("isCompleted").is_none());
    }

    #[test]
    fn relative_posters_are_not_sent() {
        let mut t = target();
        t.poster_url = "/wp-content/uploads/x.jpg".into();
        assert_eq!(progress_body(&t, "", 0.0, 1000.0, "progress")["posterUrl"], "");
        t.poster_url = "https://img/x.jpg".into();
        assert_eq!(progress_body(&t, "", 0.0, 1000.0, "progress")["posterUrl"], "https://img/x.jpg");
        assert_eq!(absolute_or_empty("HTTPS://A/b"), "HTTPS://A/b");
        assert_eq!(absolute_or_empty("  "), "");
    }

    #[test]
    fn driver_names_read_as_devices() {
        assert_eq!(driver_caption("StreamHub (android)"), "由 Android 手機控制");
        assert_eq!(driver_caption("StreamHub (ios)"), "由 iPhone 控制");
        assert_eq!(driver_caption("StreamHub (web)"), "由瀏覽器控制");
        assert_eq!(driver_caption("Pixel 7"), "由 Pixel 7 控制");
        assert_eq!(driver_caption("Chrome on Mac"), "由 Chrome on Mac 控制");
        assert_eq!(driver_caption("Unknown device"), "由另一部裝置控制");
        assert_eq!(driver_caption("客廳的手機"), "由客廳的手機控制");
    }

    #[test]
    fn sources_fall_back_to_the_item_page() {
        let mut t = target();
        assert_eq!(episode_source_url(&t), t.item_url);
        t.season_url = "https://m/s2".into();
        assert_eq!(episode_source_url(&t), "https://m/s2");
    }

    #[test]
    fn relay_is_versioned_and_tokened() {
        let u = absolute_relay("http://h", "/api/stream?target=x", "tok");
        assert_eq!(u, "http://h/api/v1/stream?target=x&accessToken=tok");
        let u = absolute_relay("http://h", "/api/v1/stream?target=x&accessToken=a", "tok");
        assert_eq!(u, "http://h/api/v1/stream?target=x&accessToken=a");
    }

    #[test]
    fn captions_and_times() {
        assert_eq!(episode_caption("04"), "第04集");
        assert_eq!(episode_caption("第4集"), "第4集");
        assert_eq!(episode_caption("EP1"), "EP1");
        assert_eq!(format_time(0), "0:00");
        assert_eq!(format_time(3725), "1:02:05");
    }
}
