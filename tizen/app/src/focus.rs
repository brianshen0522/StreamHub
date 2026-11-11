//! Spatial navigation for the remote. OWNED BY THE UI AGENT.
//!
//! Contract (other modules rely on exactly this):
//! - Any element with `data-focusable` takes part. Enter on it dispatches a
//!   `click`, so screens just use `on:click`.
//! - Elements sharing `data-focus-group="<name>"` form a rail/grid that
//!   remembers its last focused child: entering the group returns there.
//! - The focused element gets the `is-focused` class and real DOM focus
//!   (text inputs need it for the IME), and is brought into view (below).
//! - `set_screen_handler` lets a screen take keys first (the Player does,
//!   for seek/play-pause); returning true consumes the key.
//! - Back: screen handler first, then close the exit prompt if it is open,
//!   then `nav::back()`, then open the exit prompt. `Key::Exit` leaves the
//!   app at once.
//!
//! Extras the screens use:
//! - `data-focus-id="<stable id>"` names an element across re-renders. The
//!   last one focused is remembered per navigation entry, and `restore()`
//!   returns there when a screen is shown again (Back from Detail lands on
//!   the same card).
//! - `data-autofocus` marks where focus goes when nothing valid is focused.
//! - `data-focus-scope` marks a modal: while one is in the DOM, focus is kept
//!   inside the last one, and returns to where it was once it closes.
//! - Bringing into view is by transform, not scrolling (DESIGN.md "Rails"):
//!   inside `[data-rail-track]` the track is moved by translateX so the
//!   focused element sits at the track's `data-rail-start` x (default 96, the
//!   left safe edge), clamped so the rail's end stays put; inside
//!   `[data-row-column]` the column is moved by translateY so the focused
//!   `[data-row]`'s top sits at `data-row-anchor` (px within the column,
//!   default 0; rows already above it never move down). Rows get
//!   `is-current` / `is-past`, the column `has-focus`; a column that loses
//!   focus returns to rest. Tracks and columns carry a CSS transition, so a
//!   new target mid-flight restarts from where it is rather than queueing.
//!   Anything outside both falls back to `scrollIntoView` (nearest, instant).
//! - Text inputs: Left/Right move the caret (they leave the field only from
//!   its first/last position), Up/Down leave it, Backspace edits, Enter
//!   submits the enclosing `<form>` (a `submit` event is dispatched on it).

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use leptos::prelude::*;
use wasm_bindgen::prelude::*;
use wasm_bindgen::JsCast;
use web_sys::{Element, HtmlElement, KeyboardEvent};

use crate::keys::Key;
use crate::nav::{self, Route};

pub type KeyHandler = Rc<dyn Fn(Key) -> bool>;

const FOCUSABLE: &str = "[data-focusable]";
const FOCUSED_CLASS: &str = "is-focused";
/// Tizen IME "Done" / "Cancel" keys, delivered while typing in a field.
const IME_DONE: u32 = 65376;
const IME_CANCEL: u32 = 65385;

#[derive(Default)]
struct State {
    handler: Option<KeyHandler>,
    focused: Option<Element>,
    /// Group name → last focused element in it.
    groups: HashMap<String, Element>,
    /// Navigation entry → data-focus-id last focused there.
    routes: HashMap<String, String>,
    /// Where focus was before a modal scope opened.
    before_scope: Option<Element>,
}

thread_local! {
    static STATE: RefCell<State> = RefCell::new(State::default());
    static EXIT_PROMPT: ArcRwSignal<bool> = ArcRwSignal::new(false);
}

/// Whether the "離開 StreamHub？" prompt is showing. screens/exit.rs renders it.
/// An Arc signal: it lives in a global, so it must not belong to any owner.
pub fn exit_prompt() -> ArcRwSignal<bool> {
    EXIT_PROMPT.with(|s| s.clone())
}

