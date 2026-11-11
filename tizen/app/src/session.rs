//! The signed-in session, persisted in localStorage so the monitor stays
//! signed in across launches (refresh tokens live 90 days of idleness).

use leptos::prelude::*;
use serde::{Deserialize, Serialize};
use serde_json::Value;

const KEY: &str = "streamhub.session";

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Session {
    pub access_token: String,
    pub refresh_token: String,
    /// The `user` object exactly as the server sent it.
    pub user: Value,
}

impl Session {
    /// From a login / refresh / approved-poll body: `{ user, accessToken, refreshToken }`.
    /// `user` may be absent on refresh, in which case `previous_user` is kept.
    pub fn from_body(body: &Value, previous_user: Option<&Value>) -> Option<Session> {
        let access_token = body.get("accessToken")?.as_str()?.to_string();
        let refresh_token = body.get("refreshToken")?.as_str()?.to_string();
        let user = body
            .get("user")
            .cloned()
            .or_else(|| previous_user.cloned())
            .unwrap_or(Value::Null);
        Some(Session { access_token, refresh_token, user })
    }

    pub fn display_name(&self) -> String {
        self.user
            .get("displayName")
            .or_else(|| self.user.get("username"))
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string()
    }

    pub fn is_user_role(&self) -> bool {
        self.user.get("role").and_then(Value::as_str) == Some("USER")
    }
}

fn storage() -> Option<web_sys::Storage> {
    web_sys::window()?.local_storage().ok().flatten()
}

pub fn load() -> Option<Session> {
    let raw = storage()?.get_item(KEY).ok().flatten()?;
    serde_json::from_str(&raw).ok()
}

pub fn save(session: &Session) {
    if let (Some(s), Ok(raw)) = (storage(), serde_json::to_string(session)) {
        let _ = s.set_item(KEY, &raw);
    }
}

pub fn clear() {
    if let Some(s) = storage() {
        let _ = s.remove_item(KEY);
    }
}

// An Arc signal, not RwSignal: a RwSignal belongs to whichever reactive Owner
// is current when it is created, and this thread_local is created lazily on
// first access — inside some component — so it was disposed with that
// component and every later read panicked. Arc signals have no owner.
thread_local! {
    static SESSION: ArcRwSignal<Option<Session>> = ArcRwSignal::new(load());
}

/// The reactive session. `None` means signed out; the router shows Login.
pub fn signal() -> ArcRwSignal<Option<Session>> {
    SESSION.with(|s| s.clone())
}

pub fn current() -> Option<Session> {
    signal().get_untracked()
}

pub fn set(session: Option<Session>) {
    match &session {
        Some(s) => save(s),
        None => clear(),
    }
    signal().set(session);
}
