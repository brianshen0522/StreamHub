package com.streamhub.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.streamhub.mobile.profile.ProfileScreen
import com.streamhub.mobile.search.SearchScreen
import com.streamhub.mobile.search.SearchViewModel
import kotlinx.coroutines.launch

private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    SEARCH("search", "Search", Icons.Default.Search),
    CONTINUE("continue", "Continue", Icons.Default.PlayArrow),
    PROFILE("profile", "Settings", Icons.Default.Person),
}

@Composable
fun StreamHubApp(container: AppContainer) {
    var session by remember { mutableStateOf(container.sessionStore.load()) }

    // Posters sit behind auth, so the image loader has to use the client that
    // carries the bearer token; a plain fetch answers 401 and the grid is empty.
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.api().authenticatedClient }))
            }
            .build()
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
        target.takeIf { it.isNotBlank() }?.let { container.api().posterUrl(it) }
    }

    Scaffold(
        bottomBar = {
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
                    onOpen = { /* detail screen is the next piece of work */ },
                )
            }
            composable(Destination.CONTINUE.route) {
                ContinueScreen(
                    viewModel = viewModel { ContinueViewModel(container) },
                    posterUrl = posterUrl,
                    onOpen = { /* detail screen is the next piece of work */ },
                )
            }
            composable(Destination.PROFILE.route) {
                ProfileScreen(
                    user = session.user,
                    serverUrl = container.settings.baseUrl,
                    buildId = BuildConfig.GIT_SHA,
                    onSignOut = {
                        scope.launch {
                            runCatching { container.api().logout() }
                            onSignedOut()
                        }
                    },
                )
            }
        }
    }
}