/// Leave the application now (Tizen), or close the window on a desktop.
pub fn exit_app() {
    let Some(window) = web_sys::window() else { return };
    let tizen = js_sys::Reflect::get(&window, &"tizen".into()).unwrap_or(JsValue::UNDEFINED);
    if !tizen.is_undefined() && !tizen.is_null() {
        let app = js_sys::Reflect::get(&tizen, &"application".into())
            .ok()
            .and_then(|a| {
                let f = js_sys::Reflect::get(&a, &"getCurrentApplication".into()).ok()?;
                f.dyn_into::<js_sys::Function>().ok()?.call0(&a).ok()
            });
        if let Some(app) = app {
            if let Ok(exit) = js_sys::Reflect::get(&app, &"exit".into()) {
                if let Ok(f) = exit.dyn_into::<js_sys::Function>() {
                    let _ = f.call0(&app);
                    return;
                }
            }
        }
    }
    let _ = window.close();
}

fn document() -> Option<web_sys::Document> {
    web_sys::window()?.document()
}

/// `root.querySelectorAll(sel)` as a Vec (NodeList isn't a web-sys feature here).
fn query_all(root: &JsValue, selector: &str) -> Vec<Element> {
    let Ok(f) = js_sys::Reflect::get(root, &"querySelectorAll".into()) else { return vec![] };
    let Ok(f) = f.dyn_into::<js_sys::Function>() else { return vec![] };
    let Ok(list) = f.call1(root, &selector.into()) else { return vec![] };
    js_sys::Array::from(&list)
        .iter()
        .filter_map(|v| v.dyn_into::<Element>().ok())
        .collect()
}

fn is_visible(el: &Element) -> bool {
    let r = el.get_bounding_client_rect();
    r.width() > 0.0 && r.height() > 0.0
}

fn is_usable(el: &Element) -> bool {
    el.is_connected()
        && el.get_attribute("aria-disabled").as_deref() != Some("true")
        && !el.has_attribute("disabled")
        && is_visible(el)
}

/// The modal scope focus is confined to, if any.
fn active_scope() -> Option<Element> {
    let doc = document()?;
    query_all(&doc, "[data-focus-scope]").into_iter().filter(is_visible).last()
}

fn candidates() -> Vec<Element> {
    let root: JsValue = match active_scope() {
        Some(scope) => scope.into(),
        None => match document() {
            Some(d) => d.into(),
            None => return vec![],
        },
    };
    query_all(&root, FOCUSABLE).into_iter().filter(is_usable).collect()
}

/// The element's own `data-focus-group`, or its nearest ancestor's (a rail
/// may carry the attribute for all its cards).
fn group_of(el: &Element) -> Option<String> {
    el.closest("[data-focus-group]").ok().flatten()?.get_attribute("data-focus-group")
}

/// One key per navigation entry: depth plus what the entry is.
fn route_key() -> (usize, String) {
    nav::stack().with_untracked(|s| {
        let depth = s.len();
        let what = match s.last() {
            Some(Route::Home) | None => "home".to_string(),
            Some(Route::Search) => "search".to_string(),
            Some(Route::Detail(t)) => format!("detail:{}:{}", t.provider, t.url),
            Some(Route::Player(t)) => format!("player:{}", t.item_url),
        };
        (depth, format!("{depth}|{what}"))
    })
}

fn prune_deeper(routes: &mut HashMap<String, String>, depth: usize) {
    routes.retain(|k, _| {
        k.split('|').next().and_then(|d| d.parse::<usize>().ok()).is_none_or(|d| d <= depth)
    });
}

fn is_text_input(el: &Element) -> bool {
    match el.tag_name().as_str() {
        "TEXTAREA" => true,
        "INPUT" => {
            let t = el.get_attribute("type").unwrap_or_default().to_ascii_lowercase();
            matches!(t.as_str(), "" | "text" | "search" | "password" | "email" | "url" | "tel" | "number")
        }
        _ => false,
    }
}

fn dom_focus(el: &Element) {
    let natively = matches!(el.tag_name().as_str(), "INPUT" | "BUTTON" | "TEXTAREA" | "SELECT" | "A");
    if !natively && !el.has_attribute("tabindex") {
        let _ = el.set_attribute("tabindex", "-1");
    }
    // focus({ preventScroll: true }) — FocusOptions isn't a web-sys feature here,
    // and the native focus scroll would fight scrollIntoView below.
    let opts = js_sys::Object::new();
    let _ = js_sys::Reflect::set(&opts, &"preventScroll".into(), &JsValue::TRUE);
    if let Ok(f) = js_sys::Reflect::get(el, &"focus".into()) {
        if let Ok(f) = f.dyn_into::<js_sys::Function>() {
            let _ = f.call1(el, &opts);
        }
    }
}

