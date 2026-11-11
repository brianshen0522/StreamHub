//! OWNED BY THE PLAYER AGENT: AVPlay bindings (avplay.rs over js/player-shim.js),
//! the player screen, progress reporting and up-next.

use leptos::prelude::*;
use crate::nav::PlayTarget;

#[component]
pub fn PlayerScreen(target: PlayTarget) -> impl IntoView { let _ = target; view! { <div class="screen">"player"</div> } }
