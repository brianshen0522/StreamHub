//! OWNED BY THE UI AGENT: login (credentials + pairing QR), home rails,
//! search, detail, and the exit prompt.

mod detail;
mod exit;
mod home;
mod login;
mod search;

pub use detail::DetailScreen;
pub use exit::ExitPrompt;
pub use home::HomeScreen;
pub use login::LoginScreen;
pub use search::SearchScreen;