fn scroll_into_view(el: &Element) {
    let opts = web_sys::ScrollIntoViewOptions::new();
    opts.set_behavior(web_sys::ScrollBehavior::Instant);
    opts.set_block(web_sys::ScrollLogicalPosition::Nearest);
    opts.set_inline(web_sys::ScrollLogicalPosition::Center);
    el.scroll_into_view_with_scroll_into_view_options(&opts);
}

fn num_attr(el: &Element, name: &str, default: f64) -> f64 {
    el.get_attribute(name).and_then(|v| v.trim().parse().ok()).unwrap_or(default)
}

/// `el`'s offset (left, top) inside `ancestor`, by walking offsetParent.
/// Transforms don't count, which is what makes this stable mid-animation.
fn offset_within(el: &HtmlElement, ancestor: &Element) -> (f64, f64) {
    let (mut x, mut y) = (0.0, 0.0);
    let mut cur = Some(el.clone());
    while let Some(e) = cur {
        if e.unchecked_ref::<Element>() == ancestor {
            break;
        }
        x += e.offset_left() as f64;
        y += e.offset_top() as f64;
        cur = e.offset_parent().and_then(|p| p.dyn_into::<HtmlElement>().ok());
    }
    (x, y)
}

fn set_transform(el: &Element, value: &str) {
    if let Some(h) = el.dyn_ref::<HtmlElement>() {
        let _ = h.style().set_property("transform", value);
    }
}

/// Slide the rail track so `el` lands in the focus column.
fn reveal_in_track(el: &HtmlElement, track: &Element) {
    let Some(track_h) = track.dyn_ref::<HtmlElement>() else { return };
    let start = num_attr(track, "data-rail-start", 96.0);
    let (left, _) = offset_within(el, track);
    let viewport = track.parent_element().map(|p| p.client_width() as f64).unwrap_or(1920.0);
    let max_shift = (track_h.offset_width() as f64 - viewport).max(0.0);
    let shift = (left - start).clamp(0.0, max_shift);
    set_transform(track, &format!("translate3d({}px,0,0)", -shift.round()));
}

/// Slide the column so the focused row's top sits on the anchor line.
fn reveal_in_column(el: &Element, column: &Element) {
    let Some(row) = el.closest("[data-row]").ok().flatten() else { return };
    let Some(row_h) = row.dyn_ref::<HtmlElement>() else { return };
    let anchor = num_attr(column, "data-row-anchor", 0.0);
    let (_, top) = offset_within(row_h, column);
    let shift = (anchor - top).min(0.0);
    set_transform(column, &format!("translate3d(0,{}px,0)", shift.round()));
    let _ = column.class_list().add_1("has-focus");
    let root: JsValue = column.clone().into();
    let mut past = true;
    for r in query_all(&root, "[data-row]") {
        if r.parent_element().and_then(|p| p.closest("[data-row-column]").ok().flatten()).as_ref() != Some(column) {
            continue;
        }
        let is_current = r == row;
        if is_current {
            past = false;
        }
        let _ = r.class_list().toggle_with_force("is-current", is_current);
        let _ = r.class_list().toggle_with_force("is-past", past && !is_current);
    }
}

fn rest_column(column: &Element) {
    set_transform(column, "translate3d(0,0,0)");
    let _ = column.class_list().remove_1("has-focus");
    let root: JsValue = column.clone().into();
    for r in query_all(&root, "[data-row]") {
        let _ = r.class_list().remove_2("is-current", "is-past");
    }
}

/// Bring `el` into view: rail track and row column by transform, anything
/// else by an instant scroll.
fn reveal(el: &Element) {
    let mut handled = false;
    if let (Ok(Some(track)), Some(h)) = (el.closest("[data-rail-track]"), el.dyn_ref::<HtmlElement>()) {
        reveal_in_track(h, &track);
        handled = true;
    }
    let column = el.closest("[data-row-column]").ok().flatten();
    if let Some(doc) = document() {
        for other in query_all(&doc, "[data-row-column].has-focus") {
            if Some(&other) != column.as_ref() {
                rest_column(&other);
            }
        }
    }
    if let Some(column) = &column {
        reveal_in_column(el, column);
        handled = true;
    }
    if !handled && el.closest("[data-no-scroll]").ok().flatten().is_none() {
        scroll_into_view(el);
    }
}

