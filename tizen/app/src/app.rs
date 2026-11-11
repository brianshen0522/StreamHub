//! The root: Login when signed out, otherwise the navigation stack's top.

use leptos::prelude::*;

use crate::nav::{self, Route};
use crate::screens::{DetailScreen, ExitPrompt, HomeScreen, LoginScreen, SearchScreen};
use crate::player::PlayerScreen;
use crate::session;

#[component]
pub fn App() -> impl IntoView {
    let session = session::signal();
    let signed_in = Memo::new(move |_| session.with(|s| s.is_some()));
    let route = nav::current();

    // Signing out (or a dead refresh) drops back to Login with a clean stack.
    Effect::new(move |_| {
        if !signed_in.get() {
            nav::reset();
        }
    });

    view! {
        <div class="app">
            {move || {
                if !signed_in.get() {
                    return view! { <LoginScreen/> }.into_any();
                }
                match route.get() {
                    Route::Home => view! { <HomeScreen/> }.into_any(),
                    Route::Search => view! { <SearchScreen/> }.into_any(),
                    Route::Detail(target) => view! { <DetailScreen target=target/> }.into_any(),
                    Route::Player(target) => view! { <PlayerScreen target=target/> }.into_any(),
                }
            }}
            <ExitPrompt/>
        </div>
    }
}
