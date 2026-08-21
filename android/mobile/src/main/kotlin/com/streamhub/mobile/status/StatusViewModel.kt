package com.streamhub.mobile.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamhub.core.model.ProviderInfo
import com.streamhub.mobile.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatusUiState(
    val checking: Boolean = true,
    val serverReachable: Boolean = false,
    val serverLatencyMs: Long? = null,
    val apiVersion: Int? = null,
    val serverError: String? = null,
    val providers: List<ProviderInfo> = emptyList(),
    val realtimeConnected: Boolean = false,
)

/**
 * Answers "where is the problem" rather than "is something wrong".
 *
 * The three things that can independently break are the network to the server,
 * the live-sync socket, and each scraped provider — and an empty search looks
 * the same for all of them. They are reported separately so they can be told
 * apart.
 */
class StatusViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(StatusUiState())
    val state: StateFlow<StatusUiState> = _state.asStateFlow()

    init {
        refresh()

        // Subscribe to the events themselves, and throw them away. The socket is
        // only opened while something is collecting, so without this the status
        // screen would report "not connected" whenever no other screen happened
        // to be listening — a check has to exercise what it reports on.
        viewModelScope.launch { container.realtimeEvents().collect { } }

        viewModelScope.launch {
            container.realtime().connected.collect { connected ->
                _state.update { it.copy(realtimeConnected = connected) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(checking = true, serverError = null) }

            val startedAt = System.currentTimeMillis()
            val health = runCatching { container.api().health() }
            val elapsed = System.currentTimeMillis() - startedAt

            if (health.isFailure) {
                _state.update {
                    it.copy(
                        checking = false,
                        serverReachable = false,
                        serverLatencyMs = null,
                        // Nothing else can be judged if the server is unreachable,
                        // so stale provider rows would be actively misleading.
                        providers = emptyList(),
                        serverError = "No response from ${container.settings.baseUrl}",
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    serverReachable = true,
                    serverLatencyMs = elapsed,
                    apiVersion = health.getOrNull()?.apiVersion,
                )
            }

            val providers = runCatching { container.api().providers(includeUnavailable = true) }
            _state.update {
                it.copy(
                    checking = false,
                    providers = providers.getOrDefault(emptyList()),
                    serverError = if (providers.isFailure) "Signed in, but the server refused the provider list." else null,
                )
            }
        }
    }
}
