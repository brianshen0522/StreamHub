//! Bindings to `window.StreamHubPlayer` (js/player-shim.js), which fronts
//! Samsung's `webapis.avplay`, a desktop `<video>` + hls.js, or a mock clock.
//!
//! [`Player`] owns the listener closures. Dropping it detaches them from the
//! shim *before* they are freed and closes the player, so a late callback can
//! never reach a dropped closure.

use std::cell::Cell;
use std::rc::Rc;

use wasm_bindgen::prelude::*;

#[wasm_bindgen]
extern "C" {
    #[wasm_bindgen(js_namespace = StreamHubPlayer, js_name = open)]
    fn shim_open(url: &str, start_ms: f64);
    #[wasm_bindgen(js_namespace = StreamHubPlayer, js_name = play)]
    fn shim_play();
    #[wasm_bindgen(js_namespace = StreamHubPlayer, js_name = pause)]
    fn shim_pause();
    #[wasm_bindgen(js_namespace = StreamHubPlayer, js_name = seek)]
    fn shim_seek(ms: f64);
    #[wasm_bindgen(js_namespace = StreamHubPlayer, js_name = close)]
    fn shim_close();
    #[wasm_bindgen(js_namespace = StreamHubPlayer, js_name = position)]
    fn shim_position() -> f64;
    #[wasm_bindgen(js_namespace = StreamHubPlayer, js_name = duration)]
    fn shim_duration() -> f64;
    #[wasm_bindgen(js_namespace = StreamHubPlayer, js_name = backendName)]
    fn shim_backend_name() -> String;
    #[wasm_bindgen(js_namespace = StreamHubPlayer, js_name = setListener)]
    fn shim_set_listener(listener: &JsValue);
}

#[derive(Clone, Debug, PartialEq)]
pub enum Event {
    /// idle | loading | playing | paused | ended | error
    State(String),
    Time(f64),
    Buffering(bool),
    Ended,
    Error(String),
}

pub struct Player {
    _closures: Vec<Closure<dyn FnMut(JsValue)>>,
    /// Bumped by every open(). An event is stamped with it when the shim
    /// emits and dropped if an open() happened before it is handled: the
    /// shim's own generation fence runs at emit time, but handling is queued,
    /// so source A's late error must not reach source B's recovery ladder.
    opens: Rc<Cell<u64>>,
}

impl Player {
    /// Install a listener. Only one Player should exist at a time; the shim
    /// has a single listener slot.
    pub fn attach(on_event: Rc<dyn Fn(Event)>) -> Player {
        let listener = js_sys::Object::new();
        let mut closures = Vec::new();
        let opens = Rc::new(Cell::new(0u64));
        let mut add = |name: &str, map: fn(JsValue) -> Event| {
            let f = on_event.clone();
            // Deferred to a microtask: the shim emits synchronously from inside
            // open()/seek(), and handling an event re-entrantly while the
            // caller holds the controller's state would be a double borrow.
            // Order is preserved — spawned tasks run FIFO.
            let opens = opens.clone();
            let c = Closure::<dyn FnMut(JsValue)>::new(move |v: JsValue| {
                let f = f.clone();
                let event = map(v);
                let stamp = opens.get();
                let opens = opens.clone();
                wasm_bindgen_futures::spawn_local(async move {
                    if is_current(stamp, opens.get()) {
                        f(event)
                    }
                });
            });
            let _ = js_sys::Reflect::set(&listener, &name.into(), c.as_ref());
            closures.push(c);
        };
        add("onState", |v| Event::State(v.as_string().unwrap_or_default()));
        add("onTime", |v| Event::Time(v.as_f64().unwrap_or(0.0)));
        add("onBuffering", |v| Event::Buffering(v.as_bool().unwrap_or(false)));
        add("onEnded", |_| Event::Ended);
        add("onError", |v| Event::Error(v.as_string().unwrap_or_else(|| "PLAYBACK_ERROR".into())));
        shim_set_listener(&listener);
        Player { _closures: closures, opens }
    }

    pub fn open(&self, url: &str, start_ms: f64) {
        // Before the shim call: open() emits "loading" synchronously, and
        // that event belongs to the new source.
        self.opens.set(self.opens.get() + 1);
        shim_open(url, start_ms.max(0.0));
    }
    pub fn play(&self) {
        shim_play();
    }
    pub fn pause(&self) {
        shim_pause();
    }
    pub fn seek(&self, ms: f64) {
        shim_seek(ms.max(0.0));
    }
    pub fn position_ms(&self) -> f64 {
        shim_position()
    }
    pub fn duration_ms(&self) -> f64 {
        shim_duration()
    }
    pub fn backend(&self) -> String {
        shim_backend_name()
    }
}

impl Drop for Player {
    fn drop(&mut self) {
        shim_set_listener(&JsValue::NULL);
        shim_close();
    }
}

fn is_current(stamp: u64, opens_now: u64) -> bool {
    stamp == opens_now
}

#[cfg(test)]
mod tests {
    use super::is_current;

    #[test]
    fn events_from_a_replaced_source_are_dropped() {
        // Emitted while source 1 was open, handled after open() #2.
        assert!(!is_current(1, 2));
        assert!(is_current(2, 2));
    }
}
