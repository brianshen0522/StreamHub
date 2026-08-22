package com.streamhub.tv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.streamhub.core.model.Session
import com.streamhub.tv.auth.LoginScreen
import com.streamhub.tv.auth.LoginViewModel
import com.streamhub.tv.detail.DetailScreen
import com.streamhub.tv.detail.DetailViewModel
import com.streamhub.tv.home.HomeScreen
import com.streamhub.tv.home.HomeViewModel
import com.streamhub.tv.player.PlayerScreen
import com.streamhub.tv.player.PlayerViewModel
import com.streamhub.tv.search.SearchScreen
import com.streamhub.tv.search.SearchViewModel
import kotlinx.coroutines.launch

private const val ROUTE_HOME = "home"
private const val ROUTE_SEARCH = "search"
private const val ROUTE_DETAIL = "detail"
private const val ROUTE_PLAYER = "player"

@Composable
fun StreamHubTvApp(container: AppContainer) {
    var session by remember { mutableStateOf(container.sessionStore.load()) }

    // Posters sit behind auth, so the image loader has to use the client that
    // carries the bearer token; a plain fetch answers 401 and every row is grey.
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.api.authenticatedClient }))
            }
            .build()
    }

    // A session that cannot be renewed has to take the app back to sign-in.
    // Guarded on there being one: before the first sign-in, building an API
    // client would run against a store with nothing in it.
    LaunchedEffect(container, session != null) {
        if (session == null) return@LaunchedEffect
        container.api.sessionEnded.collect { session = null }
    }

    val current = session
    if (current == null) {
        LoginScreen(
            viewModel = viewModel { LoginViewModel(container) },
            onSignedIn = { session = container.sessionStore.load() },
        )
    } else {
        SignedIn(container, current) { session = null }
    }
}

@Composable
private fun SignedIn(container: AppContainer, session: Session, onSignedOut: () -> Unit) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    val posterUrl: (String) -> String? = { target ->
        target.takeIf { it.isNotBlank() }?.let { container.api.posterUrl(it) }
    }

    // A phone asking this television to play something. Handled here rather
    // than in the player, because the usual case is that no player exists yet —
    // the television may be sitting on the home screen, or have just been
    // switched on.
    val pendingPlay by container.castReceiver.pendingPlay.collectAsStateWithLifecycle()
    LaunchedEffect(pendingPlay) {
        val play = pendingPlay ?: return@LaunchedEffect
        container.castReceiver.consumePendingPlay()
        container.handover.playback = PlaybackRequest(
            providerKey = play.provider.orEmpty(),
            mediaType = "tv",
            title = play.title.orEmpty(),
            posterUrl = play.posterUrl,
            itemUrl = play.itemUrl.orEmpty(),
            seasonUrl = play.episodeUrl,
            seasonLabel = null,
            episodeLabel = play.episodeLabel,
            sourceLabel = play.subtitle.orEmpty(),
            directUrl = play.streamUrl,
            durationSeconds = null,
            resumeAtSeconds = (play.positionMs / 1000).toInt(),
            nextEpisodeLabel = play.nextEpisodeLabel,
            prevEpisodeLabel = play.prevEpisodeLabel,
        )
        // Replace whatever is playing rather than stacking a second player.
        navController.popBackStack(ROUTE_PLAYER, inclusive = true)
        navController.navigate(ROUTE_PLAYER)
    }

    NavHost(
        navController = navController,
        startDestination = ROUTE_HOME,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(ROUTE_HOME) {
            HomeScreen(
                viewModel = viewModel { HomeViewModel(container) },
                posterUrl = posterUrl,
                onOpen = { selection ->
                    container.handover.selection = selection
                    navController.navigate(ROUTE_DETAIL)
                },
                onSearch = { navController.navigate(ROUTE_SEARCH) },
                onSignOut = {
                    scope.launch {
                        runCatching { container.api.logout() }
                        onSignedOut()
                    }
                },
            )
        }

        composable(ROUTE_SEARCH) {
            SearchScreen(
                viewModel = viewModel { SearchViewModel(container) },
                posterUrl = posterUrl,
                onOpen = { selection ->
                    container.handover.selection = selection
                    navController.navigate(ROUTE_DETAIL)
                },
            )
        }

        composable(ROUTE_DETAIL) {
            val selection = container.handover.selection
            if (selection == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                val detailViewModel = viewModel { DetailViewModel(container, selection) }
                val detailState by detailViewModel.state.collectAsStateWithLifecycle()
                var startWhenReady by rememberSaveable { mutableStateOf(false) }

                // Coming back from the player having finished an episode —
                // or arriving on a screen built for a cast-started skip. On a
                // fresh screen the view model's own load ends by selecting the
                // resume episode, which would silently override anything chosen
                // before it finished; the pending choice waits the load out so
                // it is the last word.
                LaunchedEffect(Unit) {
                    container.handover.pendingEpisode?.let { episode ->
                        container.handover.pendingEpisode = null
                        snapshotFlow { detailState.loading }.first { !it }
                        detailViewModel.selectEpisode(episode)
                        startWhenReady = true
                    }
                }

                // Answering "play the next episode" by selecting it and
                // stopping there asks for a second press to do the thing that
                // was already asked for. It waits because the episode's sources
                // arrive one at a time over NDJSON.
                LaunchedEffect(startWhenReady, detailState.sources.firstOrNull()) {
                    if (!startWhenReady) return@LaunchedEffect
                    val best = detailState.sources.firstOrNull() ?: return@LaunchedEffect
                    startWhenReady = false
                    container.handover.playback = detailViewModel.playbackFor(best)
                    navController.navigate(ROUTE_PLAYER)
                }

                DetailScreen(
                    viewModel = detailViewModel,
                    posterUrl = posterUrl,
                    onPlay = { request ->
                        container.handover.playback = request
                        navController.navigate(ROUTE_PLAYER)
                    },
                )
            }
        }

        composable(ROUTE_PLAYER) {
            val request = container.handover.playback
            if (request == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                PlayerScreen(
                    container = container,
                    request = request,
                    viewModel = viewModel(key = request.directUrl) {
                        PlayerViewModel(container, request)
                    },
                    onNextEpisode = { episode ->
                        container.handover.pendingEpisode = episode
                        // Playback that a phone or browser cast here has no
                        // detail screen beneath it — popping lands on Home and
                        // the pending episode is never consumed, which read as
                        // "Next closed the player". Build the detail from the
                        // playing request instead, so the skip has somewhere
                        // to resolve its sources.
                        if (navController.previousBackStackEntry?.destination?.route == ROUTE_DETAIL) {
                            navController.popBackStack()
                        } else {
                            container.handover.selection = MediaSelection(
                                provider = request.providerKey,
                                itemUrl = request.itemUrl,
                                title = request.title,
                                mediaType = request.mediaType,
                                posterUrl = request.posterUrl,
                            )
                            navController.popBackStack(ROUTE_PLAYER, inclusive = true)
                            navController.navigate(ROUTE_DETAIL)
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
