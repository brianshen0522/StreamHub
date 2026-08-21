package com.streamhub.mobile.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) {
    val hasRun: Boolean get() = submitted.isNotBlank()
    val totalItems: Int get() = results.sumOf { it.items.size }

    /** Providers that answered with a failure inside an otherwise fine response. */
    val failedProviders: List<ProviderResults> get() = results.filter { it.error != null }
}

class SearchViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return

        _state.update { it.copy(loading = true, error = null, submitted = query) }
        viewModelScope.launch {
            try {
                val response = container.api().search(query)
                // A provider that failed is reported inside a 200, so results and
                // failures arrive together and both are worth showing.
                _state.update { it.copy(loading = false, results = response.results) }
            } catch (error: StreamHubException) {
                _state.update { it.copy(loading = false, error = error.message, results = emptyList()) }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "Could not reach the server.",
                        results = emptyList(),
                    )
                }
            }
        }
    }

    fun retry() = search()
}
