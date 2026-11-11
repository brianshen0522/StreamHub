//! Signing in, once. Mirrors android/tv auth/LoginScreen.kt: a username and
//! password form on the left, and on the right a pairing code + QR that a
//! phone approves — no typing at all. Focus starts on the pairing panel, not
//! a field: focusing a field pops the IME, which covers the QR.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use gloo_timers::future::TimeoutFuture;
use leptos::prelude::*;
use leptos::task::spawn_local;
use serde_json::{json, Value};

use crate::api;
use crate::screens::common::s;
use crate::session::{self, Session};

const RETRY_MS: u32 = 5_000;
/// The QR is sized off this fixed height, never the panel's width (CLAUDE.md:
/// a width-derived square overflowed a television's height).
const QR_PX: u32 = 420;

#[derive(Clone, Debug, PartialEq)]
struct Pairing {
    user_code: String,
    verification_url: String,
    qr_svg: String,
}

fn qr_svg(content: &str) -> String {
    use qrcode::render::svg;
    match qrcode::QrCode::new(content.as_bytes()) {
        Ok(code) => code
            .render::<svg::Color>()
            .min_dimensions(QR_PX, QR_PX)
            .quiet_zone(true)
            .dark_color(svg::Color("#000000"))
            .light_color(svg::Color("#ffffff"))
            .build(),
        Err(_) => String::new(),
    }
}

fn now_ms() -> f64 {
    js_sys::Date::now()
}

/// A session from a login or approved-poll body, if it is a viewer account.
fn accept(body: &Value) -> Result<Session, String> {
    let session = Session::from_body(body, None).ok_or_else(|| "伺服器回應不正確".to_string())?;
    if !session.is_user_role() {
        return Err("管理員帳號無法在電視上觀看，請使用一般觀眾帳號。".into());
    }
    Ok(session)
}

/// Keeps a usable code on screen for as long as the screen is up: get one,
/// poll it until answered or expired, and on anything but approval get
/// another. Stops when `alive` goes false (the screen unmounted).
async fn pairing_loop(
    alive: Arc<AtomicBool>,
    pairing: RwSignal<Option<Pairing>>,
    message: RwSignal<Option<String>>,
    renew: Arc<AtomicBool>,
) {
    while alive.load(Ordering::Relaxed) {
        let start = match api::public_request("POST", "/auth/device/start", None).await {
            Ok(v) => v,
            Err(_) => {
                // The set may be on before the router is: say so and retry.
                if !alive.load(Ordering::Relaxed) {
                    return;
                }
                pairing.set(None);
                message.set(Some("正在等待伺服器…".into()));
                TimeoutFuture::new(RETRY_MS).await;
                continue;
            }
        };
        if !alive.load(Ordering::Relaxed) {
            return;
        }
        let device_code = s(&start, "deviceCode");
        let complete = s(&start, "verificationUrlComplete");
        if device_code.is_empty() {
            message.set(Some("無法取得配對碼".into()));
            TimeoutFuture::new(RETRY_MS).await;
            continue;
        }
        // deviceCode never reaches the screen or the QR.
        pairing.set(Some(Pairing {
            user_code: s(&start, "userCode"),
            verification_url: s(&start, "verificationUrl")
                .trim_start_matches("https://")
                .trim_start_matches("http://")
                .to_string(),
            qr_svg: qr_svg(&complete),
        }));
        message.set(None);
        renew.store(false, Ordering::Relaxed);

        let interval = start.get("intervalSeconds").and_then(Value::as_f64).unwrap_or(5.0).max(1.0);
        let expires_in = start.get("expiresInSeconds").and_then(Value::as_f64).unwrap_or(600.0).max(5.0);
        let expires_at = now_ms() + expires_in * 1000.0;
        loop {
            // Sleep in short slices so leaving the screen or asking for a new
            // code takes effect promptly.
            let wake = now_ms() + interval * 1000.0;
            while now_ms() < wake && alive.load(Ordering::Relaxed) && !renew.load(Ordering::Relaxed) {
                TimeoutFuture::new(250).await;
            }
            if !alive.load(Ordering::Relaxed) {
                return;
            }
            // The code dies on schedule, so it is replaced on schedule.
            if renew.load(Ordering::Relaxed) || now_ms() >= expires_at {
                break;
            }
            let body = json!({ "deviceCode": device_code });
            let Ok(answer) = api::public_request("POST", "/auth/device/poll", Some(&body)).await else {
                // A dropped poll is not an answer: keep the code up.
                continue;
            };
            if !alive.load(Ordering::Relaxed) {
                return;
            }
            match s(&answer, "status").as_str() {
                "approved" => match accept(&answer) {
                    Ok(session) => {
                        alive.store(false, Ordering::Relaxed);
                        session::set(Some(session));
                        return;
                    }
                    Err(e) => {
                        message.set(Some(e));
                        break;
                    }
                },
                "denied" | "expired" => break,
                _ => {}
            }
        }
        pairing.set(None);
    }
}