/// Move focus to `el`: class, DOM focus, scroll, and the memories.
pub fn focus_element(el: &Element) {
    focus_element_with(el, true);
}

/// Record `el` as where this navigation entry was left.
fn remember_route(el: &Element) {
    // Inside a modal, the screen's memory is left alone.
    if el.closest("[data-focus-scope]").ok().flatten().is_some() {
        return;
    }
    if let Some(id) = el.get_attribute("data-focus-id") {
        let (depth, key) = route_key();
        STATE.with(|s| {
            let mut s = s.borrow_mut();
            prune_deeper(&mut s.routes, depth);
            s.routes.insert(key, id);
        });
    }
}

/// `remember` is false for the watchdog's fallbacks: landing somewhere by
/// default (before a screen's data arrived) is not a choice worth restoring.
fn focus_element_with(el: &Element, remember: bool) {
    let previous = STATE.with(|s| s.borrow().focused.clone());
    if let Some(prev) = &previous {
        if prev != el {
            let _ = prev.class_list().remove_1(FOCUSED_CLASS);
        }
    }
    // Exactly one element carries the class, even if something re-rendered.
    if let Some(doc) = document() {
        for other in query_all(&doc, ".is-focused") {
            if &other != el {
                let _ = other.class_list().remove_1(FOCUSED_CLASS);
            }
        }
    }
    let _ = el.class_list().add_1(FOCUSED_CLASS);
    STATE.with(|s| {
        let mut s = s.borrow_mut();
        s.focused = Some(el.clone());
        if let Some(g) = group_of(el) {
            s.groups.insert(g, el.clone());
        }
    });
    if remember {
        remember_route(el);
    }
    dom_focus(el);
    reveal(el);
}

/// Focus the first element matching a CSS selector (e.g. "[data-autofocus]").
/// Returns false if none exists yet — callers may retry after data loads.
pub fn focus_selector(selector: &str) -> bool {
    let Some(doc) = document() else { return false };
    match query_all(&doc, selector).into_iter().find(is_usable) {
        Some(el) => {
            focus_element(&el);
            true
        }
        None => false,
    }
}

/// After the current render settles (a 0 ms timeout runs after Leptos'
/// microtask flush), focus `selector`.
pub fn focus_later(selector: &str) {
    let selector = selector.to_string();
    gloo_timers::callback::Timeout::new(0, move || {
        focus_selector(&selector);
    })
    .forget();
}

/// Return to the element last focused on this navigation entry, if it is in
/// the DOM. Screens call this once their data has rendered.
pub fn restore() -> bool {
    let (depth, key) = route_key();
    let id = STATE.with(|s| {
        let mut s = s.borrow_mut();
        prune_deeper(&mut s.routes, depth);
        s.routes.get(&key).cloned()
    });
    let Some(id) = id else { return false };
    let escaped = id.replace('\\', "\\\\").replace('"', "\\\"");
    focus_selector(&format!("[data-focus-id=\"{escaped}\"]"))
}

/// The element the remote is on, if it is still in the document.
pub fn focused() -> Option<Element> {
    STATE.with(|s| s.borrow().focused.clone()).filter(|e| e.is_connected())
}

/// True when the focused element (if any) matches `selector`.
pub fn focused_matches(selector: &str) -> bool {
    focused().is_some_and(|e| e.matches(selector).unwrap_or(false))
}

/// Screens set this on mount and clear it (None) on cleanup. The next screen
/// may mount before the previous one's cleanup runs, so a screen should make
/// its handler inert on cleanup (see `screen_alive`) rather than set None
/// blindly.
pub fn set_screen_handler(handler: Option<KeyHandler>) {
    STATE.with(|s| s.borrow_mut().handler = handler);
}

