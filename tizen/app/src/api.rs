//! The fetch layer: every request the app makes goes through here.
//!
//! The rules come from shared/api/README.md:
//! - `X-StreamHub-Client: tv` on every request, so the server refuses admin
//!   accounts at login instead of handing out a token that 403s everywhere.
//! - On any 401, refresh once and retry once, then sign out.
//! - **Refresh rotates**, so it is single-flight: concurrent callers await the
//!   one in-flight refresh. Two refreshes in parallel would kill the session.
//!   The realtime socket goes through the same `refresh()`.

use std::cell::RefCell;

use futures::future::{FutureExt, LocalBoxFuture, Shared};
use serde_json::Value;
use wasm_bindgen::{JsCast, JsValue};
use wasm_bindgen_futures::JsFuture;
use web_sys::{Headers, Request, RequestInit, Response};

use crate::config::api_url;
use crate::session::{self, Session};

pub const CLIENT_KIND: &str = "tv";

#[derive(Clone, Debug, PartialEq)]
pub struct ApiError {
    /// HTTP status, or 0 when no answer arrived at all (network down, timeout).
    pub status: u16,
    /// The server's `error` text when it gave one — fit to show on screen.
    pub message: String,
}

impl ApiError {
    pub fn network(message: impl Into<String>) -> Self {
        ApiError { status: 0, message: message.into() }
    }
    pub fn is_offline(&self) -> bool {
        self.status == 0
    }
}

impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

/// `?a=1&b=2`, percent-encoded. Empty values are kept: the server reads them.
pub fn query(pairs: &[(&str, &str)]) -> String {
    if pairs.is_empty() {
        return String::new();
    }
    let parts: Vec<String> = pairs
        .iter()
        .map(|(k, v)| {
            format!(
                "{}={}",
                js_sys::encode_uri_component(k),
                js_sys::encode_uri_component(v)
            )
        })
        .collect();
    format!("?{}", parts.join("&"))
}

/// A media URL (`/api/manifest`, `/api/stream`, `/api/poster`) with the access
/// token in the query — the only three routes that accept it there, because
/// AVPlay and `<img>` cannot set headers.
pub fn media_url(path: &str, pairs: &[(&str, &str)]) -> String {
    let token = session::current().map(|s| s.access_token).unwrap_or_default();
    let mut all: Vec<(&str, &str)> = pairs.to_vec();
    all.push(("accessToken", token.as_str()));
    format!("{}{}", api_url(path), query(&all))
}

/// `/api/poster?target=…` for a provider poster URL, or "" when there is none.
pub fn poster_url(target: &str) -> String {
    if target.trim().is_empty() {
        return String::new();
    }
    media_url("/poster", &[("target", target)])
}

fn build_request(
    method: &str,
    path: &str,
    body: Option<&Value>,
    token: Option<&str>,
    signal: Option<&web_sys::AbortSignal>,
) -> Result<Request, ApiError> {
    let headers = Headers::new().map_err(|_| ApiError::network("headers"))?;
    let _ = headers.set("X-StreamHub-Client", CLIENT_KIND);
    let _ = headers.set("Accept", "application/json");
    if let Some(t) = token {
        let _ = headers.set("Authorization", &format!("Bearer {t}"));
    }
    let init = RequestInit::new();
    init.set_method(method);
    init.set_headers(&headers);
    if let Some(b) = body {
        let _ = headers.set("Content-Type", "application/json");
        init.set_body(&JsValue::from_str(&b.to_string()));
    }
    if let Some(s) = signal {
        init.set_signal(Some(s));
    }
    Request::new_with_str_and_init(&api_url(path), &init)
        .map_err(|_| ApiError::network("bad request"))
}

async fn send(request: Request) -> Result<Response, ApiError> {
    let window = web_sys::window().ok_or_else(|| ApiError::network("no window"))?;
    let value = JsFuture::from(window.fetch_with_request(&request))
        .await
        .map_err(|_| ApiError::network("無法連線到伺服器"))?;
    value
        .dyn_into::<Response>()
        .map_err(|_| ApiError::network("bad response"))
}

async fn body_json(response: &Response) -> Value {
    let Ok(promise) = response.text() else { return Value::Null };
    let Ok(text) = JsFuture::from(promise).await else { return Value::Null };
    let text = text.as_string().unwrap_or_default();
    if text.is_empty() {
        return Value::Null;
    }
    serde_json::from_str(&text).unwrap_or(Value::Null)
}

fn error_from(status: u16, body: &Value) -> ApiError {
    let message = body
        .get("error")
        .and_then(Value::as_str)
        .map(str::to_string)
        .unwrap_or_else(|| format!("伺服器錯誤（{status}）"));
    ApiError { status, message }
}

