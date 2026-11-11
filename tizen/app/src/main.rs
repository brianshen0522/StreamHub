mod api;
mod app;
mod config;
mod focus;
mod keys;
mod nav;
mod player;
mod realtime;
mod screens;
mod session;

fn main() {
    console_error_panic_hook_lite();
    focus::init();
    realtime::start();
    leptos::mount::mount_to_body(app::App);
}

/// Panics go to the console (visible in the TV's Web Inspector) instead of
/// vanishing into "unreachable".
fn console_error_panic_hook_lite() {
    std::panic::set_hook(Box::new(|info| {
        web_sys::console::error_1(&info.to_string().into());
    }));
}
