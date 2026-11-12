package com.streamhub.tv.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Up-next card: caption ("下一集 第02集") and whole seconds left. */
data class UpNextCard(val caption: String, val secondsLeft: Long)

/** The OK glyph: whether the result is playing, and a nonce (a new one restarts it). */
data class Flash(val playing: Boolean, val nonce: Long)

/** Per episode in the strip, once /me/progress answered. */
data class StripInfo(val fraction: Double, val completed: Boolean)

/**
 * The player's signals (`Ui`, `player/mod.rs:75-131`), as Compose state. The
 * controller writes them; the OSD reads them. Initial values are Tizen's:
 * buffering and the info bar on, notice 「尋找片源中…」.
 */
@Stable
class PlayerUi(startSeconds: Long) {
    var status by mutableStateOf(PlayerState.IDLE)
    var backend by mutableStateOf("")
    var buffering by mutableStateOf(true)
    var posS by mutableStateOf(startSeconds)
    var durS by mutableStateOf(0L)

    /** The button row (focusable). [osd] is the info bar alone, shown on seek. */
    var controls by mutableStateOf(false)
    var osd by mutableStateOf(true)
    var seekPreview by mutableStateOf<Long?>(null)
    var notice by mutableStateOf<String?>("尋找片源中…")
    var fatal by mutableStateOf<String?>(null)
    var upNext by mutableStateOf<UpNextCard?>(null)
    var hasNext by mutableStateOf(false)
    var picker by mutableStateOf(false)
    var sources by mutableStateOf<List<SourceRow>>(emptyList())
    var currentSource by mutableStateOf<Int?>(null)
    var sourceLabel by mutableStateOf("")
    var driver by mutableStateOf<String?>(null)

    /** Holding Left/Right (`data-scrub="1"`). */
    var scrub by mutableStateOf(false)
    var flash by mutableStateOf<Flash?>(null)

    /** The episode strip, with the focused index. */
    var strip by mutableStateOf<Int?>(null)
    var stripInfo by mutableStateOf<List<StripInfo>>(emptyList())

    val paused: Boolean get() = status == PlayerState.PAUSED
    val seeking: Boolean get() = seekPreview != null

    /** `busy`: buffering or a notice, and no error panel. */
    val busy: Boolean get() = (buffering || notice != null) && fatal == null

    /** The chrome shows while any of these holds (`mod.rs:1624-1628`). */
    val shown: Boolean get() = osd || controls || busy || paused

    /** Where the thumb, target layer and pill sit: the seek target, else the position. */
    val shownPos: Long get() = seekPreview ?: posS
}
