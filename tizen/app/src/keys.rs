//! Remote-control keys. Tizen delivers arrows/Enter/Back without registration;
//! everything else must be registered with `tizen.tvinputdevice` (done in
//! js/tizen-keys.js at startup). Desktop equivalents are mapped too so the
//! same build runs in Chromium for QA.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Key {
    Up,
    Down,
    Left,
    Right,
    Enter,
    /// Remote Back (10009). Desktop: Escape or Backspace.
    Back,
    PlayPause,
    Play,
    Pause,
    Stop,
    Rewind,
    FastForward,
    Previous,
    Next,
    /// Long-press Back / the Exit key (10182): leave the app, no questions.
    Exit,
    Other(u32),
}

impl Key {
    pub fn from_code(code: u32, key: &str) -> Key {
        match code {
            38 => Key::Up,
            40 => Key::Down,
            37 => Key::Left,
            39 => Key::Right,
            13 => Key::Enter,
            10009 | 27 => Key::Back,
            // Backspace is Back only outside text fields; the dispatcher checks.
            8 => Key::Back,
            10252 => Key::PlayPause,
            415 => Key::Play,
            19 => Key::Pause,
            413 => Key::Stop,
            412 => Key::Rewind,
            417 => Key::FastForward,
            10232 => Key::Previous,
            10233 => Key::Next,
            10182 => Key::Exit,
            _ => match key {
                " " | "MediaPlayPause" => Key::PlayPause,
                _ => Key::Other(code),
            },
        }
    }
}
