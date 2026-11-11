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
use crate::screens::common::{friendly_error, s};
use crate::focus;
use crate::keys::Key;
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

/// Monotonic milliseconds (performance.now()): the wall clock can jump
/// (an NTP sync on a set that just booted) and would stall polling or expire
/// codes at the wrong time. Falls back to Date.now() if unavailable.
fn now_ms() -> f64 {
    web_sys::window()
        .and_then(|w| js_sys::Reflect::get(&w, &"performance".into()).ok())
        .filter(|p| !p.is_undefined() && !p.is_null())
        .and_then(|p| {
            let f = js_sys::Reflect::get(&p, &"now".into()).ok()?;
            let f: js_sys::Function = wasm_bindgen::JsCast::dyn_into(f).ok()?;
            f.call0(&p).ok()?.as_f64()
        })
        .unwrap_or_else(js_sys::Date::now)
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
    expiry: RwSignal<Option<(f64, f64)>>,
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
        let issued_at = now_ms();
        let expires_at = issued_at + expires_in * 1000.0;
        expiry.set(Some((issued_at, expires_at)));
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

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Mode {
    /// A code and a QR: the default, no typing.
    Pair,
    /// Username and password.
    Password,
}

/// "m:ss".
fn mmss(ms: f64) -> String {
    let t = (ms / 1000.0).max(0.0).ceil() as u64;
    format!("{}:{:02}", t / 60, t % 60)
}

#[component]
pub fn LoginScreen() -> impl IntoView {
    let username = RwSignal::new(String::new());
    let password = RwSignal::new(String::new());
    let busy = RwSignal::new(false);
    let error = RwSignal::new(None::<String>);
    let pairing = RwSignal::new(None::<Pairing>);
    let pair_message = RwSignal::new(None::<String>);
    let expiry = RwSignal::new(None::<(f64, f64)>);
    let mode = RwSignal::new(Mode::Pair);
    // Ticks once a second for the countdown under the code.
    let now = RwSignal::new(now_ms());
    let ticker = StoredValue::new_local(Some(gloo_timers::callback::Interval::new(1000, move || {
        let _ = now.try_set(now_ms());
    })));
    on_cleanup(move || {
        ticker.try_update_value(|t| *t = None);
    });

    // Pairing keeps polling in both modes: a phone approval signs in even
    // while someone is typing a password.
    let alive = Arc::new(AtomicBool::new(true));
    let renew = Arc::new(AtomicBool::new(false));
    spawn_local(pairing_loop(alive.clone(), pairing, pair_message, renew.clone(), expiry));
    {
        let alive = alive.clone();
        on_cleanup(move || alive.store(false, Ordering::Relaxed));
    }

    let show = move |m: Mode| {
        mode.set(m);
        error.set(None);
        match m {
            Mode::Password => focus::focus_later("input[name=username]"),
            Mode::Pair => focus::focus_later("[data-action=use-password]"),
        }
    };
    // Back from the password card returns to pairing, not to the exit prompt.
    let live = focus::screen_alive();
    let live_in = live.clone();
    let handler: focus::KeyHandler = std::rc::Rc::new(move |key| {
        if !live_in.load(Ordering::Relaxed) {
            return false;
        }
        if key == Key::Back && mode.get_untracked() == Mode::Password {
            show(Mode::Pair);
            return true;
        }
        false
    });
    focus::set_screen_handler(Some(handler));
    on_cleanup(move || live.store(false, Ordering::Relaxed));

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
            let _ = busy.try_set(false);
            match result {
                Ok(v) => match accept(&v) {
                    Ok(session) => session::set(Some(session)),
                    Err(e) => error.set(Some(e)),
                },
                // 401 wrong password, 403 admin refusal, 429 with the wait.
                Err(e) => error.set(Some(friendly_error(&e))),
            }
        });
    };

    // Time left on the code: text and a bar shrinking by scaleX.
    let left_ms = move || expiry.get().map(|(_, end)| (end - now.get()).max(0.0));
    let left_fraction = move || {
        expiry.get().map(|(start, end)| ((end - now.get()) / (end - start).max(1.0)).clamp(0.0, 1.0)).unwrap_or(0.0)
    };

    let renew_click = renew.clone();
    view! {
        <div class="tv-screen tv-login" data-screen="login">
            <div class="tv-blobs" aria-hidden="true">
                <div class="tv-blob tv-blob-a"></div>
                <div class="tv-blob tv-blob-b"></div>
            </div>
            <div class="tv-login-brand">"StreamHub"</div>

            <Show when=move || mode.get() == Mode::Pair>
                <div class="tv-login-stage">
                    <div
                        class="tv-pair"
                        data-focusable=""
                        data-action="pair"
                        data-focus-id="login-pair"
                        tabindex="-1"
                        // OK asks for a new code only when none is showing (start
                        // failed). A live code is never replaced by a press: someone
                        // may be scanning it, and the replaced code would then be
                        // approved on the phone while this screen waits on the new
                        // one forever. Expiry renews by itself.
                        on:click={
                            let renew_click = renew_click.clone();
                            move |_| {
                                if pairing.with_untracked(|p| p.is_none()) {
                                    renew_click.store(true, Ordering::Relaxed);
                                }
                            }
                        }
                    >
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
                        <div class="tv-pair-body">
                            <p class="tv-eyebrow">"用手機登入"</p>
                            <h1 class="tv-pair-title">"掃描 QR 碼登入"</h1>
                            <p class="tv-pair-step">"1. 用手機相機掃描左邊的 QR 碼"</p>
                            <p class="tv-pair-step">"2. 在手機上按「核准」"</p>
                            <p class="tv-pair-alt">
                                {move || {
                                    pairing
                                        .get()
                                        .map(|p| format!("或前往 {} 輸入", p.verification_url))
                                        .unwrap_or_else(|| "或在手機上輸入代碼".into())
                                }}
                            </p>
                            // Exactly the server's text; `nowrap` keeps the halves together.
                            <div class="tv-user-code" data-user-code="">
                                {move || pairing.get().map(|p| p.user_code).unwrap_or_default()}
                            </div>
                            <div class="tv-countdown" class:is-hidden=move || pairing.get().is_none()>
                                <div class="tv-countdown-track">
                                    <div
                                        class="tv-countdown-fill"
                                        style=move || format!("transform:scaleX({:.4})", left_fraction())
                                    ></div>
                                </div>
                                <span class="tv-countdown-text tv-num">
                                    {move || left_ms().map(|ms| format!("{} 後更新代碼", mmss(ms))).unwrap_or_default()}
                                </span>
                            </div>
                            {move || pair_message.get().filter(|_| pairing.get().is_some()).map(|m| view! { <p class="tv-error">{m}</p> })}
                        </div>
                    </div>
                    <button
                        class="tv-btn tv-login-switch"
                        data-focusable=""
                        data-autofocus=""
                        data-action="use-password"
                        data-focus-id="login-use-password"
                        on:click=move |_| show(Mode::Password)
                    >
                        "改用帳號密碼登入"
                    </button>
                </div>
            </Show>

            <Show when=move || mode.get() == Mode::Password>
                <div class="tv-login-stage">
                    <form
                        class="tv-login-form tv-login-card"
                        autocomplete="off"
                        on:submit=move |ev: leptos::ev::SubmitEvent| {
                            ev.prevent_default();
                            sign_in();
                        }
                    >
                        <h1 class="tv-login-title">"帳號密碼登入"</h1>
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
                                data-autofocus=""
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
                        <button
                            type="button"
                            class="tv-btn tv-btn-text"
                            data-focusable=""
                            data-action="use-pairing"
                            on:click=move |_| show(Mode::Pair)
                        >
                            "改用手機登入"
                        </button>
                    </form>
                </div>
            </Show>
        </div>
    }
}