#[component]
pub fn LoginScreen() -> impl IntoView {
    let username = RwSignal::new(String::new());
    let password = RwSignal::new(String::new());
    let busy = RwSignal::new(false);
    let error = RwSignal::new(None::<String>);
    let pairing = RwSignal::new(None::<Pairing>);
    let pair_message = RwSignal::new(None::<String>);

    let alive = Arc::new(AtomicBool::new(true));
    let renew = Arc::new(AtomicBool::new(false));
    spawn_local(pairing_loop(alive.clone(), pairing, pair_message, renew.clone()));
    {
        let alive = alive.clone();
        on_cleanup(move || alive.store(false, Ordering::Relaxed));
    }

    let sign_in = move || {
        if busy.get_untracked() {
            return;
        }
        let login = username.get_untracked().trim().to_string();
        let pass = password.get_untracked();
        if login.is_empty() || pass.is_empty() {
            error.set(Some("請輸入帳號與密碼".into()));
            return;
        }
        busy.set(true);
        error.set(None);
        spawn_local(async move {
            let body = json!({ "login": login, "password": pass });
            let result = api::public_request("POST", "/auth/login", Some(&body)).await;
            busy.set(false);
            match result {
                Ok(v) => match accept(&v) {
                    Ok(session) => session::set(Some(session)),
                    Err(e) => error.set(Some(e)),
                },
                // The server's own text: 401 wrong password, 403 admin refusal,
                // 429 with the wait named.
                Err(e) => error.set(Some(if e.is_offline() { "無法連線到伺服器".into() } else { e.message })),
            }
        });
    };

    let renew_click = renew.clone();
    view! {
        <div class="tv-screen tv-login" data-screen="login">
            <div class="tv-login-panels">
                <form
                    class="tv-login-form"
                    autocomplete="off"
                    on:submit=move |ev: leptos::ev::SubmitEvent| {
                        ev.prevent_default();
                        sign_in();
                    }
                >
                    <h1 class="tv-brand tv-brand-large">"StreamHub"</h1>
                    <p class="tv-muted">"使用觀眾帳號登入"</p>
                    <label class="tv-field">
                        <span class="tv-field-label">"帳號"</span>
                        <input
                            class="tv-input"
                            name="username"
                            type="text"
                            autocomplete="off"
                            autocapitalize="off"
                            spellcheck="false"
                            data-focusable=""
                            data-focus-id="login-username"
                            prop:value=move || username.get()
                            on:input=move |ev| username.set(event_target_value(&ev))
                        />
                    </label>
                    <label class="tv-field">
                        <span class="tv-field-label">"密碼"</span>
                        <input
                            class="tv-input"
                            name="password"
                            type="password"
                            autocomplete="off"
                            data-focusable=""
                            data-focus-id="login-password"
                            prop:value=move || password.get()
                            on:input=move |ev| password.set(event_target_value(&ev))
                        />
                    </label>
                    <button
                        type="button"
                        class="tv-btn tv-btn-primary tv-btn-wide"
                        data-focusable=""
                        data-action="login"
                        on:click=move |_| sign_in()
                    >
                        {move || if busy.get() { "登入中…" } else { "登入" }}
                    </button>
                    <p class="tv-error" role="alert">{move || error.get().unwrap_or_default()}</p>
                </form>

                <div
                    class="tv-pair"
                    data-focusable=""
                    data-autofocus=""
                    data-action="pair"
                    data-focus-id="login-pair"
                    tabindex="-1"
                    on:click=move |_| renew_click.store(true, Ordering::Relaxed)
                >
                    <h2 class="tv-pair-title">"用手機登入"</h2>
                    <div class="tv-qr">
                        {move || match pairing.get() {
                            Some(p) if !p.qr_svg.is_empty() => {
                                view! { <div class="tv-qr-code" inner_html=p.qr_svg></div> }.into_any()
                            }
                            _ => view! {
                                <div class="tv-qr-wait">
                                    {move || pair_message.get().unwrap_or_else(|| "正在取得配對碼…".into())}
                                </div>
                            }
                            .into_any(),
                        }}
                    </div>
                    // Shown exactly as the server formats it; `nowrap` in CSS keeps
                    // the two halves from wrapping apart.
                    <div class="tv-user-code" data-user-code="">
                        {move || pairing.get().map(|p| p.user_code).unwrap_or_default()}
                    </div>
                    <div class="tv-pair-hint">
                        {move || {
                            pairing
                                .get()
                                .map(|p| format!("掃描 QR 碼，或前往 {} 輸入代碼", p.verification_url))
                                .unwrap_or_default()
                        }}
                    </div>
                    {move || pair_message.get().filter(|_| pairing.get().is_some()).map(|m| view! { <p class="tv-error">{m}</p> })}
                </div>
            </div>
        </div>
    }
}
