//! The root: Login when signed out, otherwise the navigation stack's top.

use leptos::prelude::*;

use crate::nav::{self, Route};
use crate::player::PlayerScreen;
use crate::screens::{DetailScreen, ExitPrompt, HomeScreen, LoginScreen, SearchScreen};
use crate::session;

/// What the root shows. Memoised as one value so the view rebuilds only when
/// the screen actually changes: the view used to read the session and the
/// route separately, so the stack reset on start re-ran it and mounted
/// LoginScreen a second time — two pairing codes, the first one shown and
/// then silently replaced, so approving it on a phone never signed in.
#[derive(Clone, PartialEq)]
enum Shown {
    Login,
    Route(Route),
}

#[component]
pub fn App() -> impl IntoView {
    let session = session::signal();
    let signed_in = Memo::new(move |_| session.with(|s| s.is_some()));
    let route = nav::current();
    let shown = Memo::new(move |_| {
        if signed_in.get() {
            Shown::Route(route.get())
        } else {
            Shown::Login
        }
    });

    // Signing out (or a dead refresh) starts the next session from Home.
    // Only on the transition: resetting on the first run is what re-rendered
    // the login screen.
    Effect::new(move |previous: Option<bool>| {
        let now = signed_in.get();
        if previous == Some(true) && !now {
            nav::reset();
        }
        now
    });

    view! {
        <div class="app">
            {move || match shown.get() {
                Shown::Login => view! { <LoginScreen/> }.into_any(),
                Shown::Route(Route::Home) => view! { <HomeScreen/> }.into_any(),
                Shown::Route(Route::Search) => view! { <SearchScreen/> }.into_any(),
                Shown::Route(Route::Detail(target)) => view! { <DetailScreen target=target/> }.into_any(),
                Shown::Route(Route::Player(target)) => view! { <PlayerScreen target=target/> }.into_any(),
            }}
            <ExitPrompt/>
        </div>
    }
}
