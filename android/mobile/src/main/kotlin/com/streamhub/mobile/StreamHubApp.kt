package com.streamhub.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.streamhub.core.model.Session
import com.streamhub.mobile.auth.LoginScreen
import com.streamhub.mobile.auth.LoginViewModel
import com.streamhub.mobile.continuewatching.ContinueScreen
import com.streamhub.mobile.continuewatching.ContinueViewModel
import com.streamhub.mobile.detail.DetailScreen
import com.streamhub.mobile.detail.DetailViewModel
import com.streamhub.mobile.library.FavoritesScreen
import com.streamhub.mobile.library.FavoritesViewModel
import com.streamhub.mobile.library.HistoryScreen
import com.streamhub.mobile.library.HistoryViewModel
import com.streamhub.mobile.status.StatusSection
import com.streamhub.mobile.status.StatusViewModel
import com.streamhub.mobile.player.PlayerScreen
import com.streamhub.mobile.player.PlayerViewModel
import com.streamhub.mobile.profile.ProfileScreen
import com.streamhub.mobile.search.SearchScreen
import com.streamhub.mobile.search.SearchViewModel
import kotlinx.coroutines.launch

private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    SEARCH("search", "Search", Icons.Default.Search),
    CONTINUE("continue", "Continue", Icons.Default.PlayArrow),
    FAVORITES("favorites", "Saved", Icons.Default.Favorite),
    HISTORY("history", "History", Icons.Default.DateRange),
    PROFILE("profile", "Settings", Icons.Default.Settings),
}

private const val ROUTE_DETAIL = "detail"
private const val ROUTE_PLAYER = "player"

@Composable
fun StreamHubApp(container: AppContainer) {
    var session by remember { mutableStateOf(container.sessionStore.load()) }

    // Posters sit behind auth, so the image loader has to use the client that
    // carries the bearer token; a plain fetch answers 401 and the grid is empty.
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.api.authenticatedClient }))
            }
            .build()
    }

    // A session that cannot be renewed has to take the app back to sign-in.
    // Otherwise every screen keeps failing with a server message and a Try again
    // button that can never succeed.
    //
    // Guarded on there being a session: before the first sign-in there is no
    // server address either, and building an API client without one throws.
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
        SignedIn(
            container = container,
            session = current,
            onSignedOut = { session = null },
        )
    }
}

@Composable
private fun SignedIn(container: AppContainer, session: Session, onSignedOut: () -> Unit) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val scope = rememberCoroutineScope()

    val posterUrl: (String) -> String? = { target ->
        target.takeIf { it.isNotBlank() }?.let { container.api.posterUrl(it) }
    }

    // The detail screen and the player are full-screen; a tab bar under them
    // would be an escape hatch out of a video rather than navigation.
    val showTabs = Destination.entries.any { it.route == route }

    Scaffold(
        bottomBar = {
            if (!showTabs) return@Scaffold
            NavigationBar {
                for (destination in Destination.entries) {
                    NavigationBarItem(
                        selected = route == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Tapping a tab returns to it rather than stacking
                                // another copy, and keeps each tab's own state.
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.SEARCH.route,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Destination.SEARCH.route) {
                SearchScreen(
                    viewModel = viewModel { SearchViewModel(container) },
                    posterUrl = posterUrl,
                    onOpen = { item ->
                        container.handover.selection = MediaSelection(
                            provider = item.provider,
                            itemUrl = item.url,
                            title = item.title,
                            mediaType = item.mediaType,
                            posterUrl = item.posterUrl,
                        )
                        navController.navigate(ROUTE_DETAIL)
                    },
                )
            }
            composable(Destination.CONTINUE.route) {
                ContinueScreen(
                    viewModel = viewModel { ContinueViewModel(container) },
                    posterUrl = posterUrl,
                    onOpen = { item ->
                        container.handover.selection = MediaSelection(
                            provider = item.providerKey,
                            itemUrl = item.itemUrl,
                            title = item.title,
                            mediaType = item.mediaType,
                            posterUrl = item.posterUrl,
                        )
                        navController.navigate(ROUTE_DETAIL)
                    },
                )
            }
            composable(ROUTE_DETAIL) {
                val selection = container.handover.selection
                if (selection == null) {
                    // Only reachable if the process was killed on this screen.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    val detailViewModel = viewModel { DetailViewModel(container, selection) }

                    // Coming back from the player's next-episode button. The
                    // screen kept its state on the back stack, so this only has
                    // to move the selection.
                    LaunchedEffect(Unit) {
                        container.handover.pendingEpisode?.let { episode ->
                            container.handover.pendingEpisode = null
                            detailViewModel.selectEpisode(episode)
                        }
                    }

                    DetailScreen(
                        viewModel = detailViewModel,
                        posterUrl = posterUrl,
                        onBack = { navController.popBackStack() },
                        onPlay = { source ->
                            container.handover.playback = detailViewModel.playbackFor(source)
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
                        viewModel = viewModel { PlayerViewModel(container, request) },
                        onNextEpisode = { episode ->
                            container.handover.pendingEpisode = episode
                            navController.popBackStack()
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(Destination.FAVORITES.route) {
                FavoritesScreen(
                    viewModel = viewModel { FavoritesViewModel(container) },
                    posterUrl = posterUrl,
                    onOpen = { favorite ->
                        container.handover.selection = MediaSelection(
                            provider = favorite.providerKey,
                            itemUrl = favorite.itemUrl,
                            title = favorite.title,
                            mediaType = favorite.mediaType,
                            posterUrl = favorite.posterUrl,
                        )
                        navController.navigate(ROUTE_DETAIL)
                    },
                )
            }
            composable(Destination.HISTORY.route) {
                HistoryScreen(
                    viewModel = viewModel { HistoryViewModel(container) },
                    posterUrl = posterUrl,
                    onOpen = { entry ->
                        container.handover.selection = MediaSelection(
                            provider = entry.providerKey,
                            itemUrl = entry.itemUrl,
                            title = entry.title,
                            mediaType = entry.mediaType,
                            posterUrl = entry.posterUrl,
                        )
                        navController.navigate(ROUTE_DETAIL)
                    },
                )
            }
            composable(Destination.PROFILE.route) {
                ProfileScreen(
                    user = session.user,
                    serverUrl = container.serverUrl,
                    buildId = BuildConfig.GIT_SHA,
                    statusSection = {
                        StatusSection(viewModel = viewModel { StatusViewModel(container) })
                    },
                    onSignOut = {
                        scope.launch {
                            runCatching { container.api.logout() }
                            onSignedOut()
                        }
                    },
                )
            }
        }
    }
}
