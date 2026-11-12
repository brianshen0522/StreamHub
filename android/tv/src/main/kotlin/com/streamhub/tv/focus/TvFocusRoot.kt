package com.streamhub.tv.focus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/** The engine, for any composable under [TvFocusRoot]. */
val LocalTvFocus = staticCompositionLocalOf<TvFocusEngine> { error("No TvFocusRoot above this composable") }

/**
 * The engine's root: everything the engine can focus must be inside it. Put it
 * directly inside [com.streamhub.tv.ui.TvCanvas], so its coordinates are the
 * canvas's. Starts the 200 ms ensure-valid watchdog.
 */
@Composable
fun TvFocusRoot(engine: TvFocusEngine, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val rootFocus = remember { FocusRequester() }
    DisposableEffect(engine, scope) {
        engine.attach(scope, rootFocus)
        onDispose { engine.detach(scope) }
    }
    LaunchedEffect(engine) { engine.tick() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    CompositionLocalProvider(LocalTvFocus provides engine) {
        Box(
            modifier
                .fillMaxSize()
                .then(FocusRootElement(engine))
                .focusRequester(rootFocus)
                .focusable(),
        ) {
            content()
        }
    }
}

/**
 * Install a screen key handler for as long as this composable is in the
 * composition (`set_screen_handler` + the `screen_alive` guard). [onKey] gets
 * every keydown first, Back included; return true to consume it. [onKeyUp]
 * gets every keyup. The latest lambdas are always used.
 */
@Composable
fun TvScreenKeys(
    onKeyUp: (TvKey, Int) -> Unit = { _, _ -> },
    onKey: (TvKeyPress) -> Boolean,
) {
    val engine = LocalTvFocus.current
    val down by rememberUpdatedState(onKey)
    val up by rememberUpdatedState(onKeyUp)
    DisposableEffect(engine) {
        val h = object : TvKeyHandler {
            override fun onKey(press: TvKeyPress) = down(press)
            override fun onKeyUp(key: TvKey, keyCode: Int) = up(key, keyCode)
        }
        engine.setScreenHandler(h)
        onDispose { engine.clearScreenHandler(h) }
    }
}
