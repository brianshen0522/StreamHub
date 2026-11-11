//! OWNED BY THE PLAYER AGENT: the /api/realtime socket — auth frame, close
//! codes (4002 → api::refresh() then reconnect), backoff, and the cast
//! receiver (announce, publish state, obey commands).
//!
//! Contract: shared/api/README.md "Realtime" and android/CASTING.md.
//! - Auth is the first frame, `{type:"auth", token}`, within 5 s.
//! - After `ready` this set announces itself with a playback frame (null
//!   state when idle): sending one at all is what lists it as a receiver, and
//!   the server forgets receivers on every reconnect, so it is re-sent then.
//! - 4002: the token behind the handshake expired — refresh *first*, then
//!   reconnect. 4003: unauthorized — stop until a different token appears.
//!   Anything else: backoff 1 s … 30 s.
//! - Commands: `play` navigates (Route::Player, from_cast); the transport
//!   ones go to whichever player registered a handler. Unknown ones are
//!   ignored, never an error.
//!
//! Library events (`favorites`, `progress`) are not consumed by any screen
//! yet; [`library_version`] ticks on each so a screen can refetch.

use std::cell::RefCell;
use std::rc::Rc;

use gloo_timers::callback::{Interval, Timeout};
use leptos::prelude::*;
use leptos::reactive::owner::Owner;
use serde_json::{json, Value};
use wasm_bindgen::prelude::*;
use web_sys::{CloseEvent, MessageEvent, WebSocket};

use crate::api;
use crate::config;
use crate::nav::{self, PlayTarget, Route};
use crate::session;

pub const CLOSE_AUTH_TIMEOUT: u16 = 4001;
pub const CLOSE_TOKEN_EXPIRED: u16 = 4002;
pub const CLOSE_UNAUTHORIZED: u16 = 4003;
const BACKOFF_BASE_MS: u32 = 1_000;
const BACKOFF_MAX_MS: u32 = 30_000;
const HEARTBEAT_MS: u32 = 45_000;
/// The "controlled by" badge's freshness window, the same 12 s as the phones'.
const DRIVER_FRESH_MS: f64 = 12_000.0;
/// Refresh before connecting when the token has less than this left: the
/// server answers an expired token at handshake with 4003, not 4002.
const EXPIRY_MARGIN_MS: f64 = 60_000.0;

/// A transport command from a phone, for the player on screen.
#[derive(Clone, Debug, PartialEq)]
pub enum Command {
    Pause,
    Resume,
    Stop,
    Seek(f64),
    Next,
    Previous,
}

pub type CommandHandler = Rc<dyn Fn(Command)>;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum CloseAction {
    RefreshThenReconnect,
    Stop,
    Backoff(u32),
}

fn backoff_ms(attempts: u32) -> u32 {
    (BACKOFF_BASE_MS << attempts.min(5)).min(BACKOFF_MAX_MS)
}

fn close_action(code: u16, attempts: u32) -> CloseAction {
    match code {
        CLOSE_TOKEN_EXPIRED => CloseAction::RefreshThenReconnect,
        CLOSE_UNAUTHORIZED => CloseAction::Stop,
        // The auth frame took over 5 s: a slow link, not a bad token.
        CLOSE_AUTH_TIMEOUT => CloseAction::Backoff(backoff_ms(attempts)),
        // 1006, 1001, anything else.
        _ => CloseAction::Backoff(backoff_ms(attempts)),
    }
}

struct Conn {
    id: u64,
    ws: WebSocket,
    token: String,
    ready: bool,
    _on_open: Closure<dyn FnMut()>,
    _on_message: Closure<dyn FnMut(MessageEvent)>,
    _on_close: Closure<dyn FnMut(CloseEvent)>,
}

impl Conn {
    fn detach(&self) {
        self.ws.set_onopen(None);
        self.ws.set_onmessage(None);
        self.ws.set_onclose(None);
    }
}

