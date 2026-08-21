package com.streamhub.mobile.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamhub.core.net.StreamHubException
import com.streamhub.mobile.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val serverUrl: String = "",
    val login: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() = !submitting && serverUrl.isNotBlank() && login.isNotBlank() && password.isNotBlank()
}

class LoginViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState(serverUrl = container.settings.baseUrl))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onServerUrlChange(value: String) = _state.update { it.copy(serverUrl = value, error = null) }
    fun onLoginChange(value: String) = _state.update { it.copy(login = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun submit(onSuccess: () -> Unit) {
        val current = _state.value
        if (!current.canSubmit) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            // Save first: the address is what the client is about to talk to, and
            // keeping a wrong one out of storage is not worth losing a right one
            // to a typo in the password.
            container.settings.baseUrl = current.serverUrl

            try {
                container.api().login(current.login.trim(), current.password)
                _state.update { it.copy(submitting = false, password = "") }
                onSuccess()
            } catch (error: StreamHubException) {
                _state.update { it.copy(submitting = false, error = error.message) }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        submitting = false,
                        // A bad address or an unreachable server lands here, and
                        // the exception text is not fit to show.
                        error = "Could not reach the server. Check the address and that it is running.",
                    )
                }
            }
        }
    }
}
