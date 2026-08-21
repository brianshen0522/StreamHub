package com.streamhub.mobile.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamhub.core.model.ItemDetail
import com.streamhub.core.model.RawStream
import com.streamhub.core.model.SeasonRef
import com.streamhub.core.model.Source
import com.streamhub.core.model.WatchProgress
import com.streamhub.core.net.StreamHubException
import com.streamhub.core.resume.ResumeRules
import com.streamhub.mobile.AppContainer
import com.streamhub.mobile.MediaSelection
import com.streamhub.mobile.PlaybackRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val posterUrl: String? = null,
    val isMovie: Boolean = false,
    val seasons: List<SeasonRef> = emptyList(),
    val selectedSeason: SeasonRef? = null,
    val episodes: List<String> = emptyList(),
    val loadingEpisodes: Boolean = false,
    val selectedEpisode: String? = null,
    val sources: List<Source> = emptyList(),
    val loadingSources: Boolean = false,
    val sourcesFinished: Boolean = false,
    /** Episodes already finished, so the list can say so. */
    val completedEpisodes: Set<String> = emptySet(),
)

class DetailViewModel(
    private val container: AppContainer,
    private val selection: MediaSelection,
) : ViewModel() {

    private val _state = MutableStateFlow(
        DetailUiState(title = selection.title, posterUrl = selection.posterUrl)
    )
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private var progress: Map<String, WatchProgress> = emptyMap()
    private var movieStreams: List<RawStream> = emptyList()
    private var sourcesJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                // Progress first: it decides which episode opens, and fetching it
                // after would mean opening the wrong one and correcting it.
                progress = ResumeRules.progressMap(
                    runCatching {
                        container.api().progress(selection.provider, selection.itemUrl)
                    }.getOrDefault(emptyList())
                )

                when (val detail = container.api().item(
                    provider = selection.provider,
                    url = selection.itemUrl,
                    title = selection.title,
                    mediaType = selection.mediaType,
                    posterUrl = selection.posterUrl,
                )) {
                    is ItemDetail.Seasons -> {
                        _state.update {
                            it.copy(
                                loading = false,
                                title = detail.title.ifBlank { it.title },
                                posterUrl = detail.posterUrl ?: it.posterUrl,
                                seasons = detail.seasons,
                            )
                        }
                        detail.seasons.firstOrNull()?.let { selectSeason(it) }
                    }

                    is ItemDetail.Episodes -> {
                        val resume = ResumeRules.resumeEpisode(detail.episodes, detail.sourceUrl, progress)
                        _state.update {
                            it.copy(
                                loading = false,
                                title = detail.title.ifBlank { it.title },
                                posterUrl = detail.posterUrl ?: it.posterUrl,
                                episodes = detail.episodes,
                                selectedSeason = detail.sourceUrl?.let { url -> SeasonRef("", url) },
                                completedEpisodes = completed(detail.episodes, detail.sourceUrl),
                            )
                        }
                        // A fully watched season resumes nowhere; open the first
                        // episode rather than leaving the screen inert.
                        selectEpisode(resume ?: detail.episodes.firstOrNull() ?: return@launch)
                    }

                    is ItemDetail.Movie -> {
                        movieStreams = detail.streams
                        _state.update {
                            it.copy(
                                loading = false,
                                isMovie = true,
                                title = detail.title.ifBlank { it.title },
                                posterUrl = detail.posterUrl ?: it.posterUrl,
                            )
                        }
                        loadMovieSources()
                    }
                }
            } catch (error: StreamHubException) {
                _state.update { it.copy(loading = false, error = error.message) }
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = "Could not reach the server.") }
            }
        }
    }

    fun selectSeason(season: SeasonRef) {
        _state.update {
            it.copy(
                selectedSeason = season,
                loadingEpisodes = true,
                episodes = emptyList(),
                selectedEpisode = null,
                sources = emptyList(),
            )
        }
        viewModelScope.launch {
            try {
                val episodes = container.api().episodes(selection.provider, season.url)
                _state.update {
                    it.copy(
                        loadingEpisodes = false,
                        episodes = episodes,
                        completedEpisodes = completed(episodes, season.url),
                    )
                }
                val resume = ResumeRules.resumeEpisode(episodes, season.url, progress)
                selectEpisode(resume ?: episodes.firstOrNull() ?: return@launch)
            } catch (error: Exception) {
                _state.update { it.copy(loadingEpisodes = false, error = "Could not load episodes.") }
            }
        }
    }

    fun selectEpisode(episode: String) {
        val season = _state.value.selectedSeason
        _state.update {
            it.copy(selectedEpisode = episode, sources = emptyList(), loadingSources = true, sourcesFinished = false)
        }

        sourcesJob?.cancel()
        sourcesJob = viewModelScope.launch {
            try {
                val preferred = runCatching {
                    container.api().sourcePreference(selection.provider, _state.value.title, selection.mediaType)
                }.getOrNull()

                container.api()
                    .sources(selection.provider, season?.url.orEmpty(), episode, preferred)
                    .collect { source ->
                        // Sources land one at a time as each health probe finishes,
                        // so they are appended rather than waited for.
                        _state.update { it.copy(sources = it.sources + source, loadingSources = false) }
                    }
                _state.update { it.copy(loadingSources = false, sourcesFinished = true) }
            } catch (error: Exception) {
                _state.update { it.copy(loadingSources = false, sourcesFinished = true) }
            }
        }
    }

    private fun loadMovieSources() {
        sourcesJob?.cancel()
        sourcesJob = viewModelScope.launch {
            _state.update { it.copy(loadingSources = true, sourcesFinished = false) }
            try {
                container.api().checkSources(selection.provider, movieStreams)
                    .collect { source ->
                        _state.update { it.copy(sources = it.sources + source, loadingSources = false) }
                    }
            } catch (error: Exception) {
                /* whatever arrived is still usable */
            }
            _state.update { it.copy(loadingSources = false, sourcesFinished = true) }
        }
    }

    /** Builds what the player needs, including where to pick up. */
    fun playbackFor(source: Source): PlaybackRequest {
        val current = _state.value
        val season = current.selectedSeason
        val episode = current.selectedEpisode
        val existing = progress[ResumeRules.progressKey(season?.url, episode)]

        return PlaybackRequest(
            providerKey = selection.provider,
            mediaType = if (current.isMovie) "movie" else "tv",
            title = current.title,
            posterUrl = current.posterUrl,
            itemUrl = selection.itemUrl,
            seasonUrl = season?.url,
            seasonLabel = season?.label?.takeIf { it.isNotBlank() },
            episodeLabel = episode,
            sourceLabel = source.sourceLabel,
            directUrl = source.directUrl,
            durationSeconds = source.durationSeconds,
            resumeAtSeconds = ResumeRules.resumePositionSeconds(existing),
        )
    }

    private fun completed(episodes: List<String>, seasonUrl: String?): Set<String> =
        episodes.filterTo(mutableSetOf()) { episode ->
            progress[ResumeRules.progressKey(seasonUrl, episode)]?.isCompleted == true
        }
}
