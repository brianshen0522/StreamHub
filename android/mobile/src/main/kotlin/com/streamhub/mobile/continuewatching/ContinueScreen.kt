package com.streamhub.mobile.continuewatching

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
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
import com.streamhub.core.model.ContinueItem
import com.streamhub.mobile.ui.EmptyState
import com.streamhub.mobile.ui.ErrorState
import com.streamhub.mobile.ui.LoadingState
import com.streamhub.mobile.ui.Poster

@Composable
fun ContinueScreen(
    viewModel: ContinueViewModel,
    posterUrl: (String) -> String?,
    onOpen: (ContinueItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when {
        state.loading -> LoadingState(modifier)
        state.error != null -> ErrorState(state.error!!, onRetry = viewModel::refresh, modifier = modifier)
        state.items.isEmpty() -> EmptyState(
            title = "Nothing in progress",
            detail = "Titles you start appear here, on every device signed in to this account.",
            modifier = modifier,
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            items(state.items, key = { "${it.providerKey}:${it.title}" }) { item ->
                ContinueCard(
                    item = item,
                    poster = posterUrl(item.posterUrl.orEmpty()),
                    onOpen = { onOpen(item) },
                    onDismiss = { viewModel.dismiss(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(
    item: ContinueItem,
    poster: String?,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            Poster(
                poster,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Remove from Continue") },
                    onClick = { menuOpen = false; onDismiss() },
                )
            }
        }

        // A finished episode means the next one is what plays, so the bar would
        // be showing progress through something already watched.
        if (!item.nextUp && item.progressPercent > 0) {
            LinearProgressIndicator(
                progress = { (item.progressPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.subtitle(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun ContinueItem.subtitle(): String {
    val episode = episodeLabel?.takeIf { it.isNotBlank() }
    return when {
        nextUp && episode != null -> "Up next after $episode"
        nextUp -> "Up next"
        episode != null -> "Resume $episode"
        durationSeconds > 0 -> "${((durationSeconds - positionSeconds) / 60).coerceAtLeast(0)} min left"
        else -> "Resume"
    }
}
