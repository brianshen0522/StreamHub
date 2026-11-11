//! OWNED BY THE PLAYER AGENT: the player screen, progress reporting, up-next,
//! recovery, and the cast receiver's business end.
//!
//! Shape: [`PlayerScreen`] renders signals in [`Ui`]; a [`Ctl`] (one at a time,
//! held in `ACTIVE`) owns everything non-`Send` — the shim binding, timers,
//! the source list and the recovery ladder. Every callback reaches it by id or
//! `Weak`, so nothing outlives the screen.
//!
//! QA hooks: root `[data-screen="player"]` with `data-position` (whole seconds,
//! ≤1 update/s), `data-duration`, `data-controls="visible|hidden"`,
//! `data-state` (shim state), `data-backend`, `data-source` (index);
//! overlays `[data-overlay="loading|up-next|error|sources|driver"]`; buttons
//! `[data-action="toggle|back10|fwd10|next-episode|sources|retry|back|source"]`.

mod avplay;
mod ladder;
mod media;

use std::cell::RefCell;
use std::rc::{Rc, Weak};

use gloo_timers::callback::{Interval, Timeout};
use gloo_timers::future::TimeoutFuture;
use leptos::prelude::*;
use serde_json::{json, Value};
use streamhub_core::models::{SeasonRef, WatchProgress};
use streamhub_core::resume::{self, UpNext};
use wasm_bindgen::prelude::*;

use crate::api;
use crate::focus;
use crate::keys::Key;
use crate::nav::{self, PlayTarget, Route};
use crate::realtime::{self, Command};
use ladder::{Ladder, Step, Tier};

const SEEK_STEP_MS: f64 = 10_000.0;
const MEDIA_SEEK_STEP_MS: f64 = 30_000.0;
const SEEK_DEBOUNCE_MS: u32 = 400;
const CONTROLS_TIMEOUT_MS: u32 = 4_000;
const PROGRESS_EVERY_MS: f64 = 15_000.0;
/// How long to wait for a saved source before taking whatever arrived first.
const PREFERENCE_WAIT_MS: u32 = 2_500;
const STALL_MS: f64 = 45_000.0;
const FORGIVE_MS: f64 = 60_000.0;
/// Holding Left/Right: one scrub step per tick, growing with the hold.
const SCRUB_TICK_MS: u32 = 120;
/// No keyup and no repeat for this long means the release was lost.
const SCRUB_HOLD_GRACE_MS: f64 = 450.0;
/// OK held this long opens the source picker instead of toggling.
const OK_LONG_PRESS_MS: u32 = 700;
const FLASH_MS: u32 = 650;
/// Episode strip tiles: width + gap, and the visible width inside the safe area.
const STRIP_TILE_PX: f64 = 284.0;
const STRIP_VIEW_PX: f64 = 1728.0;

/// Scrub acceleration: 10 s steps, 30 s after a second held, 60 s after three.
fn scrub_step_ms(held_ms: f64) -> f64 {
    if held_ms < 1_000.0 {
        10_000.0
    } else if held_ms < 3_000.0 {
        30_000.0
    } else {
        60_000.0
    }
}

/// Keep the focused tile two from the left edge until the rail runs out.
fn strip_offset(idx: usize, count: usize) -> f64 {
    let max = (count as f64 * STRIP_TILE_PX - 24.0 - STRIP_VIEW_PX).max(0.0);
    ((idx as f64 - 2.0) * STRIP_TILE_PX).clamp(0.0, max)
}

#[derive(Clone, Copy)]
struct Ui {
    status: RwSignal<String>,
    backend: RwSignal<String>,
    buffering: RwSignal<bool>,
    pos_s: RwSignal<u64>,
    dur_s: RwSignal<u64>,
    /// The button row (focusable). `osd` is the info bar alone, shown on seek.
    controls: RwSignal<bool>,
    osd: RwSignal<bool>,
    seek_preview: RwSignal<Option<u64>>,
    notice: RwSignal<Option<String>>,
    fatal: RwSignal<Option<String>>,
    /// (caption, seconds left)
    up_next: RwSignal<Option<(String, u64)>>,
    has_next: RwSignal<bool>,
    picker: RwSignal<bool>,
    sources: RwSignal<Vec<SourceRow>>,
    current_source: RwSignal<Option<usize>>,
    source_label: RwSignal<String>,
    driver: RwSignal<Option<String>>,
    /// Holding Left/Right (data-scrub="1").
    scrub: RwSignal<bool>,
    /// The OK feedback glyph: (now playing, nonce — a new nonce restarts it).
    flash: RwSignal<Option<(bool, u64)>>,
    /// The episode strip, with the focused index.
    strip: RwSignal<Option<usize>>,
    /// Per episode: (watched fraction, completed), once /me/progress answered.
    strip_info: RwSignal<Vec<(f64, bool)>>,
}

impl Ui {
    fn new(start_s: u64) -> Self {
        Ui {
            status: RwSignal::new("idle".into()),
            backend: RwSignal::new(String::new()),
            buffering: RwSignal::new(true),
            pos_s: RwSignal::new(start_s),
            dur_s: RwSignal::new(0),
            controls: RwSignal::new(false),
            osd: RwSignal::new(true),
            seek_preview: RwSignal::new(None),
            notice: RwSignal::new(Some("尋找片源中…".into())),
            fatal: RwSignal::new(None),
            up_next: RwSignal::new(None),
            has_next: RwSignal::new(false),
            picker: RwSignal::new(false),
            sources: RwSignal::new(Vec::new()),
            current_source: RwSignal::new(None),
            source_label: RwSignal::new(String::new()),
            driver: RwSignal::new(None),
            scrub: RwSignal::new(false),
            flash: RwSignal::new(None),
            strip: RwSignal::new(None),
            strip_info: RwSignal::new(Vec::new()),
        }
    }
}

#[derive(Default)]
struct Inner {
    sources: Vec<Value>,
    ladder: Option<Ladder>,
    started: bool,
    stream_done: bool,
    waiting_more: bool,
    probing: bool,
    offline: bool,
    preferred: Option<String>,
    pref_timer: Option<Timeout>,
    abort: Option<web_sys::AbortController>,
    pos_ms: f64,
    dur_ms: f64,
    last_good_ms: f64,
    last_fault_at: f64,
    last_fault: String,
    intent_play: bool,
    ended: bool,
    up_next_dismissed: bool,
    next: Option<PlayTarget>,
    prev: Option<PlayTarget>,
    pending_seek: Option<f64>,
    seek_timer: Option<Timeout>,
    hide_timer: Option<Timeout>,
    ticker: Option<Interval>,
    last_progress_at: f64,
    stall_since: f64,
    stall_anchor: f64,
    cmd_id: u64,
    on_visibility: Option<Closure<dyn FnMut()>>,
    on_keys: Option<(Closure<dyn FnMut(web_sys::KeyboardEvent)>, Closure<dyn FnMut(web_sys::KeyboardEvent)>)>,
    /// A seek asked for before the player was ready; applied once it is.
    deferred_seek: Option<f64>,
    /// When the current Left/Right press went down (acceleration clock).
    key_down_at: f64,
    scrub: Option<Scrub>,
    scrub_timer: Option<Interval>,
    /// OK pressed and not yet released: tap vs long press.
    ok_down: bool,
    ok_repeat: bool,
    ok_long: bool,
    ok_timer: Option<Timeout>,
    flash_seq: u64,
    flash_timer: Option<Timeout>,
    strip_rows: Vec<Option<WatchProgress>>,
    /// Deferred focus after a layer renders, and the cast echo: one handle
    /// each, replaced on reuse — never a forgotten (leaked) timer.
    focus_timer: Option<Timeout>,
    echo_timer: Option<Timeout>,
}

struct Scrub {
    dir: f64,
    since: f64,
    held_until: f64,
}

struct Ctl {
    id: u64,
    target: PlayTarget,
    ui: Ui,
    me: Weak<Ctl>,
    player: RefCell<Option<avplay::Player>>,
    s: RefCell<Inner>,
}

