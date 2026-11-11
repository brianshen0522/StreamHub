//! Source discovery and the URLs and bodies the player sends. The rules are
//! shared/api/README.md's: a native player is handed `/api/manifest`, never the
//! raw CDN URL; the relay is the source's `proxyUrl` with the token in the
//! query; progress is integer seconds and never carries the derived fields.

use serde_json::{json, Value};
use wasm_bindgen::{JsCast, JsValue};
use wasm_bindgen_futures::JsFuture;
use web_sys::{AbortSignal, Headers, Request, RequestInit, Response};

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
        "posterUrl": target.poster_url,
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

/// "第4集" stays as is; a bare "04" becomes "第04集". Never used as a key.
pub fn episode_caption(label: &str) -> String {
    let bare = !label.is_empty() && label.chars().all(|c| c.is_ascii_digit());
    if bare {
        format!("第{label}集")
    } else {
        label.to_string()
    }
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
    ndjson_post("/check-sources", &body, signal, on_line).await
}

/// `api::ndjson` is GET-only; `/check-sources` is a POST.
async fn ndjson_post(
    path: &str,
    body: &Value,
    signal: &AbortSignal,
    mut on_line: impl FnMut(Value),
) -> Result<(), ApiError> {
    let mut refreshed = false;
    let response = loop {
        let token = session::current().map(|s| s.access_token).unwrap_or_default();
        let headers = Headers::new().map_err(|_| ApiError::network("headers"))?;
        let _ = headers.set("X-StreamHub-Client", api::CLIENT_KIND);
        let _ = headers.set("Content-Type", "application/json");
        let _ = headers.set("Authorization", &format!("Bearer {token}"));
        let init = RequestInit::new();
        init.set_method("POST");
        init.set_headers(&headers);
        init.set_body(&JsValue::from_str(&body.to_string()));
        init.set_signal(Some(signal));
        let request = Request::new_with_str_and_init(&config::api_url(path), &init)
            .map_err(|_| ApiError::network("bad request"))?;
        let window = web_sys::window().ok_or_else(|| ApiError::network("no window"))?;
        let response: Response = JsFuture::from(window.fetch_with_request(&request))
            .await
            .map_err(|_| ApiError::network("無法連線到伺服器"))?
            .unchecked_into();
        if response.status() == 401 && !refreshed {
            refreshed = true;
            api::refresh().await?;
            continue;
        }
        if !(200..300).contains(&response.status()) {
            let text = match response.text() {
                Ok(p) => JsFuture::from(p).await.ok().and_then(|t| t.as_string()).unwrap_or_default(),
                Err(_) => String::new(),
            };
            let message = serde_json::from_str::<Value>(&text)
                .ok()
                .and_then(|v| v.get("error").and_then(Value::as_str).map(str::to_string))
                .unwrap_or_else(|| format!("伺服器錯誤（{}）", response.status()));
            return Err(ApiError { status: response.status(), message });
        }
        break response;
    };
    let Some(stream) = response.body() else { return Ok(()) };
    let reader: web_sys::ReadableStreamDefaultReader =
        stream.get_reader().dyn_into().map_err(|_| ApiError::network("reader"))?;
    let decoder = web_sys::TextDecoder::new().map_err(|_| ApiError::network("decoder"))?;
    let mut buffer = streamhub_core::ndjson::LineBuffer::default();
    loop {
        let chunk = JsFuture::from(reader.read())
            .await
            .map_err(|_| ApiError::network("stream interrupted"))?;
        let done = js_sys::Reflect::get(&chunk, &"done".into())
            .ok()
            .and_then(|v| v.as_bool())
            .unwrap_or(true);
        if done {
            buffer.finish().into_iter().for_each(&mut on_line);
            return Ok(());
        }
        let bytes = js_sys::Reflect::get(&chunk, &"value".into()).unwrap_or(JsValue::NULL);
        let text = decoder.decode_with_js_u8_array(&bytes.unchecked_into()).unwrap_or_default();
        buffer.push(&text).into_iter().for_each(&mut on_line);
    }
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
