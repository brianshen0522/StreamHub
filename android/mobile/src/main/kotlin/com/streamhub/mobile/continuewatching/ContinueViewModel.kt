package com.streamhub.mobile.continuewatching

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamhub.core.model.ContinueItem
import com.streamhub.core.net.RealtimeEvent
import com.streamhub.core.net.StreamHubException
import com.streamhub.mobile.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContinueUiState(
    val loading: Boolean = true,
    val items: List<ContinueItem> = emptyList(),
    val error: String? = null,
)

class ContinueViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ContinueUiState())
    val state: StateFlow<ContinueUiState> = _state.asStateFlow()

    init {
        refresh()
        watchForChanges()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty(), error = null) }
            try {
                val items = container.api().continueWatching()
                _state.update { it.copy(loading = false, items = items) }
            } catch (error: StreamHubException) {
                _state.update { it.copy(loading = false, error = error.message) }
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = "Could not reach the server.") }
            }
        }
    }

    /**
     * Events say only that something changed, so the shelf is refetched rather
     * than patched. That is what keeps this device in step with the web app and
     * the TV without either having to reason about ordering.
     */
    private fun watchForChanges() {
        viewModelScope.launch {
            container.realtime().events().collect { event ->
                if (event is RealtimeEvent.Progress) refresh()
            }
        }
    }

    fun dismiss(item: ContinueItem) {
        viewModelScope.launch {
            // Remove locally first — the card is gone from the user's point of
            // view the moment they dismiss it, and the realtime event will
            // reconcile anything that goes wrong.
            _state.update { current -> current.copy(items = current.items.filterNot { it.sameTitle(item) }) }
            runCatching {
                container.api().deleteProgress(
                    com.streamhub.core.model.ProgressDelete(
                        providerKey = item.providerKey,
                        // "title" scope deletes the whole show and ignores itemUrl,
                        // which is what a card stands for — some providers give
                        // every episode its own itemUrl.
                        scope = "title",
                        title = item.title,
                    )
                )
            }.onFailure { refresh() }
        }
    }

    private fun ContinueItem.sameTitle(other: ContinueItem) =
        providerKey == other.providerKey && title == other.title
}
