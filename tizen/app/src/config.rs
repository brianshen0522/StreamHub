//! Where the server is. A packaged app has no origin of its own to fall back
//! on, so the base is compiled in — `STREAMHUB_API_BASE` at build time — and
//! may be overridden at run time by `localStorage["streamhub.apiBase"]`, which
//! is how the QA harness points a build at a local server.

const COMPILED_BASE: &str = match option_env!("STREAMHUB_API_BASE") {
    Some(base) => base,
    None => "https://streamhub.gugulu.tw",
};

pub fn api_base() -> String {
    let override_base = web_sys::window()
        .and_then(|w| w.local_storage().ok().flatten())
        .and_then(|s| s.get_item("streamhub.apiBase").ok().flatten())
        .filter(|v| !v.trim().is_empty());
    override_base
        .unwrap_or_else(|| COMPILED_BASE.to_string())
        .trim_end_matches('/')
        .to_string()
}

/// Every route under the version the contract pins native clients to.
pub fn api_url(path: &str) -> String {
    let path = path.strip_prefix("/api").unwrap_or(path);
    format!("{}/api/v1{}", api_base(), path)
}

/// Same, as a WebSocket URL.
pub fn realtime_url() -> String {
    let base = api_base();
    let ws = if let Some(rest) = base.strip_prefix("https://") {
        format!("wss://{rest}")
    } else if let Some(rest) = base.strip_prefix("http://") {
        format!("ws://{rest}")
    } else {
        base
    };
    // Unversioned on purpose: the /api/v1 rewrite is HTTP middleware and never
    // sees an upgrade, so the socket only answers at /api/realtime (as the
    // Android and iOS clients use it).
    format!("{ws}/api/realtime")
}
