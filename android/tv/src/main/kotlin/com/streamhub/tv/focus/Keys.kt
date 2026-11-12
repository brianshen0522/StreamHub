package com.streamhub.tv.focus

import android.view.KeyEvent

/**
 * The remote's keys, as Tizen names them (`tizen/app/src/keys.rs`), plus the
 * keys Tizen passes through as `Other(code)` and the focus engine gives
 * meaning to: CH+/CH−, PgUp/PgDn (a whole row) and the red key (favourite).
 */
enum class TvKey {
    Up, Down, Left, Right,
    Enter,
    /** Remote Back. Repeats are never delivered (PARITY §5). */
    Back,
    PlayPause, Play, Pause, Stop, Rewind, FastForward, Previous, Next,
    /** Tizen 10182. Android has no such key; kept so the dispatch reads like focus.rs. */
    Exit,
    ChannelUp, ChannelDown, PageUp, PageDown,
    /** The red colour key (Tizen 403). */
    Red,
    Other;

    val isArrow: Boolean get() = this == Up || this == Down || this == Left || this == Right

    /** CH+/CH−/PgUp/PgDn: one row per keydown, repeats included, no pacing. */
    val isRowKey: Boolean get() = this == ChannelUp || this == ChannelDown || this == PageUp || this == PageDown
}

/** A keydown as a screen handler sees it. [keyCode] is the Android code. */
data class TvKeyPress(val key: TvKey, val repeat: Boolean, val keyCode: Int = 0)

/**
 * Android key codes → [TvKey] (PARITY §5).
 *
 * [inTextField]: Backspace (DEL) is Back and Space is Play/Pause only outside a
 * text field; inside one they are characters.
 */
object TvKeyMap {
    fun map(keyCode: Int, inTextField: Boolean = false): TvKey = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> TvKey.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> TvKey.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> TvKey.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> TvKey.Right
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A -> TvKey.Enter
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> TvKey.Back
        KeyEvent.KEYCODE_DEL -> if (inTextField) TvKey.Other else TvKey.Back
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> TvKey.PlayPause
        KeyEvent.KEYCODE_SPACE -> if (inTextField) TvKey.Other else TvKey.PlayPause
        KeyEvent.KEYCODE_MEDIA_PLAY -> TvKey.Play
        KeyEvent.KEYCODE_MEDIA_PAUSE -> TvKey.Pause
        KeyEvent.KEYCODE_MEDIA_STOP -> TvKey.Stop
        KeyEvent.KEYCODE_MEDIA_REWIND -> TvKey.Rewind
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> TvKey.FastForward
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> TvKey.Previous
        KeyEvent.KEYCODE_MEDIA_NEXT -> TvKey.Next
        KeyEvent.KEYCODE_CHANNEL_UP -> TvKey.ChannelUp
        KeyEvent.KEYCODE_CHANNEL_DOWN -> TvKey.ChannelDown
        KeyEvent.KEYCODE_PAGE_UP -> TvKey.PageUp
        KeyEvent.KEYCODE_PAGE_DOWN -> TvKey.PageDown
        KeyEvent.KEYCODE_PROG_RED -> TvKey.Red
        else -> TvKey.Other
    }
}