/// A flag a screen's handler checks, flipped off by `on_cleanup` (which needs
/// Send + Sync, hence not an Rc).
pub fn screen_alive() -> std::sync::Arc<std::sync::atomic::AtomicBool> {
    std::sync::Arc::new(std::sync::atomic::AtomicBool::new(true))
}

#[derive(Clone, Copy, PartialEq)]
enum Dir {
    Up,
    Down,
    Left,
    Right,
}

struct Rect {
    l: f64,
    t: f64,
    r: f64,
    b: f64,
}

impl Rect {
    fn of(el: &Element) -> Rect {
        let r = el.get_bounding_client_rect();
        Rect { l: r.left(), t: r.top(), r: r.right(), b: r.bottom() }
    }
    fn cx(&self) -> f64 {
        (self.l + self.r) / 2.0
    }
    fn cy(&self) -> f64 {
        (self.t + self.b) / 2.0
    }
}

/// Gap between two intervals, 0 when they overlap.
fn gap(a0: f64, a1: f64, b0: f64, b1: f64) -> f64 {
    if a1 < b0 {
        b0 - a1
    } else if b1 < a0 {
        a0 - b1
    } else {
        0.0
    }
}

/// How a candidate lies relative to the focused element.
#[derive(Clone, Copy)]
struct Measure {
    /// Distance along the pressed direction (0 when touching/overlapping).
    primary: f64,
    /// Gap between the two on the other axis, 0 when they overlap.
    cross_gap: f64,
    /// Offset between centres on the other axis.
    cross_centre: f64,
    /// Offset between leading (left/top) edges on the other axis: entering a
    /// rail from a wide element lands on its start, not under its middle.
    cross_edge: f64,
}

/// Some(m) when `c` lies in `dir` from `cur`.
fn measure(cur: &Rect, c: &Rect, dir: Dir) -> Option<Measure> {
    // A little slack: the focused element is scaled up, so its edges overlap
    // its neighbours' by a few pixels.
    const SLACK: f64 = 2.0;
    let horizontal = |primary: f64| Measure {
        primary: primary.max(0.0),
        cross_gap: gap(cur.t, cur.b, c.t, c.b),
        cross_centre: (c.cy() - cur.cy()).abs(),
        cross_edge: (c.t - cur.t).abs(),
    };
    let vertical = |primary: f64| Measure {
        primary: primary.max(0.0),
        cross_gap: gap(cur.l, cur.r, c.l, c.r),
        cross_centre: (c.cx() - cur.cx()).abs(),
        cross_edge: (c.l - cur.l).abs(),
    };
    match dir {
        Dir::Right if c.cx() > cur.cx() + SLACK && c.l >= cur.l + SLACK => Some(horizontal(c.l - cur.r)),
        Dir::Left if c.cx() < cur.cx() - SLACK && c.r <= cur.r - SLACK => Some(horizontal(cur.l - c.r)),
        Dir::Down if c.cy() > cur.cy() + SLACK && c.t >= cur.t + SLACK => Some(vertical(c.t - cur.b)),
        Dir::Up if c.cy() < cur.cy() - SLACK && c.b <= cur.b - SLACK => Some(vertical(cur.t - c.b)),
        _ => None,
    }
}

/// Primary distance plus a heavy penalty on cross-axis offset.
fn score(m: &Measure) -> f64 {
    m.primary + m.cross_gap * 6.0 + m.cross_centre * 0.3
}

fn pick<'a, I, F>(list: I, key: F) -> Option<Element>
where
    I: Iterator<Item = &'a (Element, Measure)>,
    F: Fn(&Measure) -> f64,
{
    list.min_by(|a, b| key(&a.1).total_cmp(&key(&b.1))).map(|(e, _)| e.clone())
}

