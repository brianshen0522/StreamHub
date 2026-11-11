//! Spatial navigation for the remote. OWNED BY THE UI AGENT.
//!
//! Contract (other modules rely on exactly this):
//! - Any element with `data-focusable` takes part. Enter on it dispatches a
//!   `click`, so screens just use `on:click`.
//! - Elements sharing `data-focus-group="<name>"` form a rail/grid that
//!   remembers its last focused child: entering the group returns there.
//! - The focused element gets the `is-focused` class and real DOM focus
//!   (text inputs need it for the IME), and is scrolled into view.
//! - `set_screen_handler` lets a screen take keys first (the Player does,
//!   for seek/play-pause); returning true consumes the key.
//! - Back: screen handler first, then `nav::back()`, then the exit prompt.

use std::rc::Rc;

use crate::keys::Key;

pub type KeyHandler = Rc<dyn Fn(Key) -> bool>;

/// Install the single window keydown listener. Call once from main.
pub fn init() {
    todo!("ui agent")
}

/// Screens set this on mount and clear it (None) on cleanup.
pub fn set_screen_handler(_handler: Option<KeyHandler>) {
    todo!("ui agent")
}

/// Focus the first element matching a CSS selector (e.g. "[data-autofocus]").
/// Returns false if none exists yet — callers may retry after data loads.
pub fn focus_selector(_selector: &str) -> bool {
    todo!("ui agent")
}