#[derive(Default)]
struct Rt {
    conn: Option<Conn>,
    seq: u64,
    connecting: bool,
    attempts: u32,
    reconnect: Option<Timeout>,
    heartbeat: Option<Interval>,
    /// A token the server refused with 4003; wait for a different one.
    rejected: Option<String>,
    /// What this set last said it is doing — re-sent after every reconnect.
    state: Option<Value>,
    session_id: Option<String>,
    handler: Option<(u64, CommandHandler)>,
    handler_seq: u64,
    driver: Option<(String, f64)>,
}

thread_local! {
    static RT: RefCell<Rt> = RefCell::new(Rt::default());
    static LIBRARY: ArcRwSignal<u64> = ArcRwSignal::new(0);
}

fn now() -> f64 {
    js_sys::Date::now()
}

/// Ticks on every `favorites` / `progress` event for this account.
pub fn library_version() -> ArcRwSignal<u64> {
    LIBRARY.with(|s| s.clone())
}

/// Start the socket whenever a session exists; stop when it goes away.
pub fn start() {
    // Deferred one task: main() calls this before mount_to_body, which is what
    // initialises the executor an Effect is scheduled on.
    Timeout::new(0, || {
        let owner = Owner::new();
        owner.with(|| {
            Effect::new(move |_| {
                let token = session::signal().with(|s| s.as_ref().map(|s| s.access_token.clone()));
                on_token(token);
            });
        });
        std::mem::forget(owner);
    })
    .forget();
}

fn on_token(token: Option<String>) {
    let Some(token) = token else {
        disconnect();
        RT.with(|rt| {
            let mut rt = rt.borrow_mut();
            rt.heartbeat = None;
            rt.reconnect = None;
            rt.rejected = None;
            rt.state = None;
            rt.attempts = 0;
            rt.driver = None;
        });
        return;
    };
    let start_heartbeat = RT.with(|rt| rt.borrow().heartbeat.is_none());
    if start_heartbeat {
        heartbeat();
        let interval = Interval::new(HEARTBEAT_MS, heartbeat);
        RT.with(|rt| rt.borrow_mut().heartbeat = Some(interval));
    }
    // A refresh elsewhere changes the token under a healthy socket; that is
    // no reason to drop it. Only connect when there is nothing live or pending.
    let should_connect = RT.with(|rt| {
        let mut rt = rt.borrow_mut();
        if rt.conn.is_some() || rt.connecting || rt.reconnect.is_some() {
            return false;
        }
        if rt.rejected.as_deref() == Some(token.as_str()) {
            return false;
        }
        rt.rejected = None;
        true
    });
    if should_connect {
        connect();
    }
}

fn heartbeat() {
    if session::current().is_none() {
        return;
    }
    wasm_bindgen_futures::spawn_local(async {
        let _ = api::request("POST", "/auth/heartbeat", None).await;
    });
}

fn schedule_reconnect(ms: u32) {
    let timer = Timeout::new(ms, || {
        RT.with(|rt| rt.borrow_mut().reconnect = None);
        if session::current().is_some() {
            connect();
        }
    });
    RT.with(|rt| rt.borrow_mut().reconnect = Some(timer));
}

fn connect() {
    let Some(current) = session::current() else { return };
    let busy = RT.with(|rt| {
        let mut rt = rt.borrow_mut();
        let busy = rt.conn.is_some() || rt.connecting;
        if !busy {
            rt.connecting = true;
        }
        busy
    });
    if busy {
        return;
    }
    let expiring = token_expiry_ms(&current.access_token).is_some_and(|exp| exp - now() < EXPIRY_MARGIN_MS);
    if !expiring {
        open_socket(current.access_token);
        return;
    }
    wasm_bindgen_futures::spawn_local(async {
        let result = api::refresh().await;
        RT.with(|rt| rt.borrow_mut().connecting = false);
        match result {
            Ok(()) => connect(),
            Err(e) if e.is_offline() => {
                let ms = next_backoff();
                schedule_reconnect(ms);
            }
            // The refresh token is dead too; api::refresh signed out.
            Err(_) => {}
        }
    });
}

