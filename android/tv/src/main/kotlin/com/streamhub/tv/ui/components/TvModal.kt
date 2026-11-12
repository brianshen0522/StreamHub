package com.streamhub.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.streamhub.tv.focus.LocalTvFocus
import com.streamhub.tv.focus.tvFocusScope
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvMotion
import com.streamhub.tv.ui.theme.TvShadows
import com.streamhub.tv.ui.theme.TvShapes
import com.streamhub.tv.ui.theme.TvText
import com.streamhub.tv.ui.theme.TvType
import com.streamhub.tv.ui.theme.TvZ
import com.streamhub.tv.ui.theme.tvShadow

object TvModalDefaults {
    /** `.tv-modal-actions .tv-btn { min-width: 240px }`. */
    val ActionMinWidth = 120.dp
}

/**
 * `.tv-modal-backdrop > .tv-modal` (PARITY §3.0): the backdrop fades in over
 * 240 ms, the panel rises 12 dp and fades in over 420 ms. The panel is a focus
 * scope: focus moves into it (its autofocus button, else the first) and is
 * confined there; when it leaves the composition focus returns to where it was.
 *
 * [menu] is the `.tv-menu` variant (380 dp, a one-line 20 sp title). Give each
 * action `Modifier.widthIn(min = TvModalDefaults.ActionMinWidth)`.
 */
@Composable
fun TvModal(
    title: String,
    text: String? = null,
    menu: Boolean = false,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    val engine = LocalTvFocus.current
    val fade = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, TvMotion.tween(TvMotion.T)) }
    LaunchedEffect(Unit) { rise.animateTo(1f, TvMotion.tween(TvMotion.TSlow)) }
    // Opening moves focus in on the next pass rather than waiting for the watchdog.
    LaunchedEffect(Unit) { engine.afterLayout { engine.ensureValid() } }

    TvModalBackdrop(modifier, alpha = { fade.value }) {
        Column(
            Modifier
                .graphicsLayer {
                    alpha = rise.value
                    translationY = (1f - rise.value) * TvMotion.RiseDistance.toPx()
                }
                .tvFocusScope()
                .width(if (menu) 380.dp else 430.dp)
                .tvShadow(TvShadows.Modal, TvShapes.RPanel)
                .clip(TvShapes.Panel)
                .background(TvColors.Surface2)
                .border(0.5.dp, TvColors.ModalBorder, TvShapes.Panel)
                .padding(start = 36.dp, end = 36.dp, top = 32.dp, bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TvText(
                title,
                if (menu) TvType.Title.copy(fontSize = 20.sp) else TvType.Title,
                maxLines = if (menu) 1 else 3,
                textAlign = TextAlign.Center,
            )
            if (text != null) {
                Spacer(Modifier.height(8.dp))
                TvText(text, TvType.ModalText, maxLines = 4, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(if (menu) 20.dp else 24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), content = actions)
        }
    }
}

/** The full-canvas `rgba(0,0,0,.6)` backdrop at z100, centring its content. */
@Composable
fun TvModalBackdrop(modifier: Modifier = Modifier, alpha: () -> Float = { 1f }, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .zIndex(TvZ.ModalBackdrop)
            .graphicsLayer { this.alpha = alpha() }
            .background(TvColors.ModalBackdrop),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
