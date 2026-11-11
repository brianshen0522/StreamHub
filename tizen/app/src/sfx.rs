//! Interface sounds, played by js/sfx.js (which owns all the overlap,
//! priority and spacing rules). Every call is fire-and-forget: a missing
//! script or a blocked AudioContext means silence, never an error.

use wasm_bindgen::prelude::*;

#[wasm_bindgen]
extern "C" {
    #[wasm_bindgen(catch, js_namespace = StreamHubSfx, js_name = play)]
    fn js_play(name: &str, repeat: bool) -> Result<JsValue, JsValue>;
    #[wasm_bindgen(catch, js_namespace = StreamHubSfx, js_name = setEnabled)]
    fn js_set_enabled(on: bool) -> Result<JsValue, JsValue>;
    #[wasm_bindgen(catch, js_namespace = StreamHubSfx, js_name = enabled)]
    fn js_enabled() -> Result<JsValue, JsValue>;
    #[wasm_bindgen(catch, js_namespace = StreamHubSfx, js_name = setPlayer)]
    fn js_set_player(up: bool) -> Result<JsValue, JsValue>;
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Sound {
    /// Focus moved.
    Move,
    /// A direction met the edge: nothing to move to.
    Edge,
    /// OK on something.
    Select,
    /// Back.
    Back,
}

impl Sound {
    fn name(self) -> &'static str {
        match self {
            Sound::Move => "move",
            Sound::Edge => "edge",
            Sound::Select => "select",
            Sound::Back => "back",
        }
    }
}

pub fn play(sound: Sound) {
    let _ = js_play(sound.name(), false);
}

/// A move from a held key: spaced wider and quieter by the script.
pub fn play_repeat(sound: Sound) {
    let _ = js_play(sound.name(), true);
}

pub fn enabled() -> bool {
    js_enabled().ok().and_then(|v| v.as_bool()).unwrap_or(false)
}

pub fn set_enabled(on: bool) -> bool {
    js_set_enabled(on).ok().and_then(|v| v.as_bool()).unwrap_or(false)
}

/// The player is up (true) or gone (false): silent and suspended meanwhile.
pub fn set_player(up: bool) {
    let _ = js_set_player(up);
}
