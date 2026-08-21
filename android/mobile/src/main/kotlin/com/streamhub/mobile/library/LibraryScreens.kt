package com.streamhub.mobile.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamhub.core.model.Favorite
import com.streamhub.core.model.WatchHistoryEntry
import com.streamhub.mobile.ui.EmptyState
import com.streamhub.mobile.ui.ErrorState
import com.streamhub.mobile.ui.LoadingState
import com.streamhub.mobile.ui.Poster

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel,
    posterUrl: (String) -> String?,
    onOpen: (Favorite) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when {
        state.loading -> LoadingState(modifier)
        state.error != null -> ErrorState(state.error!!, viewModel::refresh, modifier)
        state.items.isEmpty() -> EmptyState(
            title = "No favourites yet",
            detail = "Anything you save appears here, on every device signed in to this account.",
            modifier = modifier,
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            items(state.items, key = { it.id }) { favorite ->
                var menuOpen by remember { mutableStateOf(false) }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box {
                        Poster(
                            favorite.posterUrl?.let { posterUrl(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onOpen(favorite) },
                                    onLongClick = { menuOpen = true },
                                ),
                        )
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Remove") },
                                onClick = { menuOpen = false; viewModel.remove(favorite) },
                            )
                        }
                    }
                    Text(
                        text = favorite.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    favorite.episodeLabel?.takeIf { it.isNotBlank() }?.let { episode ->
                        Text(
                            text = episode,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    posterUrl: (String) -> String?,
    onOpen: (WatchHistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when {
        state.loading -> LoadingState(modifier)
        state.error != null -> ErrorState(state.error!!, viewModel::refresh, modifier)
        state.items.isEmpty() -> EmptyState(
            title = "Nothing watched yet",
            detail = "Episodes and films you watch are listed here, newest first.",
            modifier = modifier,
        )
        else -> LazyColumn(modifier = modifier.fillMaxSize()) {
            items(state.items, key = { it.id }) { entry ->
                ListItem(
                    leadingContent = {
                        Poster(
                            entry.posterUrl?.let { posterUrl(it) },
                            modifier = Modifier.width(48.dp),
                        )
                    },
                    headlineContent = {
                        Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = { Text(entry.describe()) },
                    modifier = Modifier.clickable { onOpen(entry) },
                )
            }
        }
    }
}

private fun WatchHistoryEntry.describe(): String {
    val episode = episodeLabel?.takeIf { it.isNotBlank() }
    val watched = if (durationSeconds > 0) {
        "${(positionSeconds / 60)} of ${durationSeconds / 60} min"
    } else {
        null
    }
    return listOfNotNull(episode, watched, sourceLabel?.takeIf { it.isNotBlank() })
        .joinToString(" · ")
        .ifEmpty { providerKey }
}
