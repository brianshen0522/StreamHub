//! OWNED BY THE PLAYER AGENT: the /api/realtime socket — auth frame, close
//! codes (4002 → api::refresh() then reconnect), backoff, library-change
//! events, and the cast receiver (announce, publish state, obey commands).

/// Start the socket whenever a session exists; stop when it goes away.
pub fn start() {}
