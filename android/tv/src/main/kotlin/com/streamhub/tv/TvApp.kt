package com.streamhub.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.streamhub.tv.focus.TvFocusEngine
import com.streamhub.tv.focus.TvFocusRoot
import com.streamhub.tv.nav.PlayTarget
import com.streamhub.tv.nav.TvNav
import com.streamhub.tv.nav.TvRoute
import com.streamhub.tv.screens.DetailPlaceholder
import com.streamhub.tv.screens.HomePlaceholder
import com.streamhub.tv.screens.LoginPlaceholder
import com.streamhub.tv.screens.PlayerPlaceholder
import com.streamhub.tv.screens.SearchPlaceholder
import com.streamhub.tv.sfx.SfxControl
import com.streamhub.tv.sfx.Sound
import com.streamhub.tv.ui.TvCanvas
import com.streamhub.tv.ui.components.LocalTvToast
import com.streamhub.tv.ui.components.TvExitPrompt
import com.streamhub.tv.ui.components.TvScreenEnter
import com.streamhub.tv.ui.components.TvToastHost
import com.streamhub.tv.ui.components.TvToastState
import kotlinx.coroutines.launch

/** The navigation stack, for screens. */
val LocalTvNav = staticCompositionLocalOf<TvNav> { error("No TvShell above this composable") }

/** The sounds and their switches, for screens (the 音效 toggle). */
val LocalTvSfx = staticCompositionLocalOf<SfxControl> { SfxControl.Silent() }

/**
 * The foundation's root, without any app data: the canvas, the focus root,
 * the locals screens read, the exit prompt over everything and the toast
 * host. [content] is the current screen. Tests and previews use this directly.
 */
@Composable
fun TvShell(
    engine: TvFocusEngine,
    nav: TvNav,
    sfx: SfxControl,
    toast: TvToastState = remember { TvToastState() },
    content: @Composable () -> Unit,
) {
    TvCanvas {
        TvFocusRoot(engine) {
            CompositionLocalProvider(LocalTvNav provides nav, LocalTvSfx provides sfx, LocalTvToast provides toast) {
                Box(Modifier.fillMaxSize()) {
                    content()
                    // After every screen, so it paints above a screen's own modals (app.rs:54).
                    TvExitPrompt(engine)
                    TvToastHost(toast)
                }
            }
        }
    }
}

/**
 * The app (`app.rs`): Login when signed out, otherwise the top of the nav
 * stack. Only the top route is mounted; each entry is keyed on its id, so
 * returning to a screen re-mounts it and route memory restores its focus.
 */
@Composable
fun TvApp(container: AppContainer, engine: TvFocusEngine, nav: TvNav, sfx: SfxControl) {
    var signedIn by remember { mutableStateOf(container.sessionStore.load() != null) }
    var sfxOn by remember { mutableStateOf(sfx.enabled) }
    val scope = rememberCoroutineScope()

    // Posters sit behind auth: the image loader uses the client with the bearer token.
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.api.authenticatedClient })) }
            .build()
    }

    // A session that cannot be renewed takes the app back to sign-in, and the
    // next session starts from Home (only on the transition — app.rs:37-43).
    LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        container.api.sessionEnded.collect {
            signedIn = false
            nav.reset()
        }
    }

    // A phone asking this television to play something: replace a playing
    // player rather than stacking a second one.
    val pendingPlay by container.castReceiver.pendingPlay.collectAsStateWithLifecycle()
    LaunchedEffect(pendingPlay, signedIn) {
        val play = pendingPlay ?: return@LaunchedEffect
        if (!signedIn) return@LaunchedEffect
        container.castReceiver.consumePendingPlay()
        val route = TvRoute.Player(
            PlayTarget(
                provider = play.provider.orEmpty(),
                itemUrl = play.itemUrl.orEmpty(),
                seasonUrl = play.episodeUrl.orEmpty(),
                title = play.title.orEmpty(),
                posterUrl = play.posterUrl.orEmpty(),
                episodeLabel = play.episodeLabel.orEmpty(),
                startSeconds = play.positionMs / 1000.0,
                fromCast = true,
            ),
        )
        if (nav.current is TvRoute.Player) nav.replace(route) else nav.push(route)
    }

    // Silent while the player is up (player/mod.rs:222-236).
    val playerUp = signedIn && nav.current is TvRoute.Player
    LaunchedEffect(playerUp) { sfx.setPlayer(playerUp) }

    TvShell(engine, nav, sfx) {
        if (!signedIn) {
            key("login") {
                TvScreenEnter { LoginPlaceholder(container) { signedIn = true } }
            }
        } else {
            val entry = nav.top
            key(entry.id) {
                when (val route = entry.route) {
                    TvRoute.Home -> TvScreenEnter {
                        HomePlaceholder(
                            container,
                            sfxOn = sfxOn,
                            onToggleSfx = {
                                sfxOn = sfx.setEnabled(!sfxOn)
                                if (sfxOn) sfx.play(Sound.Select) // home.rs:573-576
                            },
                            onOpen = nav::push,
                            onSignOut = {
                                scope.launch {
                                    runCatching { container.api.logout() }
                                    signedIn = false
                                    nav.reset()
                                }
                            },
                        )
                    }
                    TvRoute.Search -> TvScreenEnter { SearchPlaceholder(container, onOpen = nav::push) }
                    is TvRoute.Detail -> TvScreenEnter { DetailPlaceholder(route.target, onOpen = nav::push) }
                    // The player is not a .tv-screen: no enter animation.
                    is TvRoute.Player -> PlayerPlaceholder(route.target)
                }
            }
        }
    }
}
