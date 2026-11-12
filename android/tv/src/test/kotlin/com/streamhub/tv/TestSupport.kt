package com.streamhub.tv

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.streamhub.tv.sfx.Sound
import com.streamhub.tv.sfx.UiSounds
import java.io.File

/** Records what the engine asked to play. */
class RecordingSounds : UiSounds {
    val played = ArrayList<Pair<Sound, Boolean>>()
    override fun play(sound: Sound): Boolean {
        played.add(sound to false)
        return true
    }
    override fun playRepeat(sound: Sound): Boolean {
        played.add(sound to true)
        return true
    }
    fun last(): Sound? = played.lastOrNull()?.first
    fun clear() = played.clear()
}

/**
 * Noto Sans CJK from the build image (`fonts-noto-cjk`), so screenshots render
 * Chinese rather than tofu: Robolectric's own font set has no CJK. Falls back
 * to the default family where the files are missing.
 */
fun notoSansCjk(): FontFamily {
    val dir = File("/usr/share/fonts/opentype/noto")
    val regular = File(dir, "NotoSansCJK-Regular.ttc")
    val bold = File(dir, "NotoSansCJK-Bold.ttc")
    if (!regular.exists() || !bold.exists()) return FontFamily.Default
    return FontFamily(
        Font(regular, FontWeight.Normal),
        Font(regular, FontWeight.Medium),
        Font(bold, FontWeight.SemiBold),
        Font(bold, FontWeight.Bold),
        Font(bold, FontWeight.ExtraBold),
    )
}