fn next_backoff() -> u32 {
    RT.with(|rt| {
        let mut rt = rt.borrow_mut();
        let ms = backoff_ms(rt.attempts);
        rt.attempts = rt.attempts.saturating_add(1);
        ms
    })
}

fn open_socket(token: String) {
    let ws = match WebSocket::new(&config::realtime_url()) {
        Ok(ws) => ws,
        Err(_) => {
            RT.with(|rt| rt.borrow_mut().connecting = false);
            let ms = next_backoff();
            schedule_reconnect(ms);
            return;
        }
    };
    let id = RT.with(|rt| {
        let mut rt = rt.borrow_mut();
        rt.seq += 1;
        rt.seq
    });

    let on_open = {
        let ws = ws.clone();
        let token = token.clone();
        Closure::<dyn FnMut()>::new(move || {
            let _ = ws.send_with_str(&json!({ "type": "auth", "token": token }).to_string());
        })
    };
    let on_message = Closure::<dyn FnMut(MessageEvent)>::new(move |e: MessageEvent| {
        if let Some(text) = e.data().as_string() {
            on_frame(id, &text);
        }
    });
    let on_close = Closure::<dyn FnMut(CloseEvent)>::new(move |e: CloseEvent| on_closed(id, e.code()));
    ws.set_onopen(Some(on_open.as_ref().unchecked_ref()));
    ws.set_onmessage(Some(on_message.as_ref().unchecked_ref()));
    ws.set_onclose(Some(on_close.as_ref().unchecked_ref()));

    RT.with(|rt| {
        let mut rt = rt.borrow_mut();
        rt.connecting = false;
        rt.conn = Some(Conn {
            id,
            ws,
            token,
            ready: false,
            _on_open: on_open,
            _on_message: on_message,
            _on_close: on_close,
        });
    });
}

fn disconnect() {
    let conn = RT.with(|rt| {
        let mut rt = rt.borrow_mut();
        rt.session_id = None;
        rt.conn.take()
    });
    if let Some(conn) = conn {
        conn.detach();
        let _ = conn.ws.close();
    }
}

fn on_closed(id: u64, code: u16) {
    let taken = RT.with(|rt| {
        let mut rt = rt.borrow_mut();
        if rt.conn.as_ref().map(|c| c.id) != Some(id) {
            return None;
        }
        rt.session_id = None;
        let conn = rt.conn.take()?;
        if conn.ready {
            rt.attempts = 0;
        }
        Some(conn)
    });
    let Some(conn) = taken else { return };
    let token = conn.token.clone();
    conn.detach();
    // This runs inside one of the connection's own closures: free them later.
    Timeout::new(0, move || drop(conn)).forget();
    if session::current().is_none() {
        return;
    }
    let attempts = RT.with(|rt| rt.borrow().attempts);
    match close_action(code, attempts) {
        CloseAction::RefreshThenReconnect => {
            RT.with(|rt| rt.borrow_mut().attempts = 0);
            wasm_bindgen_futures::spawn_local(async {
                match api::refresh().await {
                    Ok(()) => connect(),
                    Err(e) if e.is_offline() => {
                        let ms = next_backoff();
                        schedule_reconnect(ms);
                    }
                    Err(_) => {}
                }
            });
        }
        CloseAction::Stop => {
            let current = session::current().map(|s| s.access_token);
            if current.as_deref() == Some(token.as_str()) {
                RT.with(|rt| rt.borrow_mut().rejected = Some(token));
            } else {
                // Already holding a newer token than the one refused.
                connect();
            }
        }
        CloseAction::Backoff(ms) => {
            RT.with(|rt| rt.borrow_mut().attempts = attempts.saturating_add(1));
            schedule_reconnect(ms);
        }
    }
}

fn send_on_ready_socket(frame: &Value) {
    RT.with(|rt| {
        let rt = rt.borrow();
        if let Some(conn) = rt.conn.as_ref().filter(|c| c.ready) {
            let _ = conn.ws.send_with_str(&frame.to_string());
        }
    });
}

fn playback_frame(state: &Option<Value>) -> Value {
    // `state` must be present even when null: the frame is the announcement.
    json!({ "type": "playback", "state": state.clone().unwrap_or(Value::Null) })
}

