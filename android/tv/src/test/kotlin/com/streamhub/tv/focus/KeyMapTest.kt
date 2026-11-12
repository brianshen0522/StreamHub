package com.streamhub.tv.focus

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** PARITY §5. KeyEvent constants are compile-time ints, so no Android runtime is needed. */
class KeyMapTest {
    @Test
    fun arrowsOkAndBack() {
        assertEquals(TvKey.Up, TvKeyMap.map(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(TvKey.Down, TvKeyMap.map(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(TvKey.Left, TvKeyMap.map(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(TvKey.Right, TvKeyMap.map(KeyEvent.KEYCODE_DPAD_RIGHT))
        for (c in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)) {
            assertEquals(TvKey.Enter, TvKeyMap.map(c))
        }
        assertEquals(TvKey.Back, TvKeyMap.map(KeyEvent.KEYCODE_BACK))
        assertEquals(TvKey.Back, TvKeyMap.map(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun backspaceAndSpaceAreCharactersInsideATextField() {
        assertEquals(TvKey.Back, TvKeyMap.map(KeyEvent.KEYCODE_DEL))
        assertEquals(TvKey.Other, TvKeyMap.map(KeyEvent.KEYCODE_DEL, inTextField = true))
        assertEquals(TvKey.PlayPause, TvKeyMap.map(KeyEvent.KEYCODE_SPACE))
        assertEquals(TvKey.Other, TvKeyMap.map(KeyEvent.KEYCODE_SPACE, inTextField = true))
    }

    @Test
    fun mediaRowAndColourKeys() {
        assertEquals(TvKey.PlayPause, TvKeyMap.map(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(TvKey.PlayPause, TvKeyMap.map(KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(TvKey.Play, TvKeyMap.map(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(TvKey.Pause, TvKeyMap.map(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(TvKey.Stop, TvKeyMap.map(KeyEvent.KEYCODE_MEDIA_STOP))
        assertEquals(TvKey.Rewind, TvKeyMap.map(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(TvKey.FastForward, TvKeyMap.map(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(TvKey.Previous, TvKeyMap.map(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(TvKey.Next, TvKeyMap.map(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(TvKey.ChannelUp, TvKeyMap.map(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(TvKey.ChannelDown, TvKeyMap.map(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(TvKey.PageUp, TvKeyMap.map(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(TvKey.PageDown, TvKeyMap.map(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(TvKey.Red, TvKeyMap.map(KeyEvent.KEYCODE_PROG_RED))
        assertEquals(TvKey.Other, TvKeyMap.map(KeyEvent.KEYCODE_PROG_GREEN))
        assertEquals(TvKey.Other, TvKeyMap.map(KeyEvent.KEYCODE_0))
    }
}
