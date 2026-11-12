package com.streamhub.tv.ui.theme

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.isUnspecified
import kotlin.math.roundToInt
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The sans family every [TvText] uses. `FontFamily.Default` on a device (Noto
 * Sans CJK on Android TV); screenshot tests provide Noto Sans CJK TC loaded from
 * the build image so CJK never renders as tofu.
 */
val LocalTvFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

/**
 * Type tokens (PARITY §2.2). CSS px / 2 = sp; unitless line-heights become em.
 *
 * Every style centres the text in its line box and drops font padding, which is
 * how CSS lays out a line (half-leading above and below), so a 28px/1.45 CSS
 * line and a 14sp/1.45em line are the same height.
 */
object TvType {
    /** CSS weight 650. No system font has it; 600 is the documented substitute (PARITY §2.2). */
    val W650 = FontWeight(600)

    /** `font-variant-numeric: tabular-nums`. */
    const val TabularNums = "tnum"

    private val cssLine = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

    /** The body: 28px/1.45, weight 400 (`styles.css:63-64`). Every other style derives from it. */
    val Body = TextStyle(
        color = TvColors.Text,
        fontSize = 14.sp,
        lineHeight = 1.45.em,
        fontWeight = FontWeight.Normal,
        lineHeightStyle = cssLine,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    /** Display 72/1.05 w800. */
    val Display = Body.copy(fontSize = 36.sp, lineHeight = 1.05.em, fontWeight = FontWeight.ExtraBold)
    /** Title 44/1.15 w700 (modal title). */
    val Title = Body.copy(fontSize = 22.sp, lineHeight = 1.15.em, fontWeight = FontWeight.Bold)
    /** Row heading 32/1.2 w650. */
    val RowHeading = Body.copy(fontSize = 16.sp, lineHeight = 1.2.em, fontWeight = W650)
    /** Meta 24/1.3 w500. */
    val Meta = Body.copy(fontSize = 12.sp, lineHeight = 1.3.em, fontWeight = FontWeight.Medium)
    /** Caption 20px. Nothing is smaller. */
    val Caption = Body.copy(fontSize = 10.sp)

    /** `.tv-btn` 28px w650. */
    val Button = Body.copy(fontSize = 14.sp, fontWeight = W650)
    /** `.tv-btn-chip` / `.tv-btn-text` 26px. */
    val ButtonSmall = Button.copy(fontSize = 13.sp)
    /** `.tv-input` 30px. */
    val Input = Body.copy(fontSize = 15.sp)
    /** `.tv-field-label` 24px w500, --text-2. */
    val FieldLabel = Body.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TvColors.Text2)
    /** `.tv-modal-text` 26px --text-2. */
    val ModalText = Body.copy(fontSize = 13.sp, color = TvColors.Text2)
    /** `.tv-toast` 26px w600. */
    val Toast = Body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    /** `.tv-badge` / `.tv-chip` 20px w700. */
    val Badge = Body.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold)
    /** `.tv-card-title` 24px/1.3 w600 --text-2. */
    val CardTitle = Body.copy(fontSize = 12.sp, lineHeight = 1.3.em, fontWeight = FontWeight.SemiBold, color = TvColors.Text2)
    /** `.tv-card-sub` 20px/1.4 --text-3. */
    val CardSub = Body.copy(fontSize = 10.sp, lineHeight = 1.4.em, color = TvColors.Text3)
    /** `.tv-row-note` 22px w500 --text-3 tnum. */
    val RowNote = Body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TvColors.Text3, fontFeatureSettings = TabularNums)
    /** `.tv-row-caption` 24px w500 --text-3. */
    val RowCaption = Body.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TvColors.Text3)
    /** `.tv-error` 24px --danger. */
    val Error = Body.copy(fontSize = 12.sp, color = TvColors.Danger)
    /** `.tv-brand` 40px w800 -0.5px, accent. */
    val Brand = Body.copy(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.25).sp, color = TvColors.Accent)
}

/**
 * The one text primitive: [BasicText] with the [LocalTvFontFamily] merged in.
 * Defaults to one line with an ellipsis, which is what nearly every TV label is.
 */
@Composable
fun TvText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign? = null,
    softWrap: Boolean = maxLines > 1,
) {
    var merged = style.copy(fontFamily = style.fontFamily ?: LocalTvFontFamily.current)
    if (color != Color.Unspecified) merged = merged.copy(color = color)
    if (textAlign != null) merged = merged.copy(textAlign = textAlign)
    val box = remember { LineBox() }
    BasicText(
        text, modifier.cssLineBox(box, merged), merged,
        onTextLayout = { box.lines = it.lineCount }, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
    )
}

/** [TvText] for a lambda colour (an animated one), read at draw time only. */
@Composable
fun TvText(
    text: String,
    style: TextStyle,
    color: () -> Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val merged = style.copy(fontFamily = style.fontFamily ?: LocalTvFontFamily.current)
    val box = remember { LineBox() }
    BasicText(
        text, modifier.cssLineBox(box, merged), merged, onTextLayout = { box.lines = it.lineCount },
        overflow = overflow, softWrap = maxLines > 1, maxLines = maxLines, color = ColorProducer { color() },
    )
}

/** The line count of the last layout, written by `onTextLayout` during measure. */
private class LineBox {
    var lines = 1
}

/**
 * A CSS line box. Android never makes a line shorter than the font's own
 * ascent + descent (Noto Sans CJK is 1.448em; with fallback fonts the
 * tallest one wins), so `line-height: 1.15` on a title lays out at ~1.45.
 * CSS instead centres the content area in the shorter box (negative
 * half-leading). This reports `lines × line-height` as the height and
 * centres the text in it, drawing outside its box exactly as CSS overflows.
 */
private fun Modifier.cssLineBox(box: LineBox, style: TextStyle): Modifier {
    val lh = style.lineHeight
    if (lh.isUnspecified) return this
    return this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val linePx = if (lh.isEm) lh.value * style.fontSize.toPx() else lh.toPx()
        val target = (box.lines.coerceAtLeast(1) * linePx).roundToInt()
        if (placeable.height <= target) {
            val h = placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(placeable.width, h) { placeable.place(0, 0) }
        } else {
            val h = target.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(placeable.width, h) { placeable.place(0, (h - placeable.height) / 2) }
        }
    }
}
