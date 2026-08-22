package com.streamhub.mobile.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamhub.core.model.Source
import com.streamhub.mobile.ui.ErrorState
import com.streamhub.mobile.ui.LoadingState
import com.streamhub.mobile.ui.Poster
import com.streamhub.mobile.ui.StreamHubColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    posterUrl: (String) -> String?,
    onBack: () -> Unit,
    onPlay: (Source) -> Unit,
    onDownload: (Source) -> Unit,
    /** The cast button, supplied by the host so this screen stays unaware of it. */
    castAction: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { castAction() },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            state.error != null -> ErrorState(state.error!!, viewModel::load, Modifier.padding(padding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Poster(
                            state.posterUrl?.let { posterUrl(it) },
                            modifier = Modifier.width(110.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(state.title, style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = if (state.isMovie) "Film" else "Series",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (state.seasons.isNotEmpty()) {
                    item { SectionTitle("Seasons") }
                    item {
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.seasons, key = { it.url }) { season ->
                                FilterChip(
                                    selected = state.selectedSeason?.url == season.url,
                                    onClick = { viewModel.selectSeason(season) },
                                    label = { Text(season.label) },
                                )
                            }
                        }
                    }
                }

                if (state.loadingEpisodes) {
                    item { InlineProgress("Loading episodes") }
                } else if (state.episodes.isNotEmpty()) {
                    item { SectionTitle("Episodes") }
                    item {
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.episodes, key = { it }) { episode ->
                                FilterChip(
                                    selected = state.selectedEpisode == episode,
                                    onClick = { viewModel.selectEpisode(episode) },
                                    label = { Text(episode) },
                                    leadingIcon = if (episode in state.completedEpisodes) {
                                        { Icon(Icons.Default.Check, contentDescription = "Watched") }
                                    } else null,
                                )
                            }
                        }
                    }
                }

                item { SectionTitle("Sources") }

                if (state.sources.isEmpty() && state.loadingSources) {
                    item { InlineProgress("Checking sources") }
                }

                items(state.sources, key = { it.sourceLabel + it.directUrl }) { source ->
                    ListItem(
                        headlineContent = { Text(source.sourceLabel) },
                        // Tapping the row plays; the trailing arrow saves. Both
                        // fetch the same cleaned playlist, so what is saved is
                        // exactly what would have played - ads already cut.
                        trailingContent = {
                            IconButton(onClick = { onDownload(source) }) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Download " + source.sourceLabel,
                                )
                            }
                        },
                        supportingContent = {
                            Text(
                                text = source.describe(),
                                // Green, not the accent: ads having been found and
                                // removed is a good outcome, and red on a detail
                                // line reads as a warning about the source rather
                                // than a point in its favour.
                                color = if (source.adSeconds > 0) {
                                    StreamHubColors.Green
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        modifier = Modifier.clickable { onPlay(source) },
                    )
                }

                if (state.sourcesFinished && state.sources.isEmpty()) {
                    item {
                        Text(
                            text = "No source for this episode passed its check.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else if (state.sources.isNotEmpty() && state.loadingSources) {
                    // More are still being probed; the list keeps growing.
                    item { InlineProgress("Still checking") }
                }
            }
        }
    }
}

/** Runtime already has detected ads subtracted, so it matches the timeline. */
private fun Source.describe(): String {
    val minutes = durationSeconds?.let { "${it / 60} min" }
    val ads = adSeconds.takeIf { it > 0 }?.let { "${it}s of ads removed" }
    return listOfNotNull(minutes, ads).joinToString(" · ").ifEmpty { "Ready" }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun InlineProgress(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