fn find_target(cur: &Element, dir: Dir) -> Option<Element> {
    let cur_rect = Rect::of(cur);
    let cur_group = group_of(cur);
    let all = candidates();
    let measured: Vec<(Element, Measure)> = all
        .iter()
        .filter(|e| *e != cur)
        .filter_map(|e| measure(&cur_rect, &Rect::of(e), dir).map(|m| (e.clone(), m)))
        .collect();
    if measured.is_empty() {
        return None;
    }

    let chosen: Element = match dir {
        Dir::Left | Dir::Right => {
            // Stay in the rail while it has somewhere to go.
            let same = cur_group
                .as_ref()
                .and_then(|g| pick(measured.iter().filter(|(e, _)| group_of(e).as_ref() == Some(g)), score));
            match same {
                Some(e) => e,
                // Leaving sideways only to something on the same line: the end
                // of one rail must not jump diagonally into the next.
                None => pick(measured.iter().filter(|(_, m)| m.cross_gap == 0.0), score)?,
            }
        }
        Dir::Up | Dir::Down => {
            // The nearest row first, then on that row the closest thing:
            // overlapping before not, then aligned leading edges.
            let nearest = pick(measured.iter(), |m| m.primary + m.cross_centre * 0.001)?;
            let n = Rect::of(&nearest);
            let row = measured.iter().filter(|(e, _)| {
                let r = Rect::of(e);
                r.t < n.b && r.b > n.t
            });
            pick(row, |m| m.cross_gap * 10.0 + m.cross_edge).unwrap_or(nearest)
        }
    };

    // Entering a different group returns to where that group was left, or
    // the first time, to its selected item (the current episode or season).
    if let Some(g) = group_of(&chosen) {
        if cur_group.as_ref() != Some(&g) {
            let remembered = STATE.with(|s| s.borrow().groups.get(&g).cloned());
            if let Some(r) = remembered {
                if is_usable(&r) && all.contains(&r) {
                    return Some(r);
                }
            }
            let selected = all
                .iter()
                .find(|e| group_of(e).as_ref() == Some(&g) && e.class_list().contains("is-selected"));
            if let Some(sel) = selected {
                return Some(sel.clone());
            }
        }
    }
    Some(chosen)
}

fn move_focus(dir: Dir) -> bool {
    let Some(cur) = focused().filter(is_usable) else {
        ensure_valid();
        return true;
    };
    if let Some(target) = find_target(&cur, dir) {
        focus_element(&target);
    }
    true
}

/// Keep focus on something real: after a route change, a data load or a
/// modal opening/closing. Runs from the key handler and a slow ticker.
pub fn ensure_valid() {
    let scope = active_scope();
    let current = focused().filter(is_usable);
    match &scope {
        Some(scope) => {
            let inside = current.as_ref().is_some_and(|c| scope.contains(Some(c.as_ref())));
            if !inside {
                if let Some(c) = current.as_ref().filter(|c| !scope.contains(Some(c.as_ref()))) {
                    STATE.with(|s| s.borrow_mut().before_scope = Some(c.clone()));
                }
                let root: JsValue = scope.clone().into();
                let pick = query_all(&root, "[data-autofocus]")
                    .into_iter()
                    .find(is_usable)
                    .or_else(|| query_all(&root, FOCUSABLE).into_iter().find(is_usable));
                if let Some(el) = pick {
                    focus_element(&el);
                }
            }
        }
        None => {
            let before = STATE.with(|s| s.borrow_mut().before_scope.take());
            let in_dead_scope = current
                .as_ref()
                .is_some_and(|c| c.closest("[data-focus-scope]").ok().flatten().is_some());
            if current.is_some() && !in_dead_scope {
                return;
            }
            if let Some(b) = before.filter(is_usable) {
                focus_element(&b);
                return;
            }
            if restore() {
                return;
            }
            let Some(doc) = document() else { return };
            let pick = query_all(&doc, "[data-autofocus]")
                .into_iter()
                .find(is_usable)
                .or_else(|| query_all(&doc, FOCUSABLE).into_iter().find(is_usable));
            if let Some(el) = pick {
                focus_element_with(&el, false);
            }
        }
    }
}

fn handle_back(handler: Option<KeyHandler>) {
    if let Some(h) = handler {
        if h(Key::Back) {
            return;
        }
    }
    let prompt = exit_prompt();
    if prompt.get_untracked() {
        prompt.set(false);
        gloo_timers::callback::Timeout::new(0, ensure_valid).forget();
        return;
    }
    if nav::back() {
        return;
    }
    prompt.set(true);
    gloo_timers::callback::Timeout::new(0, ensure_valid).forget();
}

fn click(el: &Element) {
    if let Some(h) = el.dyn_ref::<HtmlElement>() {
        h.click();
    }
}

