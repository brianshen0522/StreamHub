package com.streamhub.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.streamhub.tv.focus.TvFocusTarget
import com.streamhub.tv.focus.TvFocusTransform
import com.streamhub.tv.focus.TvIntent
import com.streamhub.tv.focus.rememberTvFocusTarget
import com.streamhub.tv.focus.tvFocusable
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvMotion
import com.streamhub.tv.ui.theme.TvShadows
import com.streamhub.tv.ui.theme.TvShapes
import com.streamhub.tv.ui.theme.TvText
import com.streamhub.tv.ui.theme.TvType
import com.streamhub.tv.ui.theme.tvShadow

/** `.tv-btn` colour variants (`styles.css:142-182, 901-902`). */
enum class TvButtonStyle {
    /** `--surface-2` at rest. */
    Default,
    /** `.tv-btn-primary`: `--accent` at rest. */
    Primary,
    /** `.tv-btn-danger`: rgba(229,56,59,.18), text #ffb3ae. */
    Danger,
    /** `.tv-btn-text`: transparent, `--text-2`, 60px high, 26px text, no shadow. */
    Text,
}

/** `.tv-btn` (72px) or `.tv-btn-chip` (60px, padding 0 30px, 26px text). */
enum class TvButtonSize { Regular, Chip }

/**
 * The pill button `.tv-btn` (PARITY §3.0). Focused: white fill, `--on-focus`
 * text, scale 1.06 over 140 ms, and a pre-drawn shadow fading in over 240 ms.
 * [disabled] (`aria-disabled`) draws at .5 and is never focusable.
 *
 * Pass [target] to own the focus target yourself (to read `isFocused` outside,
 * or share it); otherwise the attribute parameters build one.
 * [content] replaces the default label row; it gets the current text colour.
 * [height], [paddingX] and [textStyle] override the variant's box and label
 * (the top bar's 64px pills: `.tv-topbar .tv-btn`).
 */
@Composable
fun TvButton(
    text: String?,
    onClick: (TvIntent) -> Unit,
    modifier: Modifier = Modifier,
    style: TvButtonStyle = TvButtonStyle.Default,
    size: TvButtonSize = TvButtonSize.Regular,
    wide: Boolean = false,
    focusId: String? = null,
    action: String? = null,
    group: String? = null,
    autofocus: Boolean = false,
    disabled: Boolean = false,
    selected: Boolean = false,
    longPress: Boolean = false,
    playable: Boolean = false,
    favable: Boolean = false,
    target: TvFocusTarget? = null,
    icon: (@Composable (Color) -> Unit)? = null,
    content: (@Composable RowScope.(Color) -> Unit)? = null,
    height: Dp? = null,
    paddingX: Dp? = null,
    textStyle: TextStyle? = null,
) {
    val own = rememberTvFocusTarget(
        focusId = focusId, action = action, group = group, autofocus = autofocus, longPress = longPress,
        playable = playable, favable = favable, selected = selected, disabled = disabled, onClick = onClick,
    )
    val t = target ?: own
    val focused = t.isFocused
    val shadowAlpha by animateFloatAsState(if (focused) 1f else 0f, TvMotion.tween(TvMotion.T), label = "btn-shadow")

    val small = size == TvButtonSize.Chip || style == TvButtonStyle.Text
    val h: Dp = height ?: if (small) 30.dp else 36.dp
    val padX: Dp = paddingX ?: if (size == TvButtonSize.Chip) 15.dp else 20.dp
    val bg = when {
        focused -> TvColors.Focus
        style == TvButtonStyle.Primary -> TvColors.Accent
        style == TvButtonStyle.Danger -> TvColors.DangerButtonBg
        style == TvButtonStyle.Text -> Color.Transparent
        else -> TvColors.Surface2
    }
    val fg = when {
        focused -> TvColors.OnFocus
        style == TvButtonStyle.Danger -> TvColors.DangerButtonText
        style == TvButtonStyle.Text -> TvColors.Text2
        else -> TvColors.Text
    }

    Row(
        modifier
            .zIndex(if (focused) 2f else 0f)
            .then(if (disabled) Modifier.alpha(0.5f) else Modifier)
            .tvFocusable(t, TvFocusTransform.Button)
            .then(if (style != TvButtonStyle.Text) Modifier.tvShadow(TvShadows.Button, 999.dp) { shadowAlpha } else Modifier)
            .then(if (wide) Modifier.fillMaxWidth() else Modifier)
            .height(h)
            .clip(TvShapes.Pill)
            .background(bg)
            .padding(horizontal = padX),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (content != null) {
            content(fg)
        } else {
            icon?.invoke(fg)
            if (text != null) TvText(text, textStyle ?: if (small) TvType.ButtonSmall else TvType.Button, color = fg)
        }
    }
}
