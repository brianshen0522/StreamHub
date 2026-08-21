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
    val login: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = !submitting && login.isNotBlank() && password.isNotBlank()
}

class LoginViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** Shown under the form, so it is obvious which deployment this build talks to. */
    val serverUrl: String = container.serverUrl

    fun onLoginChange(value: String) = _state.update { it.copy(login = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun submit(onSuccess: () -> Unit) {
        val current = _state.value
        if (!current.canSubmit) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                container.api.login(current.login.trim(), current.password)
                _state.update { it.copy(submitting = false, password = "") }
                onSuccess()
            } catch (error: StreamHubException) {
                _state.update { it.copy(submitting = false, error = error.message) }
            } catch (error: Exception) {
                // An unreachable server lands here, and the exception text is not
                // fit to show. There is nothing the person can correct, so this
                // says what happened rather than asking them to check a setting.
                _state.update {
                    it.copy(submitting = false, error = "Cannot reach StreamHub. Check your connection and try again.")
                }
            }
        }
    }
}
