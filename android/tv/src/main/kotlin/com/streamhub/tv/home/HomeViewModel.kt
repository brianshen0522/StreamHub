package com.streamhub.tv.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamhub.core.model.ContinueItem
import com.streamhub.core.model.Favorite
import com.streamhub.core.net.RealtimeEvent
import com.streamhub.tv.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Kept from the previous TV app for the placeholder Home. The Home screen
// rewrite (PARITY §3.2) replaces it with three rails and their retry rules.

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
