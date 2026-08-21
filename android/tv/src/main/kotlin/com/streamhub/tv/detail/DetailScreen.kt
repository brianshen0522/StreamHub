package com.streamhub.tv.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamhub.core.model.Source
import com.streamhub.tv.PlaybackRequest
import com.streamhub.tv.ui.CentredMessage
import com.streamhub.tv.ui.SectionTitle
import com.streamhub.tv.ui.Tv
import com.streamhub.tv.ui.TvButton

/**
 * A title, ready to play.
 *
 * The whole screen is arranged around one press: Play is focused when the
 * screen opens and starts the best source of the episode the viewer would
 * resume, so the common case never touches the episode or source rows at all.
 * Those rows are there for when the default is wrong, not as a route to
 * playback.
 *
 * "Best" is the first source, which the view model has already ranked so that
 * streams whose ad breaks were recognised and cut come first.
 */
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    posterUrl: (String) -> String?,
    onPlay: (PlaybackRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playButton = remember { FocusRequester() }

    // Focus lands on Play as soon as there is something to play, not when the
    // screen appears — sources arrive one at a time over NDJSON, and a Play
    // button focused while it is still inert swallows the first press.
    val ready = state.sources.isNotEmpty()
    LaunchedEffect(ready) { if (ready) runCatching { playButton.requestFocus() } }

    if (state.loading) {
        CentredMessage("Loading…", modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    if (state.error != null) {
        CentredMessage(state.error!!, modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

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
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    state.posterUrl?.let(posterUrl)?.let { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(if (state.isMovie) "Film" else "Series")
                            state.selectedEpisode?.let { append("  ·  Episode $it") }
                            state.selectedSeason?.label?.takeIf { it.isNotBlank() }
                                ?.let { append("  ·  $it") }
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val best = state.sources.firstOrNull()
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TvButton(
                            label = when {
                                best != null -> "Play"
                                state.loadingSources -> "Finding sources…"
                                else -> "No playable source"
                            },
                            primary = true,
                            onClick = { best?.let { onPlay(viewModel.playbackFor(it)) } },
                            modifier = Modifier.focusRequester(playButton),
                        )
                    }
                    if (best != null) {
                        Text(
                            text = describe(best),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (best.adSeconds > 0) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.seasons.size > 1) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("Seasons")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    ) {
                        items(state.seasons, key = { it.url }) { season ->
                            TvButton(
                                label = season.label.ifBlank { "Season" },
                                primary = season.url == state.selectedSeason?.url,
                                onClick = { viewModel.selectSeason(season) },
                            )
                        }
                    }
                }
            }
        }

        if (state.episodes.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("Episodes")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    ) {
                        items(state.episodes, key = { it }) { episode ->
                            TvButton(
                                // A finished episode is marked rather than
                                // hidden: the list is also how someone finds
                                // one they want to watch again.
                                label = if (episode in state.completedEpisodes) "$episode ✓" else episode,
                                primary = episode == state.selectedEpisode,
                                onClick = { viewModel.selectEpisode(episode) },
                            )
                        }
                    }
                }
            }
        }

        if (state.sources.size > 1) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("Other sources")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    ) {
                        items(state.sources.drop(1), key = { it.url }) { source ->
                            TvButton(
                                label = "${source.sourceLabel}  ·  ${describe(source)}",
                                onClick = { onPlay(viewModel.playbackFor(source)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun describe(source: Source): String {
    val minutes = source.durationSeconds?.takeIf { it > 0 }?.let { "${it / 60} min" }
    val ads = source.adSeconds.takeIf { it > 0 }?.let { "${it}s of ads removed" }
    return listOfNotNull(minutes, ads).joinToString("  ·  ").ifBlank { source.sourceLabel }
}