thread_local! {
    static ACTIVE: RefCell<Option<Rc<Ctl>>> = const { RefCell::new(None) };
    static SEQ: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
    /// `repeat` of the keydown being dispatched, recorded by a capture-phase
    /// listener that runs before focus.rs hands the key to the screen.
    static KEY_REPEAT: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

fn active(id: u64) -> Option<Rc<Ctl>> {
    ACTIVE.with(|a| a.borrow().as_ref().filter(|c| c.id == id).cloned())
}

/// Monotonic: progress cadence, stall detection and forgiveness all subtract
/// two readings, which an NTP jump of the wall clock would break.
fn now() -> f64 {
    realtime::mono_ms()
}


/// `vp-playing` on <body> and <html>: the page must be transparent over the
/// video plane, and Chrome 85 (the M7) has no `:has()` to reach <html> from
/// the body's class.
fn set_body_playing(on: bool) {
    // Interface sounds stay silent (and their AudioContext suspended) for as
    // long as the player is up: nothing over the film, nothing competing with AVPlay.
    crate::sfx::set_player(on);
    let Some(doc) = web_sys::window().and_then(|w| w.document()) else { return };
    let targets = [doc.document_element(), doc.body().map(Into::into)];
    for el in targets.into_iter().flatten() {
        let current = el.class_name();
        let mut parts: Vec<&str> = current.split_whitespace().filter(|c| *c != "vp-playing").collect();
        if on {
            parts.push("vp-playing");
        }
        el.set_class_name(&parts.join(" "));
    }
}

fn neighbour(target: &PlayTarget, label: String) -> PlayTarget {
    PlayTarget {
        episode_label: label,
        source: None,
        start_seconds: Some(0.0),
        ..target.clone()
    }
}

fn teardown(id: u64) {
    let Some(ctl) = ACTIVE.with(|a| {
        let mut a = a.borrow_mut();
        if a.as_ref().map(|c| c.id) == Some(id) { a.take() } else { None }
    }) else {
        return;
    };
    let (started, ended, cmd_id, abort, vis) = {
        let mut s = ctl.s.borrow_mut();
        (s.started, s.ended, s.cmd_id, s.abort.take(), s.on_visibility.take())
    };
    let keys = ctl.s.borrow_mut().on_keys.take();
    if let (Some((down, up)), Some(w)) = (keys, web_sys::window()) {
        let _ = w.remove_event_listener_with_callback_and_bool("keydown", down.as_ref().unchecked_ref(), true);
        let _ = w.remove_event_listener_with_callback_and_bool("keyup", up.as_ref().unchecked_ref(), true);
    }
    // Leaving is a pause, as far as progress is concerned.
    if started && !ended {
        ctl.report("pause");
    }
    realtime::publish_state(None);
    realtime::clear_command_handler(cmd_id);
    focus::set_screen_handler(None);
    if let Some(a) = abort {
        a.abort();
    }
    if let (Some(v), Some(doc)) = (vis, web_sys::window().and_then(|w| w.document())) {
        let _ = doc.remove_event_listener_with_callback("visibilitychange", v.as_ref().unchecked_ref());
    }
    // Detaches the listener, then closes the shim.
    ctl.player.borrow_mut().take();
    set_body_playing(false);
}

impl Ctl {
    fn mount(target: PlayTarget) -> Rc<Ctl> {
        // A replace (next episode, cast play) can build the new screen before
        // the old one's cleanup runs; end the old one first, so it cannot
        // clear handlers this one installs.
        if let Some(old) = ACTIVE.with(|a| a.borrow().as_ref().map(|c| c.id)) {
            teardown(old);
        }
        let id = SEQ.with(|s| {
            s.set(s.get() + 1);
            s.get()
        });
        let start_ms = target.start_seconds.unwrap_or(0.0).max(0.0) * 1000.0;
        let ui = Ui::new((start_ms / 1000.0) as u64);
        let ctl = Rc::new_cyclic(|me| Ctl {
            id,
            target,
            ui,
            me: me.clone(),
            player: RefCell::new(None),
            s: RefCell::new(Inner {
                pos_ms: start_ms,
                last_good_ms: start_ms,
                intent_play: true,
                last_progress_at: now(),
                ..Default::default()
            }),
        });
        ACTIVE.with(|a| *a.borrow_mut() = Some(ctl.clone()));

        let weak = ctl.me.clone();
        let on_event: Rc<dyn Fn(avplay::Event)> = Rc::new(move |e| {
            if let Some(c) = weak.upgrade() {
                c.on_event(e);
            }
        });
        *ctl.player.borrow_mut() = Some(avplay::Player::attach(on_event));

        // Neighbours, for up-next, the 下一集 button and the cast remote.
        {
            let t = &ctl.target;
            let ep = Some(t.episode_label.as_str());
            let next = match resume::up_next(&t.episodes, ep, &[], Some(&t.season_url)) {
                Some(UpNext::Episode(l)) => Some(neighbour(t, l)),
                _ => None,
            };
            let prev = resume::previous_episode(&t.episodes, ep).map(|l| neighbour(t, l));
            ui.has_next.set(next.is_some());
            let mut s = ctl.s.borrow_mut();
            s.next = next;
            s.prev = prev;
        }
        let at_season_end = !ctl.target.episodes.is_empty()
            && ctl.target.episodes.last() == Some(&ctl.target.episode_label);
        if at_season_end {
            ctl.resolve_next_season();
        }

        let weak = ctl.me.clone();
        let ticker = Interval::new(1_000, move || {
            if let Some(c) = weak.upgrade() {
                c.tick();
            }
        });
        let weak = ctl.me.clone();
        let cmd_id = realtime::set_command_handler(Rc::new(move |cmd| {
            if let Some(c) = weak.upgrade() {
                c.on_command(cmd);
            }
        }));
        let weak = ctl.me.clone();
        let on_visibility = Closure::<dyn FnMut()>::new(move || {
            let hidden = web_sys::window().and_then(|w| w.document()).is_some_and(|d| d.hidden());
            if let (true, Some(c)) = (hidden, weak.upgrade()) {
                c.report("pause");
            }
        });
        if let Some(doc) = web_sys::window().and_then(|w| w.document()) {
            let _ = doc.add_event_listener_with_callback("visibilitychange", on_visibility.as_ref().unchecked_ref());
        }
        {
            let mut s = ctl.s.borrow_mut();
            s.ticker = Some(ticker);
            s.cmd_id = cmd_id;
            s.on_visibility = Some(on_visibility);
        }
        focus::set_screen_handler(Some(Rc::new(move |key| active(id).is_some_and(|c| c.on_key(key)))));
        // focus.rs only forwards keydown, and without `repeat`. Holds need
        // both: a capture listener notes `repeat` before focus.rs dispatches,
        // and keyup ends a scrub or decides tap vs long press on OK.
        let on_down = Closure::<dyn FnMut(web_sys::KeyboardEvent)>::new(|e: web_sys::KeyboardEvent| {
            KEY_REPEAT.with(|r| r.set(e.repeat()));
        });
        let on_up = Closure::<dyn FnMut(web_sys::KeyboardEvent)>::new(move |e: web_sys::KeyboardEvent| {
            if let Some(c) = active(id) {
                c.on_key_up(e.key_code());
            }
        });
        if let Some(w) = web_sys::window() {
            let _ = w.add_event_listener_with_callback_and_bool("keydown", on_down.as_ref().unchecked_ref(), true);
            let _ = w.add_event_listener_with_callback_and_bool("keyup", on_up.as_ref().unchecked_ref(), true);
        }
        ctl.s.borrow_mut().on_keys = Some((on_down, on_up));
        set_body_playing(true);

        if let Some(source) = ctl.target.source.clone() {
            ctl.s.borrow_mut().sources.push(source);
            ctl.refresh_source_labels();
            ctl.begin(0);
        }
        // Always listed, even with a source in hand: the others are the
        // ladder's next rung and the 片源 menu.
        ctl.load_sources();
        ctl.show_osd();
        ctl.publish();
        ctl
    }

    fn player<R>(&self, f: impl FnOnce(&avplay::Player) -> R) -> Option<R> {
        self.player.borrow().as_ref().map(f)
    }

    // ── sources ────────────────────────────────────────────────────────────

    fn load_sources(&self) {
        let abort = web_sys::AbortController::new().ok();
        let Some(signal) = abort.as_ref().map(|a| a.signal()) else { return };
        {
            let mut s = self.s.borrow_mut();
            if let Some(old) = s.abort.replace(abort.unwrap()) {
                old.abort();
            }
            s.stream_done = false;
        }
        let weak = self.me.clone();
        let target = self.target.clone();
        wasm_bindgen_futures::spawn_local(async move {
            let preferred = media::preferred_label(&target).await;
            match weak.upgrade() {
                Some(c) => c.s.borrow_mut().preferred = preferred.clone(),
                None => return,
            }
            let w = weak.clone();
            let result = media::stream_sources(&target, preferred.as_deref(), &signal, move |line| {
                if let Some(c) = w.upgrade() {
                    c.on_source(line);
                }
            })
            .await;
            if signal.aborted() {
                return;
            }
            if let Some(c) = weak.upgrade() {
                c.on_sources_done(result);
            }
        });
    }

    fn refresh_source_labels(&self) {
        let labels: Vec<SourceRow> = self
            .s
            .borrow()
            .sources
            .iter()
            .enumerate()
            .map(|(i, v)| SourceRow::from_line(i, v))
            .collect();
        self.ui.sources.set(labels);
    }

    fn on_source(&self, line: Value) {
        let Some(direct) = media::direct_url(&line).map(str::to_string) else { return };
        let (idx, started, waiting, preferred, label) = {
            let mut s = self.s.borrow_mut();
            if let Some(known) = s.sources.iter_mut().find(|v| media::direct_url(v) == Some(direct.as_str())) {
                // Already listed — typically the source a cast or Detail
                // started with, which carries no label, runtime or ad count.
                // Fill those in from the probe's line; keep what is there.
                let changed = merge_missing(known, &line);
                drop(s);
                if changed {
                    self.refresh_source_labels();
                }
                return;
            }
            let label = media::str_field(&line, "sourceLabel").to_string();
            s.sources.push(line);
            (s.sources.len() - 1, s.started, s.waiting_more, s.preferred.clone(), label)
        };
        self.refresh_source_labels();
        if !started {
            match preferred {
                None => self.begin(idx),
                Some(p) if p == label => self.begin(idx),
                Some(_) => {
                    let pending = self.s.borrow().pref_timer.is_some();
                    if !pending {
                        let weak = self.me.clone();
                        let timer = Timeout::new(PREFERENCE_WAIT_MS, move || {
                            if let Some(c) = weak.upgrade() {
                                if !c.s.borrow().started {
                                    c.begin(0);
                                }
                            }
                        });
                        self.s.borrow_mut().pref_timer = Some(timer);
                    }
                }
            }
        } else if waiting {
            let pos = {
                let mut s = self.s.borrow_mut();
                s.waiting_more = false;
                if let Some(l) = s.ladder.as_mut() {
                    l.select(idx);
                }
                s.last_good_ms
            };
            self.ui.notice.set(Some("改用其他片源…".into()));
            self.start_source(idx, Tier::Manifest, pos);
        }
    }

    fn on_sources_done(&self, result: Result<(), api::ApiError>) {
        let (started, waiting, count, preferred_idx) = {
            let mut s = self.s.borrow_mut();
            s.stream_done = true;
            let preferred_idx = s.preferred.as_ref().and_then(|p| {
                s.sources.iter().position(|v| media::str_field(v, "sourceLabel") == p)
            });
            (s.started, s.waiting_more, s.sources.len(), preferred_idx)
        };
        if let Err(e) = &result {
            if !started && e.is_offline() {
                self.wait_for_network();
                return;
            }
        }
        if !started {
            if count > 0 {
                self.begin(preferred_idx.unwrap_or(0));
            } else {
                let msg = match result {
                    Err(e) => e.message,
                    Ok(()) => "找不到可播放的片源".into(),
                };
                self.give_up(msg);
            }
        } else if waiting {
            let msg = {
                let mut s = self.s.borrow_mut();
                s.waiting_more = false;
                s.last_fault.clone()
            };
            self.give_up(msg);
        }
    }

    /// First playback of a source chosen automatically.
    fn begin(&self, idx: usize) {
        let start = {
            let mut s = self.s.borrow_mut();
            s.pref_timer = None;
            s.ladder = Some(Ladder::new(idx));
            s.pos_ms
        };
        self.start_source(idx, Tier::Manifest, start);
    }

    fn start_source(&self, idx: usize, tier: Tier, pos_ms: f64) {
        let source = self.s.borrow().sources.get(idx).cloned();
        let Some(source) = source else { return };
        let url = match tier {
            Tier::Manifest => media::manifest_url(&source),
            Tier::Relay => media::relay_url(&source),
        };
        let Some(url) = url else {
            self.give_up("片源網址無效".into());
            return;
        };
        let label = media::str_field(&source, "sourceLabel").to_string();
        {
            let mut s = self.s.borrow_mut();
            s.started = true;
            s.ended = false;
            s.intent_play = true;
            s.stall_since = 0.0;
            s.pos_ms = pos_ms;
        }
        self.ui.fatal.set(None);
        self.ui.current_source.set(Some(idx));
        self.ui.source_label.set(label);
        self.ui.buffering.set(true);
        self.player(|p| p.open(&url, pos_ms));
        if let Some(b) = self.player(|p| p.backend()) {
            self.ui.backend.set(b);
        }
    }

    // ── recovery ───────────────────────────────────────────────────────────

    fn on_fault(&self, msg: String) {
        {
            let mut s = self.s.borrow_mut();
            if s.probing || s.offline || self.ui.fatal.get_untracked().is_some() {
                return;
            }
            s.probing = true;
            s.last_fault = msg.clone();
        }
        self.ui.notice.set(Some("重新連線中…".into()));
        let weak = self.me.clone();
        wasm_bindgen_futures::spawn_local(async move {
            let reachable = media::server_reachable().await;
            let Some(c) = weak.upgrade() else { return };
            c.s.borrow_mut().probing = false;
            if reachable {
                c.climb(msg);
            } else {
                c.wait_for_network();
            }
        });
    }

    /// The network, not the source: hold the ladder where it is and resume
    /// the same rung once the server answers again.
    fn wait_for_network(&self) {
        self.s.borrow_mut().offline = true;
        self.ui.notice.set(Some("網路連線中斷，恢復後自動繼續…".into()));
        let weak = self.me.clone();
        wasm_bindgen_futures::spawn_local(async move {
            let mut delay = 2_000u32;
            loop {
                TimeoutFuture::new(delay).await;
                if weak.upgrade().is_none() {
                    return;
                }
                if media::server_reachable().await {
                    break;
                }
                delay = (delay * 2).min(30_000);
            }
            let Some(c) = weak.upgrade() else { return };
            let (started, idx, tier, pos) = {
                let mut s = c.s.borrow_mut();
                s.offline = false;
                let (idx, tier) = s.ladder.as_ref().map(|l| (l.source, l.tier)).unwrap_or((0, Tier::Manifest));
                (s.started, idx, tier, s.last_good_ms)
            };
            c.ui.notice.set(Some("重新連線中…".into()));
            if started {
                c.start_source(idx, tier, pos);
            } else {
                c.load_sources();
            }
        });
    }

    fn climb(&self, msg: String) {
        let (step, idx, pos, done) = {
            let mut s = self.s.borrow_mut();
            s.last_fault_at = now();
            let known = s.sources.len();
            let Some(ladder) = s.ladder.as_mut() else { return };
            let step = ladder.next(known);
            (step, ladder.source, s.last_good_ms, s.stream_done)
        };
        match step {
            Step::Retry => {
                let tier = self.s.borrow().ladder.as_ref().map(|l| l.tier).unwrap_or(Tier::Manifest);
                self.start_source(idx, tier, pos);
            }
            Step::SwitchToRelay => {
                self.ui.notice.set(Some("改用伺服器轉送…".into()));
                self.start_source(idx, Tier::Relay, pos);
            }
            Step::NextSource(i) => {
                self.ui.notice.set(Some("改用其他片源…".into()));
                self.start_source(i, Tier::Manifest, pos);
            }
            Step::Exhausted if !done => {
                self.s.borrow_mut().waiting_more = true;
                self.ui.notice.set(Some("尋找其他片源…".into()));
            }
            Step::Exhausted => self.give_up(msg),
        }
    }

    fn give_up(&self, msg: String) {
        web_sys::console::warn_1(&format!("player gave up: {msg}").into());
        self.s.borrow_mut().intent_play = false;
        self.ui.fatal.set(Some(msg));
        self.ui.notice.set(None);
        self.ui.buffering.set(false);
        self.ui.controls.set(false);
        self.ui.picker.set(false);
        self.ui.up_next.set(None);
        self.ui.osd.set(false);
        self.publish();
        self.focus_later(30, "[data-screen=\"player\"] [data-action=\"retry\"]", None);
    }

    fn retry(&self) {
        self.ui.fatal.set(None);
        self.ui.notice.set(Some("重新連線中…".into()));
        let (count, pos) = {
            let mut s = self.s.borrow_mut();
            if let Some(l) = s.ladder.as_mut() {
                l.select(0);
            }
            (s.sources.len(), s.last_good_ms)
        };
        if count == 0 {
            self.s.borrow_mut().started = false;
            self.load_sources();
        } else {
            self.start_source(0, Tier::Manifest, pos);
        }
    }

    // ── player events ──────────────────────────────────────────────────────

    fn on_event(&self, event: avplay::Event) {
        match event {
            avplay::Event::State(state) => {
                self.ui.status.set(state.clone());
                match state.as_str() {
                    "playing" => {
                        if !self.ui.buffering.get_untracked() {
                            self.ui.notice.set(None);
                        }
                        // Keys pressed while loading are not lost: a pause or
                        // a seek asked for then is applied now.
                        let (paused_meanwhile, seek) = {
                            let mut s = self.s.borrow_mut();
                            (!s.intent_play, s.deferred_seek.take())
                        };
                        if let Some(ms) = seek {
                            self.player(|p| p.seek(ms));
                        }
                        if paused_meanwhile {
                            self.player(|p| p.pause());
                        }
                        self.publish();
                    }
                    "paused" => {
                        // The shim honours a pause asked for while loading by
                        // coming up paused; a seek asked for then lands now.
                        let seek = self.s.borrow_mut().deferred_seek.take();
                        if let Some(ms) = seek {
                            self.player(|p| p.seek(ms));
                        }
                        self.s.borrow_mut().intent_play = false;
                        self.report("pause");
                        self.show_osd();
                        self.publish();
                    }
                    _ => {}
                }
            }
            avplay::Event::Time(ms) => self.on_time(ms),
            avplay::Event::Buffering(b) => {
                self.ui.buffering.set(b);
                if !b && self.ui.status.get_untracked() == "playing" && !self.s.borrow().offline {
                    self.ui.notice.set(None);
                }
                self.publish();
            }
            avplay::Event::Ended => self.on_ended(),
            avplay::Event::Error(msg) => self.on_fault(msg),
        }
    }

    fn on_time(&self, ms: f64) {
        let dur = self.player(|p| p.duration_ms()).unwrap_or(0.0);
        {
            let mut s = self.s.borrow_mut();
            s.pos_ms = ms;
            if ms > 0.0 {
                s.last_good_ms = ms;
            }
            if dur > 0.0 {
                s.dur_ms = dur;
            }
        }
        let sec = (ms / 1000.0).floor().max(0.0) as u64;
        if self.ui.pos_s.get_untracked() != sec {
            self.ui.pos_s.set(sec);
        }
        let dur_s = (dur / 1000.0).floor().max(0.0) as u64;
        if dur_s > 0 && self.ui.dur_s.get_untracked() != dur_s {
            self.ui.dur_s.set(dur_s);
        }
        self.check_up_next();
    }

    fn check_up_next(&self) {
        let (offer, caption, left) = {
            let mut s = self.s.borrow_mut();
            if s.ended {
                return;
            }
            let Some(next_label) = s.next.as_ref().map(|n| n.episode_label.clone()) else {
                drop(s);
                if self.ui.up_next.get_untracked().is_some() {
                    self.ui.up_next.set(None);
                }
                return;
            };
            let (pos, dur) = (s.pos_ms / 1000.0, s.dur_ms / 1000.0);
            let offer = resume::should_offer_up_next(dur, pos);
            if !offer {
                s.up_next_dismissed = false;
            }
            let caption = format!("下一集 {}", media::episode_caption(&next_label));
            (offer && !s.up_next_dismissed, caption, (dur - pos).max(0.0).ceil() as u64)
        };
        let want = offer.then_some((caption, left));
        if self.ui.up_next.get_untracked() != want && self.ui.fatal.get_untracked().is_none() {
            self.ui.up_next.set(want);
        }
    }

    fn on_ended(&self) {
        let (next, dismissed) = {
            let mut s = self.s.borrow_mut();
            if s.ended {
                return;
            }
            s.ended = true;
            s.intent_play = false;
            let end = s.dur_ms.max(s.pos_ms);
            s.pos_ms = end;
            (s.next.clone(), s.up_next_dismissed)
        };
        // Finished means finished: the end position is what marks it complete.
        self.report("ended");
        self.ui.up_next.set(None);
        self.publish();
        match next {
            Some(t) if !dismissed => nav::replace(Route::Player(t)),
            // Nothing to ask about: a frozen last frame is a worse place to
            // leave someone than the title page.
            _ => self.leave(),
        }
    }

    // ── transport ──────────────────────────────────────────────────────────

    fn is_playing_intent(&self) -> bool {
        let status = self.ui.status.get_untracked();
        status == "playing" || (self.s.borrow().intent_play && status == "loading")
    }

    fn toggle(&self) {
        if self.ui.fatal.get_untracked().is_some() || self.s.borrow().ended {
            return;
        }
        if self.is_playing_intent() {
            self.pause();
        } else {
            self.play();
        }
    }

    fn play(&self) {
        self.s.borrow_mut().intent_play = true;
        self.player(|p| p.play());
    }

    fn pause(&self) {
        self.s.borrow_mut().intent_play = false;
        self.player(|p| p.pause());
    }

    /// Move the pending seek target; nothing is sent to the player.
    fn advance(&self, delta_ms: f64) -> bool {
        if self.ui.fatal.get_untracked().is_some() || !self.s.borrow().started {
            return false;
        }
        let target = {
            let mut s = self.s.borrow_mut();
            let base = s.pending_seek.or(s.deferred_seek).unwrap_or(s.pos_ms);
            let mut t = (base + delta_ms).max(0.0);
            if s.dur_ms > 0.0 {
                t = t.min((s.dur_ms - 1_000.0).max(0.0));
            }
            s.pending_seek = Some(t);
            t
        };
        self.ui.seek_preview.set(Some((target / 1000.0) as u64));
        self.show_osd();
        true
    }

    /// Left/Right taps: presses accumulate into one seek, sent once they stop.
    fn nudge(&self, delta_ms: f64) {
        if !self.advance(delta_ms) {
            return;
        }
        let weak = self.me.clone();
        let timer = Timeout::new(SEEK_DEBOUNCE_MS, move || {
            if let Some(c) = weak.upgrade() {
                let t = c.s.borrow_mut().pending_seek.take();
                if let Some(t) = t {
                    c.seek_to(t);
                }
            }
        });
        self.s.borrow_mut().seek_timer = Some(timer);
    }

    // ── hold-to-scrub ──────────────────────────────────────────────────────

    /// A repeat keydown of Left/Right: enter scrub mode, or keep it alive.
    fn scrub_hold(&self, dir: f64) {
        let t = now();
        let starting = {
            let mut s = self.s.borrow_mut();
            let since = if s.key_down_at > 0.0 && t - s.key_down_at < 2_000.0 { s.key_down_at } else { t };
            match s.scrub.as_mut() {
                Some(sc) => {
                    sc.held_until = t + SCRUB_HOLD_GRACE_MS;
                    if sc.dir != dir {
                        sc.dir = dir;
                        sc.since = t;
                    }
                    false
                }
                None => {
                    s.scrub = Some(Scrub { dir, since, held_until: t + SCRUB_HOLD_GRACE_MS });
                    // The tap's debounced seek would fire mid-hold.
                    s.seek_timer = None;
                    true
                }
            }
        };
        if !starting {
            return;
        }
        if self.s.borrow().pending_seek.is_none() && !self.advance(0.0) {
            self.s.borrow_mut().scrub = None;
            return;
        }
        self.ui.scrub.set(true);
        let weak = self.me.clone();
        let timer = Interval::new(SCRUB_TICK_MS, move || {
            if let Some(c) = weak.upgrade() {
                c.scrub_tick();
            }
        });
        self.s.borrow_mut().scrub_timer = Some(timer);
    }

    fn scrub_tick(&self) {
        let t = now();
        let step = {
            let s = self.s.borrow();
            let Some(sc) = s.scrub.as_ref() else { return };
            if t > sc.held_until {
                None
            } else {
                Some(sc.dir * scrub_step_ms(t - sc.since))
            }
        };
        match step {
            Some(delta) => {
                self.advance(delta);
            }
            // Neither a repeat nor a keyup arrived: the release was lost.
            None => self.commit_scrub(),
        }
    }

    /// Release (or OK): one seek to wherever the target got to.
    fn commit_scrub(&self) {
        let target = {
            let mut s = self.s.borrow_mut();
            s.scrub = None;
            s.scrub_timer = None;
            s.seek_timer = None;
            s.pending_seek.take()
        };
        self.ui.scrub.set(false);
        match target {
            Some(t) => self.seek_to(t),
            None => self.ui.seek_preview.set(None),
        }
    }

    /// Back during a scrub or a tap burst: forget the target, seek nowhere.
    fn cancel_seek(&self) {
        {
            let mut s = self.s.borrow_mut();
            s.scrub = None;
            s.scrub_timer = None;
            s.seek_timer = None;
            s.pending_seek = None;
        }
        self.ui.scrub.set(false);
        self.ui.seek_preview.set(None);
        self.show_osd();
    }

    fn on_key_up(&self, code: u32) {
        match code {
            37 | 39 => {
                if self.s.borrow().scrub.is_some() {
                    self.commit_scrub();
                }
            }
            13 => self.ok_up(),
            _ => {}
        }
    }

    // ── OK: tap toggles, long press opens the sources ──────────────────────

    fn ok_down(&self, repeat: bool) {
        if repeat {
            self.s.borrow_mut().ok_repeat = true;
            return;
        }
        if self.s.borrow().ok_down {
            return;
        }
        let weak = self.me.clone();
        let timer = Timeout::new(OK_LONG_PRESS_MS, move || {
            let Some(c) = weak.upgrade() else { return };
            let held = {
                let mut s = c.s.borrow_mut();
                s.ok_timer = None;
                if !s.ok_down {
                    return;
                }
                s.ok_repeat
            };
            if held {
                c.s.borrow_mut().ok_long = true;
                c.open_picker();
            } else {
                // No repeats and no keyup: this platform lost the release.
                // A press that never repeated was a tap.
                c.s.borrow_mut().ok_down = false;
                c.ok_tap();
            }
        });
        let mut s = self.s.borrow_mut();
        s.ok_down = true;
        s.ok_repeat = false;
        s.ok_long = false;
        s.ok_timer = Some(timer);
    }

    fn ok_up(&self) {
        let tap = {
            let mut s = self.s.borrow_mut();
            if !s.ok_down {
                return;
            }
            s.ok_down = false;
            s.ok_timer = None;
            !std::mem::take(&mut s.ok_long)
        };
        if tap {
            self.ok_tap();
        }
    }

    fn ok_tap(&self) {
        if self.ui.fatal.get_untracked().is_some() || self.ui.picker.get_untracked() {
            return;
        }
        self.toggle_with_feedback();
    }

    /// Toggle, raise the bar, and pop a play/pause glyph so the press is seen.
    fn toggle_with_feedback(&self) {
        if self.ui.fatal.get_untracked().is_some() || self.s.borrow().ended {
            return;
        }
        let pausing = self.is_playing_intent();
        self.toggle();
        self.flash(!pausing);
        self.show_osd();
    }

    fn flash(&self, playing: bool) {
        let seq = {
            let mut s = self.s.borrow_mut();
            s.flash_seq += 1;
            s.flash_seq
        };
        self.ui.flash.set(Some((playing, seq)));
        let weak = self.me.clone();
        let timer = Timeout::new(FLASH_MS, move || {
            if let Some(c) = weak.upgrade() {
                if c.ui.flash.get_untracked().map(|f| f.1) == Some(seq) {
                    c.ui.flash.set(None);
                }
            }
        });
        self.s.borrow_mut().flash_timer = Some(timer);
    }

    // ── episode strip ──────────────────────────────────────────────────────

    fn current_index(&self) -> usize {
        self.target.episodes.iter().position(|e| *e == self.target.episode_label).unwrap_or(0)
    }

    fn open_strip(&self) {
        let n = self.target.episodes.len();
        if n == 0 {
            self.show_controls();
            return;
        }
        self.ui.controls.set(false);
        self.ui.osd.set(false);
        self.s.borrow_mut().hide_timer = None;
        self.ui.strip.set(Some(self.current_index()));
        let weak = self.me.clone();
        let t = self.target.clone();
        wasm_bindgen_futures::spawn_local(async move {
            let path = format!(
                "/me/progress{}",
                api::query(&[("providerKey", &t.provider), ("itemUrl", &t.item_url)])
            );
            let Ok(v) = api::get(&path).await else { return };
            let rows: Vec<WatchProgress> = v
                .get("progress")
                .cloned()
                .and_then(|p| serde_json::from_value(p).ok())
                .unwrap_or_default();
            let map = resume::progress_map(&rows);
            let season = Some(t.season_url.as_str());
            let per: Vec<Option<WatchProgress>> = t
                .episodes
                .iter()
                .map(|e| map.get(&resume::progress_key(season, Some(e))).cloned())
                .collect();
            let Some(c) = weak.upgrade() else { return };
            let info = per
                .iter()
                .map(|p| match p {
                    Some(p) => ((p.progress_percent / 100.0).clamp(0.0, 1.0), p.is_completed),
                    None => (0.0, false),
                })
                .collect();
            c.s.borrow_mut().strip_rows = per;
            c.ui.strip_info.set(info);
        });
    }

    fn close_strip(&self) {
        self.ui.strip.set(None);
        self.show_osd();
    }

    fn strip_key(&self, key: Key, repeat: bool) -> bool {
        let Some(idx) = self.ui.strip.get_untracked() else { return false };
        let last = self.target.episodes.len().saturating_sub(1);
        match key {
            Key::Left => self.ui.strip.set(Some(idx.saturating_sub(1))),
            Key::Right => self.ui.strip.set(Some((idx + 1).min(last))),
            Key::Enter if !repeat => self.choose_episode(idx),
            Key::Back | Key::Up => self.close_strip(),
            Key::Down | Key::Enter => {}
            _ => return false,
        }
        true
    }

    /// Play another episode of the season: from its resume point if it has
    /// one, from the start otherwise. The current one just closes the strip.
    fn choose_episode(&self, idx: usize) {
        if idx == self.current_index() {
            self.close_strip();
            return;
        }
        let Some(label) = self.target.episodes.get(idx).cloned() else { return };
        let start = self
            .s
            .borrow()
            .strip_rows
            .get(idx)
            .and_then(|p| p.as_ref())
            .map(|p| resume::resume_position_seconds(Some(p)))
            .unwrap_or(0.0);
        let mut next = neighbour(&self.target, label);
        next.start_seconds = Some(start);
        nav::replace(Route::Player(next));
    }

    fn ready(&self) -> bool {
        matches!(self.ui.status.get_untracked().as_str(), "playing" | "paused")
    }

    fn seek_to(&self, ms: f64) {
        let ready = self.ready();
        {
            let mut s = self.s.borrow_mut();
            s.pending_seek = None;
            s.seek_timer = None;
            s.pos_ms = ms;
            s.last_good_ms = ms;
            s.stall_since = 0.0;
            if !ready {
                // Still loading: the player would drop it. Applied on "playing".
                s.deferred_seek = Some(ms);
            }
        }
        if ready {
            self.player(|p| p.seek(ms));
        }
        self.ui.seek_preview.set(None);
        self.ui.pos_s.set((ms / 1000.0) as u64);
        self.check_up_next();
        self.publish();
    }

    fn go_next(&self) {
        let next = self.s.borrow().next.clone();
        if let Some(t) = next {
            nav::replace(Route::Player(t));
        }
    }

    fn go_prev(&self) {
        let prev = self.s.borrow().prev.clone();
        if let Some(t) = prev {
            nav::replace(Route::Player(t));
        }
    }

    /// Progress is written by the teardown the route change triggers.
    fn leave(&self) {
        nav::back();
    }

    fn select_source(&self, idx: usize) {
        self.ui.picker.set(false);
        if self.ui.current_source.get_untracked() == Some(idx) {
            self.show_controls();
            return;
        }
        let (pos, label) = {
            let mut s = self.s.borrow_mut();
            if let Some(l) = s.ladder.as_mut() {
                l.select(idx);
            } else {
                s.ladder = Some(Ladder::new(idx));
            }
            let label = s.sources.get(idx).map(|v| media::str_field(v, "sourceLabel").to_string());
            (s.pos_ms, label.unwrap_or_default())
        };
        self.report("switch");
        self.ui.notice.set(Some("切換片源…".into()));
        self.start_source(idx, Tier::Manifest, pos);
        let target = self.target.clone();
        wasm_bindgen_futures::spawn_local(async move { media::save_preference(&target, &label).await });
        self.show_osd();
    }

    // ── chrome ─────────────────────────────────────────────────────────────

    fn arm_hide(&self) {
        let weak = self.me.clone();
        let timer = Timeout::new(CONTROLS_TIMEOUT_MS, move || {
            let Some(c) = weak.upgrade() else { return };
            let ui = c.ui;
            // Never while paused: hidden controls on a paused picture offer
            // no visible way to start it again.
            if ui.picker.get_untracked() || ui.fatal.get_untracked().is_some() || ui.status.get_untracked() == "paused" {
                return;
            }
            if ui.seek_preview.get_untracked().is_some() {
                c.arm_hide();
                return;
            }
            ui.controls.set(false);
            ui.osd.set(false);
        });
        self.s.borrow_mut().hide_timer = Some(timer);
    }

    fn show_osd(&self) {
        self.ui.osd.set(true);
        self.arm_hide();
    }

    fn show_controls(&self) {
        self.ui.controls.set(true);
        self.show_osd();
        self.focus_later(0, "[data-screen=\"player\"] [data-vp-default]", None);
    }

    fn hide_controls(&self) {
        self.ui.controls.set(false);
        self.ui.osd.set(self.ui.status.get_untracked() == "paused");
        self.s.borrow_mut().hide_timer = None;
    }

    fn open_picker(&self) {
        self.ui.controls.set(false);
        self.ui.picker.set(true);
        self.s.borrow_mut().hide_timer = None;
        self.focus_later(
            0,
            "[data-overlay=\"sources\"] .is-current",
            Some("[data-overlay=\"sources\"] [data-focusable]"),
        );
    }

    /// Focus once the layer has rendered (next task). The handle is kept, so
    /// a newer request replaces (cancels) an older one.
    fn focus_later(&self, ms: u32, selector: &'static str, fallback: Option<&'static str>) {
        let timer = Timeout::new(ms, move || {
            if !focus::focus_selector(selector) {
                if let Some(f) = fallback {
                    focus::focus_selector(f);
                }
            }
        });
        self.s.borrow_mut().focus_timer = Some(timer);
    }

    fn close_picker(&self) {
        self.ui.picker.set(false);
        self.show_controls();
    }

    // ── the remote ─────────────────────────────────────────────────────────

    /// The remote. One layer per Back, innermost first: error panel → source
    /// picker → episode strip → scrub/seek burst → up-next → controls → leave.
    fn on_key(&self, key: Key) -> bool {
        let ui = self.ui;
        let repeat = KEY_REPEAT.with(|r| r.get());
        if key == Key::Exit {
            // The app is about to die; this is the last chance to save.
            self.report("pause");
            return false;
        }
        if ui.fatal.get_untracked().is_some() {
            // The panel's 重試 / 返回 take the D-pad and OK.
            return match key {
                Key::Back => {
                    self.leave();
                    true
                }
                _ => false,
            };
        }
        if ui.picker.get_untracked() {
            return match key {
                Key::Back => {
                    self.close_picker();
                    true
                }
                _ => false,
            };
        }
        match key {
            Key::PlayPause if !repeat => self.toggle_with_feedback(),
            Key::Play | Key::Pause if !repeat => {
                let playing = key == Key::Play;
                if playing { self.play() } else { self.pause() }
                self.flash(playing);
                self.show_osd();
            }
            Key::PlayPause | Key::Play | Key::Pause => {}
            Key::Stop => self.leave(),
            Key::Rewind => self.nudge(-MEDIA_SEEK_STEP_MS),
            Key::FastForward => self.nudge(MEDIA_SEEK_STEP_MS),
            Key::Next => self.go_next(),
            Key::Previous => self.go_prev(),
            _ => return self.on_dpad(key, repeat),
        }
        true
    }

    fn on_dpad(&self, key: Key, repeat: bool) -> bool {
        let ui = self.ui;
        if ui.strip.get_untracked().is_some() {
            return self.strip_key(key, repeat);
        }
        if ui.controls.get_untracked() {
            return match key {
                Key::Back => {
                    self.hide_controls();
                    true
                }
                Key::Up | Key::Down | Key::Left | Key::Right | Key::Enter => {
                    // focus.rs moves between (or clicks) the buttons.
                    self.arm_hide();
                    false
                }
                _ => false,
            };
        }
        let dir = match key {
            Key::Left => -1.0,
            Key::Right => 1.0,
            _ => 0.0,
        };
        if self.s.borrow().scrub.is_some() {
            match key {
                Key::Left | Key::Right => self.scrub_hold(dir),
                Key::Enter if !repeat => self.commit_scrub(),
                Key::Back => self.cancel_seek(),
                _ => {}
            }
            return true;
        }
        if ui.up_next.get_untracked().is_some() {
            match key {
                Key::Enter if !repeat => self.go_next(),
                Key::Back | Key::Down => {
                    self.s.borrow_mut().up_next_dismissed = true;
                    ui.up_next.set(None);
                }
                Key::Up => self.show_controls(),
                // Nothing destructive while the card is up.
                _ => {}
            }
            return true;
        }
        match key {
            Key::Enter => self.ok_down(repeat),
            Key::Left | Key::Right if repeat => self.scrub_hold(dir),
            Key::Left | Key::Right => {
                self.s.borrow_mut().key_down_at = now();
                self.nudge(dir * SEEK_STEP_MS);
            }
            Key::Up => self.show_controls(),
            Key::Down if !self.target.episodes.is_empty() => self.open_strip(),
            Key::Down => self.show_controls(),
            Key::Back if self.s.borrow().pending_seek.is_some() => self.cancel_seek(),
            Key::Back => self.leave(),
            _ => return false,
        }
        true
    }

    fn on_command(&self, cmd: Command) {
        self.ui.driver.set(realtime::controlled_by());
        match cmd {
            Command::Pause => self.pause(),
            Command::Resume => self.play(),
            Command::Seek(ms) => {
                // Clamped like the remote's own seeks: never past the end.
                let dur = self.s.borrow().dur_ms;
                let ms = if dur > 0.0 { ms.min((dur - 1_000.0).max(0.0)) } else { ms };
                self.seek_to(ms.max(0.0));
            }
            Command::Stop => {
                self.leave();
                return;
            }
            Command::Next => self.go_next(),
            Command::Previous => self.go_prev(),
        }
        self.show_osd();
        // The echo: the phone shows its press optimistically and waits for this.
        let weak = self.me.clone();
        let timer = Timeout::new(60, move || {
            if let Some(c) = weak.upgrade() {
                c.publish();
            }
        });
        self.s.borrow_mut().echo_timer = Some(timer);
    }

    // ── the 1 s tick ───────────────────────────────────────────────────────

    fn tick(&self) {
        let ui = self.ui;
        let driver = realtime::controlled_by();
        if ui.driver.get_untracked() != driver {
            ui.driver.set(driver);
        }
        let status = ui.status.get_untracked();
        let playing = status == "playing";
        let buffering = ui.buffering.get_untracked();
        let fatal = ui.fatal.get_untracked().is_some();
        let (intent, due, forgive, stalled) = {
            let mut s = self.s.borrow_mut();
            let t = now();
            let due = playing && t - s.last_progress_at >= PROGRESS_EVERY_MS;
            let forgive = playing && !buffering && s.last_fault_at > 0.0 && t - s.last_fault_at > FORGIVE_MS;
            if forgive {
                s.last_fault_at = 0.0;
                if let Some(l) = s.ladder.as_mut() {
                    l.forgive();
                }
            }
            // A player can also die without an error: buffering forever
            // against a source that stopped answering.
            let mut stalled = false;
            if buffering && s.intent_play && s.started && !fatal && !s.offline && !s.probing && !s.waiting_more {
                if s.stall_since == 0.0 || s.pos_ms != s.stall_anchor {
                    s.stall_since = t;
                    s.stall_anchor = s.pos_ms;
                } else if t - s.stall_since >= STALL_MS {
                    s.stall_since = 0.0;
                    stalled = true;
                }
            } else {
                s.stall_since = 0.0;
            }
            (s.intent_play, due, forgive, stalled)
        };
        let _ = forgive;
        if playing || (intent && buffering && !fatal) {
            self.publish();
        }
        if due {
            self.report("progress");
        }
        if stalled {
            self.on_fault("STREAM_STALLED".into());
        }
    }

    // ── reporting ──────────────────────────────────────────────────────────

    fn report(&self, event: &str) {
        let (pos, dur) = {
            let mut s = self.s.borrow_mut();
            s.last_progress_at = now();
            let live_pos = self.player(|p| p.position_ms()).unwrap_or(0.0);
            let dur = s.dur_ms.max(self.player(|p| p.duration_ms()).unwrap_or(0.0));
            let pos = if event == "ended" {
                dur
            } else if live_pos > 0.0 && s.pending_seek.is_none() {
                live_pos
            } else {
                s.pos_ms
            };
            (pos, dur)
        };
        // No duration yet means nothing has played: nothing worth recording.
        if dur <= 0.0 {
            return;
        }
        let label = self.ui.source_label.try_get_untracked().unwrap_or_default();
        let body = media::progress_body(&self.target, &label, pos, dur, event);
        wasm_bindgen_futures::spawn_local(async move {
            let _ = api::request("PUT", "/me/progress", Some(&body)).await;
        });
    }

    fn publish(&self) {
        let ui = self.ui;
        let t = &self.target;
        let state = {
            let s = self.s.borrow();
            json!({
                "provider": t.provider,
                "itemUrl": t.item_url,
                "title": t.title,
                "subtitle": ui.source_label.try_get_untracked().unwrap_or_default(),
                "posterUrl": t.poster_url,
                "episodeLabel": t.episode_label,
                "positionMs": s.pending_seek.unwrap_or(s.pos_ms).max(0.0).round() as u64,
                "durationMs": s.dur_ms.max(0.0).round() as u64,
                // Intent, not isPlaying: a buffering player that means to play
                // is "playing" to the remote, or its button reads wrong.
                "paused": !s.intent_play,
                "buffering": ui.buffering.try_get_untracked().unwrap_or(false),
                "hasNext": s.next.is_some(),
                "hasPrevious": s.prev.is_some(),
                "controlledBy": realtime::controlled_by(),
            })
        };
        realtime::publish_state(Some(state));
    }

    /// The last episode of a movieffm season: what follows is the next
    /// season's first episode, which takes /api/item for the season list and
    /// /api/episodes for its episodes. Anything else ends playback at the end.
    fn resolve_next_season(&self) {
        let weak = self.me.clone();
        let t = self.target.clone();
        wasm_bindgen_futures::spawn_local(async move {
            let item_path = format!(
                "/item{}",
                api::query(&[("provider", &t.provider), ("url", &t.item_url), ("title", &t.title), ("mediaType", "tv")])
            );
            let Ok(item) = api::get(&item_path).await else { return };
            let seasons: Vec<SeasonRef> = item
                .get("seasons")
                .cloned()
                .and_then(|v| serde_json::from_value(v).ok())
                .unwrap_or_default();
            let Some(UpNext::Season(season)) =
                resume::up_next(&t.episodes, Some(&t.episode_label), &seasons, Some(&t.season_url))
            else {
                return;
            };
            let path = format!("/episodes{}", api::query(&[("provider", &t.provider), ("sourceUrl", &season.url)]));
            let Ok(v) = api::get(&path).await else { return };
            let episodes: Vec<String> = v
                .get("episodes")
                .cloned()
                .and_then(|e| serde_json::from_value(e).ok())
                .unwrap_or_default();
            let Some(first) = episodes.first().cloned() else { return };
            let Some(c) = weak.upgrade() else { return };
            let next = PlayTarget {
                season_label: season.label,
                season_url: season.url,
                episodes,
                ..neighbour(&t, first)
            };
            c.s.borrow_mut().next = Some(next);
            c.ui.has_next.set(true);
        });
    }
}

#[component]
pub fn PlayerScreen(target: PlayTarget) -> impl IntoView {
    let title = target.title.clone();
    let episode = media::episode_caption(&target.episode_label);
    let episodes = StoredValue::new(target.episodes.clone());
    let episode_label = target.episode_label.clone();
    let current_ep = target.episodes.iter().position(|e| *e == target.episode_label);
    let ctl = Ctl::mount(target);
    let ui = ctl.ui;
    let id = ctl.id;
    drop(ctl);
    on_cleanup(move || teardown(id));

    let act = move |f: fn(&Ctl)| move |_: web_sys::MouseEvent| {
        if let Some(c) = active(id) {
            f(&c);
        }
    };
    // Fractions, not widths: the fill scales and the thumb translates, so a
    // tick never lays anything out (DESIGN.md: transform/opacity only).
    let frac = |num: u64, den: u64| if den == 0 { 0.0 } else { (num.min(den) as f64 / den as f64).clamp(0.0, 1.0) };
    let paused = move || ui.status.get() == "paused";
    let seeking = move || ui.seek_preview.get().is_some();
    let busy = move || (ui.buffering.get() || ui.notice.get().is_some()) && ui.fatal.get().is_none();
    let shown = move || ui.osd.get() || ui.controls.get() || busy() || paused();
    let shown_pos = move || ui.seek_preview.get().unwrap_or_else(|| ui.pos_s.get());
    let kicker = move || {
        let label = ui.source_label.get();
        match (episode.is_empty(), label.is_empty()) {
            (false, false) => format!("{episode} · {label}"),
            (false, true) => episode.clone(),
            (true, _) => label,
        }
    };

    view! {
        <div
            class="vp-root"
            data-screen="player"
            data-position=move || ui.pos_s.get().to_string()
            data-duration=move || ui.dur_s.get().to_string()
            data-controls=move || if ui.controls.get() { "visible" } else { "hidden" }
            data-state=move || ui.status.get()
            data-backend=move || ui.backend.get()
            data-source=move || ui.current_source.get().map(|i| i.to_string()).unwrap_or_default()
            data-scrub=move || ui.scrub.get().then_some("1")
            data-episode=episode_label
            // overflow: hidden still scrolls programmatically (focus.rs
            // scrolls the focused pill into view), and `overflow: clip` is
            // Chrome 90+: put the root back wherever something moves it.
            on:scroll=|e: web_sys::Event| {
                if let Some(el) = e.target().and_then(|t| t.dyn_into::<web_sys::Element>().ok()) {
                    el.set_scroll_top(0);
                    el.set_scroll_left(0);
                }
            }
        >
            <Show when=move || ui.driver.get().is_some()>
                <div class="vp-driver" data-overlay="driver">
                    <span class="vp-driver-dot"></span>
                    <span class="vp-driver-name">{move || media::driver_caption(&ui.driver.get().unwrap_or_default())}</span>
                </div>
            </Show>

            // OK feedback: a glyph that pops and fades. A new nonce re-creates
            // the element, so a second press restarts the animation.
            {move || ui.flash.get().map(|(playing, n)| view! {
                <div class="vp-flash" data-overlay="flash" data-glyph=if playing { "play" } else { "pause" }
                    data-n=n.to_string() aria-hidden="true">
                    {if playing {
                        view! { <svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg> }.into_any()
                    } else {
                        view! { <svg viewBox="0 0 24 24"><path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z"/></svg> }.into_any()
                    }}
                </div>
            })}

            <div class="vp-chrome" class:vp-show=shown class:vp-seeking=seeking class:vp-has-controls=move || ui.controls.get() class:vp-no-duration=move || ui.dur_s.get() == 0>
                <div class="vp-head">
                    <Show when=busy>
                        <div class="vp-status" data-overlay="loading">
                            {move || ui.notice.get().unwrap_or_else(|| "載入中…".into())}
                        </div>
                    </Show>
                    <div class="vp-kicker">{kicker}</div>
                    <div class="vp-title">{title}</div>
                </div>

                <div class="vp-scrub">
                    <div class="vp-track">
                        <div class="vp-track-fill"
                            style:transform=move || format!("scaleX({:.5})", frac(ui.pos_s.get(), ui.dur_s.get()))></div>
                        <Show when=seeking>
                            <div class="vp-track-target"
                                style:transform=move || format!("scaleX({:.5})", frac(shown_pos(), ui.dur_s.get()))></div>
                        </Show>
                        <Show when=move || ui.buffering.get() && ui.fatal.get().is_none()>
                            <div class="vp-track-busy" data-role="buffering"></div>
                        </Show>
                    </div>
                    <div class="vp-rail"
                        style:transform=move || format!("translateX({:.3}%)", frac(shown_pos(), ui.dur_s.get()) * 100.0)>
                        <div class="vp-thumb"></div>
                        <Show when=seeking>
                            <div class="vp-seek-pill" data-role="seek-target">
                                {move || media::format_time(ui.seek_preview.get().unwrap_or(0))}
                            </div>
                        </Show>
                    </div>
                </div>

                <div class="vp-times">
                    <span class="vp-time" data-role="time">{move || media::format_time(ui.pos_s.get())}</span>
                    <span class="vp-time vp-remaining" data-role="remaining">
                        {move || {
                            let d = ui.dur_s.get();
                            if d == 0 { "--:--".to_string() } else { format!("−{}", media::format_time(d.saturating_sub(ui.pos_s.get()))) }
                        }}
                    </span>
                </div>

                <Show when=move || ui.controls.get()>
                    <div class="vp-buttons">
                        <button class="vp-pill" data-focusable="" data-focus-group="vp-controls" data-vp-default=""
                            data-action="toggle" on:click=act(Ctl::toggle)>
                            <Show when=paused
                                fallback=|| view! { <svg class="vp-ico" viewBox="0 0 24 24"><path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z"/></svg> }>
                                <svg class="vp-ico" viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
                            </Show>
                            <span>{move || if paused() { "播放" } else { "暫停" }}</span>
                        </button>
                        <button class="vp-pill" data-focusable="" data-focus-group="vp-controls"
                            data-action="back10" on:click=act(|c| c.nudge(-SEEK_STEP_MS))>
                            <svg class="vp-ico" viewBox="0 0 24 24"><path d="M11 6V2L5 7l6 5V8a6 6 0 1 1-6 6H3a8 8 0 1 0 8-8z"/></svg>
                            <span>"10 秒"</span>
                        </button>
                        <button class="vp-pill" data-focusable="" data-focus-group="vp-controls"
                            data-action="fwd10" on:click=act(|c| c.nudge(SEEK_STEP_MS))>
                            <svg class="vp-ico" viewBox="0 0 24 24"><path d="M13 6V2l6 5-6 5V8a6 6 0 1 0 6 6h2a8 8 0 1 1-8-8z"/></svg>
                            <span>"10 秒"</span>
                        </button>
                        <Show when=move || ui.has_next.get()>
                            <button class="vp-pill" data-focusable="" data-focus-group="vp-controls"
                                data-action="next-episode" on:click=act(Ctl::go_next)>
                                <svg class="vp-ico" viewBox="0 0 24 24"><path d="M6 5v14l9-7zM16 5h2.5v14H16z"/></svg>
                                <span>"下一集"</span>
                            </button>
                        </Show>
                        <button class="vp-pill" data-focusable="" data-focus-group="vp-controls"
                            data-action="sources" on:click=act(Ctl::open_picker)>
                            <svg class="vp-ico" viewBox="0 0 24 24"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h10v2H4z"/></svg>
                            <span>"片源"</span>
                        </button>
                    </div>
                </Show>
            </div>

            <Show when=move || ui.strip.get().is_some()>
                <div class="vp-strip" data-overlay="episodes">
                    <div class="vp-strip-head">
                        <span class="vp-strip-title">"選集"</span>
                        <span class="vp-strip-count">{move || format!("共 {} 集", episodes.with_value(|e| e.len()))}</span>
                        <span class="vp-strip-hint">
                            <span class="vp-key">"◀ ▶"</span>"瀏覽"
                            <span class="vp-key">"OK"</span>"播放"
                            <span class="vp-key">"返回"</span>"關閉"
                        </span>
                    </div>
                    <div class="vp-strip-viewport">
                        <div class="vp-strip-track"
                            style:transform=move || format!("translateX({:.0}px)",
                                -strip_offset(ui.strip.get().unwrap_or(0), episodes.with_value(|e| e.len())))>
                            {move || episodes.get_value().into_iter().enumerate().map(|(i, label)| {
                                let meta = move || {
                                    if Some(i) == current_ep {
                                        return "播放中".to_string();
                                    }
                                    match ui.strip_info.with(|v| v.get(i).copied()) {
                                        Some((_, true)) => "已看完".to_string(),
                                        Some((f, false)) if f > 0.0 => format!("已看 {:.0}%", f * 100.0),
                                        _ => String::new(),
                                    }
                                };
                                let fill = move || {
                                    let f = ui.strip_info.with(|v| v.get(i).map(|x| if x.1 { 1.0 } else { x.0 }).unwrap_or(0.0));
                                    format!("scaleX({f:.3})")
                                };
                                view! {
                                    <div class="vp-ep" data-action="episode" data-index=i.to_string()
                                        class:is-focused=move || ui.strip.get() == Some(i)
                                        class:is-current=Some(i) == current_ep>
                                        <div class="vp-ep-label">{media::episode_caption(&label)}</div>
                                        <div class="vp-ep-meta">{meta}</div>
                                        <div class="vp-ep-bar"><div class="vp-ep-fill" style:transform=fill></div></div>
                                    </div>
                                }
                            }).collect_view()}
                        </div>
                    </div>
                </div>
            </Show>

            <Show when=move || ui.up_next.get().is_some()>
                <div class="vp-upnext" data-overlay="up-next">
                    <svg class="vp-ring" viewBox="0 0 80 80" aria-hidden="true">
                        <circle class="vp-ring-bg" cx="40" cy="40" r=RING_R></circle>
                        <circle class="vp-ring-fg" cx="40" cy="40" r=RING_R
                            stroke-dasharray=format!("{RING_C:.2}")
                            style:stroke-dashoffset=move || {
                                let left = ui.up_next.get().map(|u| u.1).unwrap_or(0) as f64;
                                format!("{:.2}", RING_C * (1.0 - (left / resume::UP_NEXT_LEAD_SECONDS).clamp(0.0, 1.0)))
                            }></circle>
                        <text class="vp-ring-num" x="40" y="40">{move || ui.up_next.get().map(|u| u.1).unwrap_or(0).to_string()}</text>
                    </svg>
                    <div class="vp-upnext-body">
                        <div class="vp-upnext-kicker">"即將播放"</div>
                        <div class="vp-upnext-title">{move || ui.up_next.get().map(|u| u.0).unwrap_or_default()}</div>
                        <div class="vp-upnext-hint">
                            <span class="vp-key">"OK"</span>"立即播放"
                            <span class="vp-key">"返回"</span>"取消"
                        </div>
                    </div>
                </div>
            </Show>

            <Show when=move || ui.picker.get()>
                <div class="vp-modal">
                    <div class="vp-panel vp-sources" data-overlay="sources">
                        <div class="vp-panel-title">"選擇片源"</div>
                        <div class="vp-panel-msg">"切換後會從目前位置繼續播放"</div>
                        <div class="vp-source-list">
                            {move || {
                                ui.sources
                                    .get()
                                    .into_iter()
                                    .enumerate()
                                    .map(|(i, row)| {
                                        let SourceRow { label, ad_seconds, runtime } = row;
                                        view! {
                                            <button class="vp-pill vp-source" data-focusable="" data-focus-group="vp-sources"
                                                data-action="source" data-index=i.to_string()
                                                class:is-current=move || ui.current_source.get() == Some(i)
                                                on:click=move |_| { if let Some(c) = active(id) { c.select_source(i) } }>
                                                <span class="vp-source-label">{label}</span>
                                                // Same reading as the web's 免廣告 tag: the filter
                                                // measured this source's ads and removes them, a
                                                // reason to pick it. The runtime is the content's,
                                                // ads already taken out.
                                                {(ad_seconds > 0).then(|| view! {
                                                    <span class="vp-source-ads" data-ad-seconds=ad_seconds.to_string()>
                                                        "免廣告"
                                                    </span>
                                                })}
                                                {runtime.map(|r| view! { <span class="vp-source-runtime">{r}</span> })}
                                                <Show when=move || ui.current_source.get() == Some(i)>
                                                    <span class="vp-source-now">"播放中"</span>
                                                </Show>
                                            </button>
                                        }
                                    })
                                    .collect_view()
                            }}
                        </div>
                    </div>
                </div>
            </Show>

            <Show when=move || ui.fatal.get().is_some()>
                <div class="vp-modal">
                    // The engine's code (MOCK_SOURCE_ERROR, an AVPlay error
                    // name) is for whoever debugs it, not the viewer: it is on
                    // this attribute and in the console, not on screen.
                    <div class="vp-panel" data-overlay="error"
                        data-error-code=move || ui.fatal.get().unwrap_or_default()>
                        <div class="vp-panel-icon" aria-hidden="true">"!"</div>
                        <div class="vp-panel-title">"無法播放"</div>
                        <div class="vp-panel-msg">"這一集目前沒有可用的片源。可以重試，或返回改看其他集數。"</div>
                        <div class="vp-panel-actions">
                            <button class="vp-pill vp-primary" data-focusable="" data-focus-group="vp-error"
                                data-action="retry" on:click=act(Ctl::retry)>"重試"</button>
                            <button class="vp-pill" data-focusable="" data-focus-group="vp-error"
                                data-action="back" on:click=act(Ctl::leave)>"返回"</button>
                        </div>
                    </div>
                </div>
            </Show>
        </div>
    }
}

const RING_R: f64 = 34.0;
const RING_C: f64 = 2.0 * std::f64::consts::PI * RING_R;

/// Copy every field of `from` that `into` lacks (absent, null or ""). Returns
/// whether anything was added.
fn merge_missing(into: &mut Value, from: &Value) -> bool {
    let (Some(dst), Some(src)) = (into.as_object_mut(), from.as_object()) else { return false };
    let mut changed = false;
    for (k, v) in src {
        let missing = |x: &Value| x.is_null() || x.as_str() == Some("");
        if dst.get(k).is_none_or(missing) && !missing(v) {
            dst.insert(k.clone(), v.clone());
            changed = true;
        }
    }
    changed
}

/// One line of the source picker, derived from an `/api/sources` NDJSON row.
#[derive(Clone, Debug, PartialEq)]
pub struct SourceRow {
    pub label: String,
    /// Seconds of spliced ads the server's filter found (and the manifest
    /// endpoint removes). 0 when none were found, which is not proof of none.
    pub ad_seconds: u64,
    /// Content runtime, e.g. "1:52:07" or "45:10"; None when unknown.
    pub runtime: Option<String>,
}

impl SourceRow {
    pub fn from_line(i: usize, v: &Value) -> Self {
        let l = media::str_field(v, "sourceLabel");
        let label = if l.is_empty() { format!("片源 {}", i + 1) } else { l.to_string() };
        let num = |k: &str| v.get(k).and_then(Value::as_f64).filter(|n| n.is_finite() && *n > 0.0);
        let ad_seconds = num("adSeconds").map(|n| n.round() as u64).unwrap_or(0);
        let runtime = num("durationSeconds").map(|n| {
            let t = n.round() as u64;
            let (h, m, sec) = (t / 3600, t / 60 % 60, t % 60);
            if h > 0 { format!("{h}:{m:02}:{sec:02}") } else { format!("{m}:{sec:02}") }
        });
        SourceRow { label, ad_seconds, runtime }
    }
}

#[cfg(test)]
mod source_row_tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn ads_and_runtime_come_from_the_line() {
        let r = SourceRow::from_line(0, &json!({"sourceLabel": "線路A", "adSeconds": 31.6, "durationSeconds": 6727}));
        assert_eq!(r, SourceRow { label: "線路A".into(), ad_seconds: 32, runtime: Some("1:52:07".into()) });
    }

    #[test]
    fn a_known_source_gains_the_probe_fields_without_losing_its_own() {
        let mut known = json!({"directUrl": "https://cdn/a.m3u8", "durationSeconds": null, "sourceLabel": ""});
        let probe = json!({"directUrl": "https://cdn/other.m3u8", "sourceLabel": "矽谷線路", "adSeconds": 52, "durationSeconds": 2838});
        assert!(merge_missing(&mut known, &probe));
        assert_eq!(known["directUrl"], "https://cdn/a.m3u8");
        assert_eq!(SourceRow::from_line(0, &known).ad_seconds, 52);
        assert_eq!(SourceRow::from_line(0, &known).label, "矽谷線路");
        assert!(!merge_missing(&mut known, &probe));
    }

    #[test]
    fn missing_fields_mean_no_badge_and_no_runtime() {
        let r = SourceRow::from_line(2, &json!({"durationSeconds": null}));
        assert_eq!(r, SourceRow { label: "片源 3".into(), ad_seconds: 0, runtime: None });
        assert_eq!(SourceRow::from_line(0, &json!({"durationSeconds": 2710})).runtime.as_deref(), Some("45:10"));
    }
}
