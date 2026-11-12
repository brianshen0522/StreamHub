package com.streamhub.tv.player

/**
 * The shim the controller drives (`window.StreamHubPlayer`,
 * `tizen/app/js/player-shim.js`; `player/avplay.rs`). States are
 * `idle | loading | playing | paused | ended | error`.
 *
 * Contract, as the shim has it:
 * - every event is fenced by an [open] generation: an event from a source that
 *   was replaced never reaches the listener;
 * - [play] / [pause] during `loading` only record the intent, and the source
 *   then comes up `paused` rather than playing over the viewer's press;
 * - [seek] reports buffering true, then false once the seek lands;
 * - [durationMs] is 0 until known.
 */
interface PlaybackEngine {
    interface Listener {
        fun onState(state: String)
        fun onTime(ms: Double)
        fun onBuffering(buffering: Boolean)
        fun onEnded()
        fun onError(message: String)
    }

    fun setListener(listener: Listener?)
    fun open(url: String, startMs: Double)
    fun play()
    fun pause()
    fun seek(ms: Double)
    fun close()
    fun positionMs(): Double
    fun durationMs(): Double
    fun backendName(): String

    /** The app went to the background (`avplay.suspend()`): stop without a state change. */
    fun suspend()

    /** Back in the foreground (`avplay.restore()`): carry on as before. */
    fun restore()

    /** Free the decoder for good (the screen is gone). */
    fun release()
}

object PlayerState {
    const val IDLE = "idle"
    const val LOADING = "loading"
    const val PLAYING = "playing"
    const val PAUSED = "paused"
    const val ENDED = "ended"
    const val ERROR = "error"
}