fn submit_form(el: &Element) {
    if let Ok(Some(form)) = el.closest("form") {
        if let Ok(ev) = web_sys::Event::new("submit") {
            let _ = form.dispatch_event(&ev);
        }
    }
}

/// Caret position and length of a focused text input (None when the type has
/// no selection API).
fn caret(el: &Element) -> Option<(u32, u32, u32)> {
    let input = el.dyn_ref::<web_sys::HtmlInputElement>()?;
    let start = input.selection_start().ok().flatten()?;
    let end = input.selection_end().ok().flatten()?;
    Some((start, end, input.value().encode_utf16().count() as u32))
}

fn on_keydown(event: KeyboardEvent) {
    let code = event.key_code();
    if event.is_composing() || code == 229 {
        return;
    }
    let key = Key::from_code(code, &event.key());
    let active = document().and_then(|d| d.active_element());
    let in_text = active.as_ref().is_some_and(is_text_input);
    let prompt_open = exit_prompt().get_untracked();
    let handler = if prompt_open { None } else { STATE.with(|s| s.borrow().handler.clone()) };

    if in_text {
        let field = active.clone().unwrap();
        match (code, key) {
            // Backspace edits the field; it is not Back here.
            (8, _) => return,
            (IME_CANCEL, _) => return,
            (IME_DONE, _) | (_, Key::Enter) => {
                event.prevent_default();
                submit_form(&field);
                return;
            }
            (_, Key::Left) | (_, Key::Right) => {
                // Leave only from the edge the caret is pressed against.
                let at_edge = match caret(&field) {
                    Some((s, e, len)) => {
                        s == e && if key == Key::Left { s == 0 } else { e == len }
                    }
                    None => false,
                };
                if !at_edge {
                    return;
                }
            }
            _ => {}
        }
    }

    // The screen gets every key first; Back goes through the chain below,
    // which also starts with it.
    if key != Key::Back {
        if let Some(h) = &handler {
            if h(key) {
                event.prevent_default();
                return;
            }
        }
    }

    match key {
        Key::Up => {
            event.prevent_default();
            move_focus(Dir::Up);
        }
        Key::Down => {
            event.prevent_default();
            move_focus(Dir::Down);
        }
        Key::Left => {
            event.prevent_default();
            move_focus(Dir::Left);
        }
        Key::Right => {
            event.prevent_default();
            move_focus(Dir::Right);
        }
        Key::Enter => {
            event.prevent_default();
            match focused().filter(is_usable) {
                Some(el) => {
                    // Pressing OK is a choice even where focus only landed by default.
                    remember_route(&el);
                    click(&el)
                }
                None => ensure_valid(),
            }
        }
        Key::Back => {
            event.prevent_default();
            handle_back(handler);
        }
        Key::Exit => {
            event.prevent_default();
            exit_app();
        }
        _ => {}
    }
}

/// Keeps the bookkeeping right when focus moves by other means (a click, or
/// the IME handing focus back).
fn on_focusin(event: web_sys::FocusEvent) {
    let Some(target) = event.target().and_then(|t| t.dyn_into::<Element>().ok()) else { return };
    let Ok(Some(el)) = target.closest(FOCUSABLE) else { return };
    let same = STATE.with(|s| s.borrow().focused.as_ref() == Some(&el));
    if !same {
        focus_element(&el);
    }
}

/// Install the single window keydown listener. Call once from main.
pub fn init() {
    let Some(window) = web_sys::window() else { return };
    let keydown = Closure::<dyn FnMut(KeyboardEvent)>::new(on_keydown);
    let _ = window.add_event_listener_with_callback("keydown", keydown.as_ref().unchecked_ref());
    keydown.forget();
    let focusin = Closure::<dyn FnMut(web_sys::FocusEvent)>::new(on_focusin);
    let _ = window.add_event_listener_with_callback("focusin", focusin.as_ref().unchecked_ref());
    focusin.forget();
    // A cheap safety net: one isConnected check every 200 ms keeps focus valid
    // after renders nobody announced (route changes, data arriving).
    gloo_timers::callback::Interval::new(200, ensure_valid).forget();
}
