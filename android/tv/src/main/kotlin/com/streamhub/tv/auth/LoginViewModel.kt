package com.streamhub.tv.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamhub.core.model.DevicePairing
import com.streamhub.core.model.DevicePairingStatus
import com.streamhub.core.net.StreamHubException
import com.streamhub.tv.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Kept from the previous TV app for the placeholder login. The Login screen
// rewrite (PARITY §3.1) replaces its copy and error strings.

data class LoginUiState(
    val busy: Boolean = false,
    val error: String? = null,
    /** Null until the server has issued a code, or while one is being replaced. */
    val pairing: DevicePairing? = null,
    val pairingError: String? = null,
)

class LoginViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var pairingJob: Job? = null

    fun signIn(login: String, password: String, onSignedIn: () -> Unit) {
        if (login.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = "Enter a username and password.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                container.api.login(login.trim(), password)
                _state.update { it.copy(busy = false) }
                onSignedIn()
            } catch (error: StreamHubException) {
                _state.update { it.copy(busy = false, error = error.message) }
            } catch (error: Exception) {
                _state.update { it.copy(busy = false, error = "Could not reach the server.") }
            }
        }
    }

    /**
     * Keeps a usable code on screen for as long as this screen is open.
     *
     * One loop rather than a timer per code: get a code, poll it until it is
     * answered or runs out, and on anything other than approval go round and
     * get another. A television is left on this screen — somebody walks off to
     * find their phone — and a code that quietly stopped working while nobody
     * was looking is the whole failure mode this avoids.
     *
     * Cancelled with the ViewModel, which goes away the moment there is a
     * session, so nothing polls into a signed-in app.
     */
    fun startPairing(onSignedIn: () -> Unit) {
        if (pairingJob?.isActive == true) return
        pairingJob = viewModelScope.launch {
            while (isActive) {
                val pairing = try {
                    container.api.startDevicePairing()
                } catch (error: Exception) {
                    // Losing the server is not a reason to give up: the set may
                    // be on before the router is, so it says so and retries.
                    _state.update {
                        it.copy(pairing = null, pairingError = "Waiting for the server…")
                    }
                    delay(RETRY_DELAY_MS)
                    continue
                }

                _state.update { it.copy(pairing = pairing, pairingError = null) }

                val interval = pairing.intervalSeconds.coerceAtLeast(1) * 1000L
                val expiresAt = System.currentTimeMillis() + pairing.expiresInSeconds.coerceAtLeast(5) * 1000L
                var resolved = false
                while (isActive && !resolved) {
                    delay(interval)
                    // The code dies on schedule, so it is replaced on
                    // schedule: waiting for a poll to say "expired" leaves a
                    // dead QR on the screen for up to a full interval.
                    if (System.currentTimeMillis() >= expiresAt) {
                        resolved = true
                        continue
                    }
                    val status = try {
                        container.api.pollDevicePairing(pairing.deviceCode)
                    } catch (error: Exception) {
                        // A dropped poll is not an answer. Keep the code up and
                        // ask again rather than churning it on a blip.
                        continue
                    }
                    when (status) {
                        is DevicePairingStatus.Approved -> {
                            onSignedIn()
                            return@launch
                        }
                        // Both mean "this code is finished"; the outer loop
                        // issues another one.
                        DevicePairingStatus.Denied, DevicePairingStatus.Expired -> resolved = true
                        DevicePairingStatus.Pending -> Unit
                    }
                }
            }
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 5_000L
    }
}
