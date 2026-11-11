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
use streamhub_core::models::SeasonRef;
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
    sources: RwSignal<Vec<String>>,
    current_source: RwSignal<Option<usize>>,
    source_label: RwSignal<String>,
    driver: RwSignal<Option<String>>,
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
}

fn active(id: u64) -> Option<Rc<Ctl>> {
    ACTIVE.with(|a| a.borrow().as_ref().filter(|c| c.id == id).cloned())
}

fn now() -> f64 {
    js_sys::Date::now()
}

fn later(ms: u32, f: impl FnOnce() + 'static) {
    Timeout::new(ms, f).forget();
}

fn set_body_playing(on: bool) {
    let Some(body) = web_sys::window().and_then(|w| w.document()).and_then(|d| d.body()) else { return };
    let current = body.class_name();
    let mut parts: Vec<&str> = current.split_whitespace().filter(|c| *c != "vp-playing").collect();
    if on {
        parts.push("vp-playing");
    }
    body.set_class_name(&parts.join(" "));
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
        let labels: Vec<String> = self
            .s
            .borrow()
            .sources
            .iter()
            .enumerate()
            .map(|(i, v)| {
                let l = media::str_field(v, "sourceLabel");
                if l.is_empty() { format!("片源 {}", i + 1) } else { l.to_string() }
            })
            .collect();
        self.ui.sources.set(labels);
    }

    fn on_source(&self, line: Value) {
        let Some(direct) = media::direct_url(&line).map(str::to_string) else { return };
        let (idx, started, waiting, preferred, label) = {
            let mut s = self.s.borrow_mut();
            if s.sources.iter().any(|v| media::direct_url(v) == Some(direct.as_str())) {
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
        self.s.borrow_mut().intent_play = false;
        self.ui.fatal.set(Some(msg));
        self.ui.notice.set(None);
        self.ui.buffering.set(false);
        self.ui.controls.set(false);
        self.ui.picker.set(false);
        self.ui.up_next.set(None);
        self.ui.osd.set(false);
        self.publish();
        later(30, || {
            focus::focus_selector("[data-screen=\"player\"] [data-action=\"retry\"]");
        });
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
                        self.s.borrow_mut().intent_play = true;
                        self.publish();
                    }
                    "paused" => {
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

    /// Left/Right: presses accumulate into one seek, sent once they stop.
    fn nudge(&self, delta_ms: f64) {
        if self.ui.fatal.get_untracked().is_some() || !self.s.borrow().started {
            return;
        }
        let target = {
            let s = self.s.borrow();
            let base = s.pending_seek.unwrap_or(s.pos_ms);
            let mut t = (base + delta_ms).max(0.0);
            if s.dur_ms > 0.0 {
                t = t.min((s.dur_ms - 1_000.0).max(0.0));
            }
            t
        };
        self.ui.seek_preview.set(Some((target / 1000.0) as u64));
        self.show_osd();
        let weak = self.me.clone();
        let timer = Timeout::new(SEEK_DEBOUNCE_MS, move || {
            if let Some(c) = weak.upgrade() {
                let t = c.s.borrow_mut().pending_seek.take();
                if let Some(t) = t {
                    c.seek_to(t);
                }
            }
        });
        let mut s = self.s.borrow_mut();
        s.pending_seek = Some(target);
        s.seek_timer = Some(timer);
    }

    fn seek_to(&self, ms: f64) {
        {
            let mut s = self.s.borrow_mut();
            s.pending_seek = None;
            s.seek_timer = None;
            s.pos_ms = ms;
            s.last_good_ms = ms;
            s.stall_since = 0.0;
        }
        self.player(|p| p.seek(ms));
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
        later(0, || {
            focus::focus_selector("[data-screen=\"player\"] [data-vp-default]");
        });
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
        later(0, || {
            if !focus::focus_selector("[data-overlay=\"sources\"] .is-current") {
                focus::focus_selector("[data-overlay=\"sources\"] [data-focusable]");
            }
        });
    }

    fn close_picker(&self) {
        self.ui.picker.set(false);
        self.show_controls();
    }

    // ── the remote ─────────────────────────────────────────────────────────

    fn on_key(&self, key: Key) -> bool {
        let ui = self.ui;
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
            Key::PlayPause => self.toggle(),
            Key::Play => self.play(),
            Key::Pause => self.pause(),
            Key::Stop => self.leave(),
            Key::Rewind => self.nudge(-MEDIA_SEEK_STEP_MS),
            Key::FastForward => self.nudge(MEDIA_SEEK_STEP_MS),
            Key::Next => self.go_next(),
            Key::Previous => self.go_prev(),
            _ => {
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
                let up_next = ui.up_next.get_untracked().is_some();
                match key {
                    Key::Enter if up_next => self.go_next(),
                    Key::Back if up_next => {
                        self.s.borrow_mut().up_next_dismissed = true;
                        ui.up_next.set(None);
                    }
                    Key::Enter => {
                        self.toggle();
                        self.show_osd();
                    }
                    Key::Left => self.nudge(-SEEK_STEP_MS),
                    Key::Right => self.nudge(SEEK_STEP_MS),
                    Key::Up | Key::Down => self.show_controls(),
                    Key::Back => self.leave(),
                    _ => return false,
                }
                return true;
            }
        }
        self.show_osd();
        true
    }

    fn on_command(&self, cmd: Command) {
        self.ui.driver.set(realtime::controlled_by());
        match cmd {
            Command::Pause => self.pause(),
            Command::Resume => self.play(),
            Command::Seek(ms) => self.seek_to(ms.max(0.0)),
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
        later(60, move || {
            if let Some(c) = weak.upgrade() {
                c.publish();
            }
        });
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
    let pct = |num: u64, den: u64| {
        if den == 0 { "0%".to_string() } else { format!("{:.2}%", (num.min(den) as f64 / den as f64) * 100.0) }
    };
    let paused = move || ui.status.get() == "paused";
    let subtitle = move || {
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
        >
            <Show when=move || (ui.buffering.get() || ui.notice.get().is_some()) && ui.fatal.get().is_none()>
                <div class="vp-center" data-overlay="loading">
                    <div class="vp-spinner"></div>
                    <div class="vp-notice">{move || ui.notice.get().unwrap_or_else(|| "載入中…".into())}</div>
                </div>
            </Show>

            <Show when=move || ui.driver.get().is_some()>
                <div class="vp-driver" data-overlay="driver">
                    <span class="vp-driver-dot"></span>
                    {move || format!("由 {} 控制", ui.driver.get().unwrap_or_default())}
                </div>
            </Show>

            <Show when=move || ui.up_next.get().is_some()>
                <div class="vp-upnext" data-overlay="up-next">
                    <div class="vp-upnext-title">{move || ui.up_next.get().map(|u| u.0).unwrap_or_default()}</div>
                    <div class="vp-upnext-hint">
                        {move || format!("{} 秒後自動播放", ui.up_next.get().map(|u| u.1).unwrap_or(0))}
                        <span class="vp-key">"OK"</span>"立即播放"
                        <span class="vp-key">"返回"</span>"取消"
                    </div>
                </div>
            </Show>

            <div class="vp-bar" class:vp-show=move || ui.osd.get() || ui.controls.get()>
                <div class="vp-meta">
                    <div class="vp-title">{title}</div>
                    <div class="vp-sub">{subtitle}</div>
                </div>
                <div class="vp-progress">
                    <div class="vp-progress-fill" style:width=move || pct(ui.pos_s.get(), ui.dur_s.get())></div>
                    <Show when=move || ui.seek_preview.get().is_some()>
                        <div
                            class="vp-progress-target"
                            style:left=move || pct(ui.seek_preview.get().unwrap_or(0), ui.dur_s.get())
                        ></div>
                    </Show>
                </div>
                <div class="vp-row">
                    <span class="vp-time" data-role="time">
                        {move || format!("{} / {}", media::format_time(ui.pos_s.get()), media::format_time(ui.dur_s.get()))}
                    </span>
                    <Show when=move || ui.seek_preview.get().is_some()>
                        <span class="vp-seek-target" data-role="seek-target">
                            {move || format!("→ {}", media::format_time(ui.seek_preview.get().unwrap_or(0)))}
                        </span>
                    </Show>
                    <span class="vp-hints">
                        <span class="vp-key">"OK"</span>{move || if paused() { "播放" } else { "暫停" }}
                        <span class="vp-key">"◀ ▶"</span>"10 秒"
                        <span class="vp-key">"▲"</span>"選單"
                        <span class="vp-key">"返回"</span>"離開"
                    </span>
                </div>
                <Show when=move || ui.controls.get()>
                    <div class="vp-buttons">
                        <button class="vp-btn" data-focusable="" data-focus-group="vp-controls" data-vp-default=""
                            data-action="toggle" on:click=act(Ctl::toggle)>
                            {move || if paused() { "播放" } else { "暫停" }}
                        </button>
                        <button class="vp-btn" data-focusable="" data-focus-group="vp-controls"
                            data-action="back10" on:click=act(|c| c.nudge(-SEEK_STEP_MS))>"−10 秒"</button>
                        <button class="vp-btn" data-focusable="" data-focus-group="vp-controls"
                            data-action="fwd10" on:click=act(|c| c.nudge(SEEK_STEP_MS))>"+10 秒"</button>
                        <Show when=move || ui.has_next.get()>
                            <button class="vp-btn" data-focusable="" data-focus-group="vp-controls"
                                data-action="next-episode" on:click=act(Ctl::go_next)>"下一集"</button>
                        </Show>
                        <button class="vp-btn" data-focusable="" data-focus-group="vp-controls"
                            data-action="sources" on:click=act(Ctl::open_picker)>"片源"</button>
                    </div>
                </Show>
            </div>

            <Show when=move || ui.picker.get()>
                <div class="vp-panel vp-sources" data-overlay="sources">
                    <div class="vp-panel-title">"片源"</div>
                    <div class="vp-source-list">
                        {move || {
                            ui.sources
                                .get()
                                .into_iter()
                                .enumerate()
                                .map(|(i, label)| {
                                    view! {
                                        <button class="vp-btn vp-source" data-focusable="" data-focus-group="vp-sources"
                                            data-action="source" data-index=i.to_string()
                                            class:is-current=move || ui.current_source.get() == Some(i)
                                            on:click=move |_| { if let Some(c) = active(id) { c.select_source(i) } }>
                                            {label}
                                        </button>
                                    }
                                })
                                .collect_view()
                        }}
                    </div>
                </div>
            </Show>

            <Show when=move || ui.fatal.get().is_some()>
                <div class="vp-panel" data-overlay="error">
                    <div class="vp-panel-title">"無法播放"</div>
                    <div class="vp-panel-msg">{move || ui.fatal.get().unwrap_or_default()}</div>
                    <div class="vp-panel-actions">
                        <button class="vp-btn vp-primary" data-focusable="" data-focus-group="vp-error"
                            data-action="retry" on:click=act(Ctl::retry)>"重試"</button>
                        <button class="vp-btn" data-focusable="" data-focus-group="vp-error"
                            data-action="back" on:click=act(Ctl::leave)>"返回"</button>
                    </div>
                </div>
            </Show>
        </div>
    }
}
