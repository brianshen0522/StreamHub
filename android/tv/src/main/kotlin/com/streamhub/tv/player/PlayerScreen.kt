package com.streamhub.tv.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamhub.tv.AppContainer
import com.streamhub.tv.cast.CastHub
import kotlinx.coroutines.CoroutineScope
import com.streamhub.tv.LocalTvNav
import com.streamhub.tv.focus.LocalTvFocus
import com.streamhub.tv.focus.TvFocusEngine
import com.streamhub.tv.focus.TvScreenKeys
import com.streamhub.tv.focus.TvSelect
import com.streamhub.tv.focus.TvSelector
import com.streamhub.tv.nav.PlayTarget
import com.streamhub.tv.nav.TvRoute

/**
 * The player route (`PlayerScreen`, `player/mod.rs:1603`). Not a
 * `.tv-screen`: no enter animation.
 *
 * The video is a `PlayerView` (a SurfaceView, letterboxed) filling the canvas
 * — which on a 16:9 panel is the whole screen, exactly where Tizen's
 * 1920×1080 AVPlay rectangle sits. The OSD is drawn over it.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(container: AppContainer, target: PlayTarget) {
    val context = LocalContext.current
    val exo = remember { ExoEngine(context, container.api.authenticatedClient) }
    PlayerLayer(
        target = target,
        engine = exo,
        data = container.playerData,
        cast = container.castReceiver,
        background = container.backgroundScope,
        clock = { SystemClock.uptimeMillis() },
        onTeardown = { exo.release() },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    player = exo.player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Everything of the player route but the video: the controller on the nav
 * stack and the focus engine, its lifecycle (mount/teardown, ON_STOP/ON_START),
 * keep-screen-on, the keys and the OSD over [video]. Behaviour tests mount
 * this with a fake engine.
 */
@Composable
fun PlayerLayer(
    target: PlayTarget,
    engine: PlaybackEngine,
    data: PlayerData,
    cast: CastHub,
    background: CoroutineScope,
    clock: () -> Long,
    onTeardown: () -> Unit = {},
    video: @Composable () -> Unit,
) {
    val nav = LocalTvNav.current
    val focusEngine = LocalTvFocus.current
    val scope = rememberCoroutineScope()

    val ctl = remember {
        PlayerController(
            target = target,
            engine = engine,
            data = data,
            cast = cast,
            nav = object : PlayerNav {
                override fun replace(target: PlayTarget) = nav.replace(TvRoute.Player(target))
                override fun back() {
                    nav.back()
                }
            },
            focus = PlayerFocus { focusPlayer(focusEngine, it) },
            scope = scope,
            background = background,
            clock = clock,
        )
    }

    DisposableEffect(ctl) {
        ctl.mount()
        onDispose {
            ctl.teardown()
            onTeardown()
        }
    }

    // `visibilitychange`: hidden → report a pause and suspend; shown → restore.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, ctl) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> ctl.onHidden()
                Lifecycle.Event.ON_START -> ctl.onShown()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Keep the panel awake while something is meant to be playing.
    val view = LocalView.current
    val awake = ctl.ui.fatal == null && ctl.ui.status != PlayerState.PAUSED && ctl.ui.status != PlayerState.ENDED
    DisposableEffect(view, awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    TvScreenKeys(onKeyUp = { key, _ -> ctl.onKeyUp(key) }) { press -> ctl.onKey(press) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        video()
        PlayerOsd(ctl.ui, target, ControllerActions(ctl))
    }
}

/** The OSD's clicks, onto the controller (`act(...)` in mod.rs). */
class ControllerActions(private val ctl: PlayerController) : PlayerActions {
    override fun toggle() = ctl.toggle()
    override fun back10() = ctl.nudge(-PlayerRules.SEEK_STEP_MS)
    override fun fwd10() = ctl.nudge(PlayerRules.SEEK_STEP_MS)
    override fun nextEpisode() = ctl.goNext()
    override fun sources() = ctl.openPicker()
    override fun selectSource(index: Int) = ctl.selectSource(index)
    override fun retry() = ctl.retry()
    override fun back() = ctl.leave()
}

/** `focus_later`'s selectors, once the layer has laid out. */
fun focusPlayer(engine: TvFocusEngine, which: PlayerFocusTarget) {
    engine.afterLayout {
        when (which) {
            PlayerFocusTarget.Toggle -> engine.focusFirst(TvSelect.action("toggle"))
            PlayerFocusTarget.PickerCurrent ->
                engine.focusFirst(TvSelector { it.action == "source" && it.selected }) ||
                    engine.focusFirst(TvSelect.action("source"))
            PlayerFocusTarget.Retry -> engine.focusFirst(TvSelect.action("retry"))
        }
    }
}
