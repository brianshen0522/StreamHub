use leptos::prelude::*;
use crate::nav::DetailTarget;
#[component]
pub fn DetailScreen(target: DetailTarget) -> impl IntoView { let _ = target; view! { <div class="screen">"detail"</div> } }
