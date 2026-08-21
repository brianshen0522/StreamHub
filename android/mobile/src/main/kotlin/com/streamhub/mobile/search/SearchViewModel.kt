package com.streamhub.mobile.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamhub.core.model.ProviderInfo
import com.streamhub.core.model.ProviderResults
import com.streamhub.core.net.StreamHubException
import com.streamhub.mobile.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val submitted: String = "",
    val loading: Boolean = false,
    val results: List<ProviderResults> = emptyList(),
    val error: String? = null,
    val available: List<ProviderInfo> = emptyList(),
    /** Empty means every source. Kept that way so a new provider is included by default. */
    val selected: Set<String> = emptySet(),
) {
    val hasRun: Boolean get() = submitted.isNotBlank()
    val totalItems: Int get() = results.sumOf { it.items.size }

    val allSelected: Boolean get() = selected.isEmpty() || selected.size == available.size

    val sourcesLabel: String
        get() = when {
            available.isEmpty() -> "Sources"
            allSelected -> "All sources"
            selected.size == 1 -> available.firstOrNull { it.key in selected }?.name ?: "1 source"
            else -> "${selected.size} of ${available.size} sources"
        }

    fun isSelected(key: String): Boolean = selected.isEmpty() || key in selected
}

class SearchViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val providers = runCatching { container.api.providers() }.getOrDefault(emptyList())
            _state.update { it.copy(available = providers) }
        }
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    /**
     * Turning the last source off would mean searching nothing, so the last one
     * standing cannot be removed — the way to widen it is to turn others back on.
     */
    fun toggleProvider(key: String) {
        _state.update { current ->
            val keys = current.available.map { it.key }
            val effective = if (current.selected.isEmpty()) keys.toSet() else current.selected
            val next = if (key in effective) effective - key else effective + key
            if (next.isEmpty()) current else current.copy(selected = next)
        }
        if (_state.value.hasRun) search()
    }

    fun selectAll() {
        _state.update { it.copy(selected = emptySet()) }
        if (_state.value.hasRun) search()
    }

    fun search() {
        val current = _state.value
        val query = current.query.trim()
        if (query.isEmpty()) return

        _state.update { it.copy(loading = true, error = null, submitted = query) }
        viewModelScope.launch {
            try {
                // The server narrows before scraping, so an excluded source is
                // never fetched and the search finishes sooner.
                val response = container.api.search(
                    query = query,
                    providers = if (current.allSelected) emptySet() else current.selected,
                )
                _state.update { it.copy(loading = false, results = response.results) }
            } catch (error: StreamHubException) {
                _state.update { it.copy(loading = false, error = error.message, results = emptyList()) }
            } catch (error: Exception) {
                _state.update {
                    it.copy(loading = false, error = "Could not reach the server.", results = emptyList())
                }
            }
        }
    }

    fun retry() = search()
}
