package com.streamhub.tv.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamhub.core.net.StreamHubException
import com.streamhub.tv.AppContainer
import com.streamhub.tv.ui.Tv
import com.streamhub.tv.ui.TvButton
import com.streamhub.tv.ui.dpadEscapes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val busy: Boolean = false,
    val error: String? = null,
)

class LoginViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

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
}

/**
 * Signing in, once.
 *
 * Typing on a remote control is slow enough that everything here is arranged to
 * need as little of it as possible: two fields, no email, no "confirm", and the
 * username field is the first thing focused so the system keyboard opens
 * without the viewer hunting for it.
 *
 * No account management beyond this. Changing a password on a television is a
 * worse version of doing it anywhere else, and this screen exists to be passed
 * through once.
 */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onSignedIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val loginFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { loginFocus.requestFocus() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = Tv.OverscanH, vertical = Tv.OverscanV),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(560.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("StreamHub", style = MaterialTheme.typography.displaySmall)
            Text(
                "Sign in with your viewer account.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TvField(
                value = login,
                onValueChange = { login = it },
                placeholder = "Username",
                modifier = Modifier.focusRequester(loginFocus),
            )
            TvField(
                value = password,
                onValueChange = { password = it },
                placeholder = "Password",
                password = true,
                onDone = { viewModel.signIn(login, password, onSignedIn) },
            )

            if (state.error != null) {
                Text(
                    text = state.error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            TvButton(
                label = if (state.busy) "Signing in…" else "Sign in",
                primary = true,
                onClick = { if (!state.busy) viewModel.signIn(login, password, onSignedIn) },
            )
        }
    }
}

/**
 * A text field that shows where focus is.
 *
 * BasicTextField rather than a Material one because tv-material3 ships no text
 * field, and the phone Material field's focus treatment is invisible at
 * television distance.
 */
@Composable
private fun TvField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .dpadEscapes()
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) androidx.compose.ui.graphics.Color.White
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            textStyle = MaterialTheme.typography.titleSmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            visualTransformation = if (password) PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                // Email rather than Text: the Text keyboard auto-capitalises,
                // which silently turns "viewer" into "Viewer" and fails the
                // sign-in with no visible cause.
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Email,
                imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        )
    }
}
