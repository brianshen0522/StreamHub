package com.streamhub.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.streamhub.tv.focus.LocalTvFocus
import com.streamhub.tv.focus.TvFocusEngine
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvMotion
import com.streamhub.tv.ui.theme.TvShapes
import com.streamhub.tv.ui.theme.TvSpace
import com.streamhub.tv.ui.theme.TvText
import com.streamhub.tv.ui.theme.TvType
import com.streamhub.tv.ui.theme.TvZ
import kotlinx.coroutines.delay

/**
 * `.tv-screen`: every screen but the player enters with opacity 0 → 1 and
 * scale 1.015 → 1 over 420 ms `--ease`. Leaving is instant. The shell wraps
 * each route in it; a screen does not need to.
 */
@Composable
fun TvScreenEnter(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, TvMotion.slow()) }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = p.value
                val s = TvMotion.EnterScale + (1f - TvMotion.EnterScale) * p.value
                scaleX = s
                scaleY = s
            },
        content = content,
    )
}

/** One toast at a time (`toast()`, `screens/common.rs:231-253`). */
@Stable
class TvToastState {
    var message: String? by mutableStateOf(null)
        private set
    internal var serial by mutableLongStateOf(0L)
        private set

    /** Show [text], replacing any toast and restarting the 2.2 s timer. */
    fun show(text: String) {
        message = text
        serial += 1
    }

    fun hide() {
        message = null
    }
}

val LocalTvToast = staticCompositionLocalOf { TvToastState() }

/**
 * `.tv-toast`: bottom-centred pill at z120, enters with opacity and an 8 dp
 * rise over 240 ms, hides 2200 ms after the last [TvToastState.show], with no
 * exit animation.
 */
@Composable
fun TvToastHost(state: TvToastState) {
    val message = state.message ?: return
    val serial = state.serial
    val p = remember(serial) { Animatable(0f) }
    LaunchedEffect(serial) {
        p.animateTo(1f, TvMotion.focus())
    }
    LaunchedEffect(serial) {
        delay(TvMotion.ToastMs.toLong())
        state.hide()
    }
    Box(Modifier.fillMaxSize().zIndex(TvZ.Toast), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .padding(bottom = TvSpace.SafeY)
                .graphicsLayer {
                    alpha = p.value
                    translationY = (1f - p.value) * TvMotion.ToastRiseDistance.toPx()
                }
                .widthIn(max = 600.dp)
                .clip(TvShapes.Pill)
                .background(TvColors.Surface2)
                .border(0.5.dp, TvColors.ToastBorder, TvShapes.Pill)
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            TvText(message, TvType.Toast)
        }
    }
}

/**
 * "離開 StreamHub？" (`screens/exit.rs`): rendered after every screen, over
 * Login too. 取消 (autofocus) closes it and focus returns where it was; 離開
 * leaves the app. Back closes it (the engine does that).
 */
@Composable
fun TvExitPrompt(engine: TvFocusEngine = LocalTvFocus.current) {
    if (!engine.exitPromptOpen) return
    TvModal(title = "離開 StreamHub？", text = "看到一半的進度都已經存好了。") {
        TvButton(
            text = "取消",
            onClick = { engine.closeExitPrompt() },
            modifier = Modifier.widthIn(min = TvModalDefaults.ActionMinWidth),
            autofocus = true,
            action = "exit-cancel",
        )
        TvButton(
            text = "離開",
            onClick = { engine.exitApp() },
            modifier = Modifier.widthIn(min = TvModalDefaults.ActionMinWidth),
            style = TvButtonStyle.Danger,
            action = "exit-confirm",
        )
    }
}