fn on_frame(id: u64, text: &str) {
    let Ok(frame) = serde_json::from_str::<Value>(text) else { return };
    match frame.get("type").and_then(Value::as_str).unwrap_or("") {
        "ready" => {
            let announce = RT.with(|rt| {
                let mut rt = rt.borrow_mut();
                let state = rt.state.clone();
                let Some(conn) = rt.conn.as_mut().filter(|c| c.id == id) else { return None };
                conn.ready = true;
                rt.attempts = 0;
                rt.session_id = frame.get("sessionId").and_then(Value::as_str).map(str::to_string);
                Some(playback_frame(&state))
            });
            if let Some(f) = announce {
                send_on_ready_socket(&f);
            }
        }
        "command" => on_command(&frame),
        "favorites" | "progress" => library_version().update(|v| *v += 1),
        // receivers, server pings as frames, anything newer: not ours.
        _ => {}
    }
}

fn on_command(frame: &Value) {
    let Some(command) = frame.get("command") else { return };
    let action = command.get("action").and_then(Value::as_str).unwrap_or("");
    let transport = match action {
        "pause" => Some(Command::Pause),
        "resume" => Some(Command::Resume),
        "stop" => Some(Command::Stop),
        "next" => Some(Command::Next),
        "previous" => Some(Command::Previous),
        "seek" => command.get("positionMs").and_then(Value::as_f64).map(Command::Seek),
        "play" => None,
        // fullscreen (a television already is), and anything a newer phone sends.
        _ => return,
    };
    if action == "seek" && transport.is_none() {
        return;
    }
    let from_name = frame
        .get("fromName")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    if !from_name.is_empty() {
        RT.with(|rt| rt.borrow_mut().driver = Some((from_name, now())));
    }
    match transport {
        Some(cmd) => {
            let handler = RT.with(|rt| rt.borrow().handler.as_ref().map(|(_, h)| h.clone()));
            if let Some(h) = handler {
                h(cmd);
            }
        }
        None => {
            if let Some(target) = command.get("playback").and_then(play_target) {
                let on_player = nav::stack().with_untracked(|s| matches!(s.last(), Some(Route::Player(_))));
                if on_player {
                    nav::replace(Route::Player(target));
                } else {
                    nav::push(Route::Player(target));
                }
            }
        }
    }
}

/// A cast `play` as a PlayTarget. `streamUrl` is the provider's direct URL;
/// the player resolves `/api/manifest` from it with this set's own token.
/// `episodeUrl` is the progress key's season URL, as android/tv reads it.
fn play_target(p: &Value) -> Option<PlayTarget> {
    let s = |k: &str| p.get(k).and_then(Value::as_str).unwrap_or("").to_string();
    let stream_url = s("streamUrl");
    if stream_url.is_empty() {
        return None;
    }
    let episode = s("episodeLabel");
    let mut episodes = Vec::new();
    if !episode.is_empty() {
        let (prev, next) = (s("prevEpisodeLabel"), s("nextEpisodeLabel"));
        if !prev.is_empty() {
            episodes.push(prev);
        }
        episodes.push(episode.clone());
        if !next.is_empty() {
            episodes.push(next);
        }
    }
    let position_ms = p.get("positionMs").and_then(Value::as_f64).unwrap_or(0.0).max(0.0);
    Some(PlayTarget {
        provider: s("provider"),
        item_url: s("itemUrl"),
        season_url: s("episodeUrl"),
        title: s("title"),
        poster_url: s("posterUrl"),
        media_type: String::new(),
        season_label: String::new(),
        episode_label: episode,
        episodes,
        source: Some(json!({ "directUrl": stream_url, "sourceLabel": s("subtitle") })),
        start_seconds: Some((position_ms / 1000.0).floor()),
        from_cast: true,
    })
}

// ── API for the player ─────────────────────────────────────────────────────

