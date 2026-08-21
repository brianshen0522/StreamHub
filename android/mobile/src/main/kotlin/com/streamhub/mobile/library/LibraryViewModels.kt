package com.streamhub.mobile.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamhub.core.model.Favorite
import com.streamhub.core.model.WatchHistoryEntry
import com.streamhub.core.net.RealtimeEvent
import com.streamhub.core.net.StreamHubException
import com.streamhub.mobile.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FavoritesUiState(
    val loading: Boolean = true,
    val items: List<Favorite> = emptyList(),
    val error: String? = null,
)

class FavoritesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            // Favouriting on the web or the TV should show up here without a
            // manual reload; the event says only that something changed.
            container.realtimeEvents.collect { event ->
                if (event is RealtimeEvent.Favorites) refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty(), error = null) }
            try {
                _state.update { it.copy(loading = false, items = container.api.favorites()) }
            } catch (error: StreamHubException) {
                _state.update { it.copy(loading = false, error = error.message) }
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = "Could not reach the server.") }
            }
        }
    }

    fun remove(favorite: Favorite) {
        viewModelScope.launch {
            _state.update { current -> current.copy(items = current.items.filterNot { it.id == favorite.id }) }
            runCatching { container.api.removeFavorite(favorite.id) }.onFailure { refresh() }
        }
    }
}

data class HistoryUiState(
    val loading: Boolean = true,
    val items: List<WatchHistoryEntry> = emptyList(),
    val error: String? = null,
)

class HistoryViewModel(private val container: AppContainer) : ViewModel() {

    /**
     * One row per title, the most recent one.
     *
     * The server keeps history as an append-only log, so watching a series
     * produces an entry per session and per episode. Listing those raw turns a
     * handful of shows into a wall of near-identical lines. The web client
     * groups by title for the same reason; this matches it, so a viewer sees the
     * same list on both.
     */
    private fun collapse(entries: List<WatchHistoryEntry>): List<WatchHistoryEntry> =
        entries.distinctBy { "${it.providerKey}::${it.title}" }


    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            container.realtimeEvents.collect { event ->
                // Only some progress events append history, and the server says
                // which — refetching on every tick would be wasted work.
                if (event is RealtimeEvent.Progress && event.historyChanged) refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty(), error = null) }
            try {
                _state.update { it.copy(loading = false, items = collapse(container.api.history())) }
            } catch (error: StreamHubException) {
                _state.update { it.copy(loading = false, error = error.message) }
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = "Could not reach the server.") }
            }
        }
    }
}
