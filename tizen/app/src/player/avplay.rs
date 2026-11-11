//! Bindings to `window.StreamHubPlayer` (js/player-shim.js), which fronts
//! Samsung's `webapis.avplay`, a desktop `<video>` + hls.js, or a mock clock.
//!
//! [`Player`] owns the listener closures. Dropping it detaches them from the
//! shim *before* they are freed and closes the player, so a late callback can
//! never reach a dropped closure.

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
}

impl Player {
    /// Install a listener. Only one Player should exist at a time; the shim
    /// has a single listener slot.
    pub fn attach(on_event: Rc<dyn Fn(Event)>) -> Player {
        let listener = js_sys::Object::new();
        let mut closures = Vec::new();
        let mut add = |name: &str, map: fn(JsValue) -> Event| {
            let f = on_event.clone();
            // Deferred to a microtask: the shim emits synchronously from inside
            // open()/seek(), and handling an event re-entrantly while the
            // caller holds the controller's state would be a double borrow.
            // Order is preserved — spawned tasks run FIFO.
            let c = Closure::<dyn FnMut(JsValue)>::new(move |v: JsValue| {
                let f = f.clone();
                let event = map(v);
                wasm_bindgen_futures::spawn_local(async move { f(event) });
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
        Player { _closures: closures }
    }

    pub fn open(&self, url: &str, start_ms: f64) {
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
