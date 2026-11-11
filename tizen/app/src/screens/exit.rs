//! The "leave StreamHub?" prompt shown when Back is pressed on Home. Opened
//! and closed through `focus::exit_prompt()`; Back closes it (focus.rs).

use leptos::prelude::*;

use crate::focus;

#[component]
pub fn ExitPrompt() -> impl IntoView {
    let open = RwSignal::from(focus::exit_prompt());
    view! {
        <Show when=move || open.get()>
            <div class="tv-modal-backdrop" data-screen="exit">
                <div class="tv-modal" data-focus-scope="" role="dialog">
                    <h2 class="tv-modal-title">"離開 StreamHub？"</h2>
                    <div class="tv-modal-actions">
                        <button
                            class="tv-btn"
                            data-focusable=""
                            data-autofocus=""
                            data-action="exit-cancel"
                            on:click=move |_| {
                                open.set(false);
                                gloo_timers::callback::Timeout::new(0, focus::ensure_valid).forget();
                            }
                        >
                            "取消"
                        </button>
                        <button
                            class="tv-btn tv-btn-danger"
                            data-focusable=""
                            data-action="exit-confirm"
                            on:click=move |_| focus::exit_app()
                        >
                            "離開"
                        </button>
                    </div>
                </div>
            </div>
        </Show>
    }
}
