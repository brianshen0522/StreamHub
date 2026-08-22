package com.streamhub.tv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamhub.core.model.ContinueItem
import com.streamhub.core.model.Favorite
import com.streamhub.core.net.RealtimeEvent
import com.streamhub.tv.AppContainer
import com.streamhub.tv.MediaSelection
import com.streamhub.tv.ui.PosterCard
import com.streamhub.tv.ui.SectionTitle
import com.streamhub.tv.ui.Tv
import com.streamhub.tv.ui.TvButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val continueWatching: List<ContinueItem> = emptyList(),
    val favorites: List<Favorite> = emptyList(),
    val error: String? = null,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        load()
        // Finishing an episode on the phone should be visible here without
        // anyone reaching for the remote.
        viewModelScope.launch {
            container.realtimeEvents.collect { event ->
                when (event) {
                    is RealtimeEvent.Progress, is RealtimeEvent.Favorites -> load()
                    else -> Unit
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(error = null) }
            try {
                val continueWatching = container.api.continueWatching()
                val favorites = container.api.favorites()
                _state.update {
                    it.copy(
                        loading = false,
                        continueWatching = continueWatching,
                        favorites = favorites,
                    )
                }
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = "Could not reach the server.") }
            }
        }
    }
}

/**
 * The home screen: what you were watching, and what you saved.
 *
 * Rows rather than a grid, because a row is what a d-pad is good at — left and
 * right stay inside one idea, up and down change the idea. Continue watching
 * comes first because on a television it is what someone wants nine times out
 * of ten, and Search sits above both rather than inside them: hunting for the
 * search box inside a row of posters is the classic ten-foot mistake.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    posterUrl: (String) -> String?,
    onOpen: (MediaSelection) -> Unit,
    onSearch: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstAction = remember { FocusRequester() }

    // Signing out is one centre press from the row a remote lands on, and on a
    // television undoing it is not a matter of typing a password again — it
    // means pairing the set from another device. So it asks first, and the
    // answer it offers is "keep me signed in".
    var confirmingSignOut by remember { mutableStateOf(false) }
    val cancelSignOut = remember { FocusRequester() }

    // Something must be focused when the screen appears, or the first press of
    // any direction key goes nowhere and the remote feels broken.
    LaunchedEffect(Unit) { runCatching { firstAction.requestFocus() } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = Tv.OverscanH,
            end = Tv.OverscanH,
            top = Tv.OverscanV,
            bottom = Tv.OverscanV,
        ),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "StreamHub",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(end = 16.dp),
                )
                TvButton(
                    label = "Search",
                    primary = true,
                    onClick = onSearch,
                    modifier = Modifier.focusRequester(firstAction),
                )

                if (confirmingSignOut) {
                    Text(
                        "Sign out of this television?",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Focus lands on staying signed in, so a second reflexive
                    // press of the centre key cancels rather than confirms.
                    TvButton(
                        label = "Stay signed in",
                        onClick = { confirmingSignOut = false },
                        modifier = Modifier.focusRequester(cancelSignOut),
                    )
                    TvButton(label = "Sign out", onClick = onSignOut)
                    LaunchedEffect(Unit) { runCatching { cancelSignOut.requestFocus() } }
                } else {
                    TvButton(label = "Sign out", onClick = { confirmingSignOut = true })
                }
            }
        }

        if (state.error != null) {
            item {
                Text(
                    text = state.error!!,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (state.continueWatching.isNotEmpty()) {
            item {
                PosterRow(title = "Continue watching") {
                    items(state.continueWatching, key = { it.providerKey + it.itemUrl }) { entry ->
                        PosterCard(
                            title = entry.title,
                            posterUrl = entry.posterUrl?.let(posterUrl),
                            subtitle = entry.episodeLabel?.let { "Episode $it" },
                            progress = (entry.progressPercent / 100.0).toFloat(),
                            onClick = {
                                onOpen(
                                    MediaSelection(
                                        provider = entry.providerKey,
                                        itemUrl = entry.itemUrl,
                                        title = entry.title,
                                        mediaType = entry.mediaType,
                                        posterUrl = entry.posterUrl,
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }

        if (state.favorites.isNotEmpty()) {
            item {
                PosterRow(title = "Saved") {
                    items(state.favorites, key = { it.id }) { favorite ->
                        PosterCard(
                            title = favorite.title,
                            posterUrl = favorite.posterUrl?.let(posterUrl),
                            onClick = {
                                onOpen(
                                    MediaSelection(
                                        provider = favorite.providerKey,
                                        itemUrl = favorite.itemUrl,
                                        title = favorite.title,
                                        mediaType = favorite.mediaType,
                                        posterUrl = favorite.posterUrl,
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }

        if (!state.loading && state.continueWatching.isEmpty() && state.favorites.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Nothing here yet. Search to find something to watch.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterRow(
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(title)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Tv.RowGap),
            // A focused card grows, and without room to grow it is clipped by
            // the row's own bounds on the first and last item.
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            content = content,
        )
    }
}