async fn send_json(
    method: &str,
    path: &str,
    body: Option<&Value>,
    token: Option<&str>,
) -> Result<Value, ApiError> {
    let response = send(build_request(method, path, body, token, None)?).await?;
    let status = response.status();
    let json = body_json(&response).await;
    if (200..300).contains(&status) || status == 304 {
        Ok(json)
    } else {
        Err(error_from(status, &json))
    }
}

/// A request that needs no session: login, device start/poll, health.
pub async fn public_request(method: &str, path: &str, body: Option<&Value>) -> Result<Value, ApiError> {
    send_json(method, path, body, None).await
}

type RefreshFuture = Shared<LocalBoxFuture<'static, Result<(), ApiError>>>;

thread_local! {
    static IN_FLIGHT: RefCell<Option<RefreshFuture>> = const { RefCell::new(None) };
}

async fn do_refresh() -> Result<(), ApiError> {
    let Some(current) = session::current() else {
        return Err(ApiError { status: 401, message: "signed out".into() });
    };
    let body = serde_json::json!({ "refreshToken": current.refresh_token });
    match send_json("POST", "/auth/refresh", Some(&body), None).await {
        Ok(json) => match Session::from_body(&json, Some(&current.user)) {
            Some(next) => {
                session::set(Some(next));
                Ok(())
            }
            None => Err(ApiError { status: 500, message: "bad refresh body".into() }),
        },
        // A refresh that got no answer is not a rejection: keep the session
        // and let the caller report offline.
        Err(e) if e.is_offline() => Err(e),
        Err(e) => {
            session::set(None);
            Err(e)
        }
    }
}

/// Single-flight token refresh. Every concurrent caller awaits the same request.
pub async fn refresh() -> Result<(), ApiError> {
    let fut = IN_FLIGHT.with(|slot| {
        let mut slot = slot.borrow_mut();
        if let Some(f) = slot.as_ref() {
            return f.clone();
        }
        let f: RefreshFuture = async {
            let result = do_refresh().await;
            IN_FLIGHT.with(|s| *s.borrow_mut() = None);
            result
        }
        .boxed_local()
        .shared();
        *slot = Some(f.clone());
        f
    });
    fut.await
}

/// An authenticated JSON request. Refreshes once on 401 and retries once.
pub async fn request(method: &str, path: &str, body: Option<&Value>) -> Result<Value, ApiError> {
    let token = session::current().map(|s| s.access_token);
    let Some(token) = token else {
        return Err(ApiError { status: 401, message: "signed out".into() });
    };
    match send_json(method, path, body, Some(&token)).await {
        Err(e) if e.status == 401 => {
            refresh().await?;
            let token = session::current().map(|s| s.access_token).unwrap_or_default();
            let result = send_json(method, path, body, Some(&token)).await;
            if matches!(&result, Err(e) if e.status == 401) {
                session::set(None);
            }
            result
        }
        other => other,
    }
}

pub async fn get(path: &str) -> Result<Value, ApiError> {
    request("GET", path, None).await
}

/// NDJSON (`/api/sources`): calls `on_line` with each object as it arrives.
/// Lines are split with `streamhub_core::ndjson::LineBuffer`. Pass an
/// AbortSignal to stop early (e.g. the person left the screen).
pub async fn ndjson(
    path: &str,
    signal: Option<&web_sys::AbortSignal>,
    mut on_line: impl FnMut(Value),
) -> Result<(), ApiError> {
    let mut attempt = 0;
    let response = loop {
        let token = session::current().map(|s| s.access_token).unwrap_or_default();
        let response = send(build_request("GET", path, None, Some(&token), signal)?).await?;
        if response.status() == 401 && attempt == 0 {
            attempt += 1;
            refresh().await?;
            continue;
        }
        if !(200..300).contains(&response.status()) {
            let json = body_json(&response).await;
            return Err(error_from(response.status(), &json));
        }
        break response;
    };
    let Some(stream) = response.body() else { return Ok(()) };
    let reader: web_sys::ReadableStreamDefaultReader = stream
        .get_reader()
        .dyn_into()
        .map_err(|_| ApiError::network("reader"))?;
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
            for value in buffer.finish() {
                on_line(value);
            }
            return Ok(());
        }
        let bytes = js_sys::Reflect::get(&chunk, &"value".into()).unwrap_or(JsValue::NULL);
        let text = decoder
            .decode_with_js_u8_array(&bytes.unchecked_into())
            .unwrap_or_default();
        for value in buffer.push(&text) {
            on_line(value);
        }
    }
}