/// Say what this set is doing (`None` = idle). Remembered and re-sent after
/// every reconnect. Allowed fields: provider, itemUrl, title, subtitle,
/// posterUrl, episodeLabel, positionMs, durationMs, paused (intent),
/// buffering, hasNext, hasPrevious, controlledBy.
pub fn publish_state(state: Option<Value>) {
    let frame = playback_frame(&state);
    RT.with(|rt| rt.borrow_mut().state = state);
    send_on_ready_socket(&frame);
}

/// Register the on-screen player for transport commands. Returns a token for
/// [`clear_command_handler`], so a player being replaced cannot clear the
/// handler its successor already installed.
pub fn set_command_handler(handler: CommandHandler) -> u64 {
    RT.with(|rt| {
        let mut rt = rt.borrow_mut();
        rt.handler_seq += 1;
        let id = rt.handler_seq;
        rt.handler = Some((id, handler));
        id
    })
}

pub fn clear_command_handler(id: u64) {
    RT.with(|rt| {
        let mut rt = rt.borrow_mut();
        if rt.handler.as_ref().map(|(i, _)| *i) == Some(id) {
            rt.handler = None;
        }
    });
}

/// The phone that last drove this set, while that is fresh (12 s).
pub fn controlled_by() -> Option<String> {
    RT.with(|rt| {
        let rt = rt.borrow();
        rt.driver
            .as_ref()
            .filter(|(_, at)| now() - at < DRIVER_FRESH_MS)
            .map(|(name, _)| name.clone())
    })
}

// ── token expiry, without a base64 dependency ──────────────────────────────

fn token_expiry_ms(token: &str) -> Option<f64> {
    let payload = token.split('.').nth(1)?;
    let bytes = base64url_decode(payload)?;
    let claims: Value = serde_json::from_slice(&bytes).ok()?;
    claims.get("exp").and_then(Value::as_f64).map(|s| s * 1000.0)
}

fn base64url_decode(input: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(input.len() * 3 / 4);
    let (mut acc, mut bits) = (0u32, 0u32);
    for c in input.bytes() {
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'-' | b'+' => 62,
            b'_' | b'/' => 63,
            b'=' => break,
            _ => return None,
        } as u32;
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn close_codes() {
        assert_eq!(close_action(4002, 7), CloseAction::RefreshThenReconnect);
        assert_eq!(close_action(4003, 0), CloseAction::Stop);
        assert_eq!(close_action(4001, 0), CloseAction::Backoff(1_000));
        assert_eq!(close_action(1006, 1), CloseAction::Backoff(2_000));
        assert_eq!(close_action(1006, 4), CloseAction::Backoff(16_000));
        assert_eq!(close_action(1006, 5), CloseAction::Backoff(30_000));
        assert_eq!(close_action(1006, 60), CloseAction::Backoff(30_000));
    }

    #[test]
    fn reads_jwt_expiry() {
        // {"sub":"u","exp":1700000000}
        let t = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1IiwiZXhwIjoxNzAwMDAwMDAwfQ.sig";
        assert_eq!(token_expiry_ms(t), Some(1_700_000_000_000.0));
        assert_eq!(token_expiry_ms("garbage"), None);
    }

    #[test]
    fn cast_play_builds_a_target() {
        let p = json!({
            "streamUrl": "https://cdn/x.m3u8", "provider": "dramasq", "itemUrl": "https://d/1",
            "title": "T", "episodeLabel": "02", "prevEpisodeLabel": "01", "nextEpisodeLabel": "03",
            "episodeUrl": "https://d/1", "subtitle": "線路1", "positionMs": 61500
        });
        let t = play_target(&p).unwrap();
        assert_eq!(t.episodes, vec!["01", "02", "03"]);
        assert_eq!(t.start_seconds, Some(61.0));
        assert!(t.from_cast);
        assert_eq!(t.source.unwrap()["directUrl"], "https://cdn/x.m3u8");
        assert!(play_target(&json!({ "title": "no url" })).is_none());
    }

    #[test]
    fn idle_announcement_carries_explicit_null() {
        let f = playback_frame(&None);
        assert_eq!(f, json!({ "type": "playback", "state": null }));
        assert!(f.to_string().contains(r#""state":null"#));
    }
}
