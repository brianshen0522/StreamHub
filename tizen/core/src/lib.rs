//! Logic the Tizen client shares, in spirit, with android/core and
//! ios/StreamHub/Core. It cannot share their code — Kotlin, Swift and Rust —
//! so each module names the file it mirrors, and its tests are ported from
//! that file's tests. Change one and the others must follow (see CLAUDE.md,
//! "Resume semantics now exist three times").

pub mod entry;
pub mod models;
pub mod ndjson;
pub mod resume;
