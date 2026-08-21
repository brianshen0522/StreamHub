package com.streamhub.tv.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamhub.core.model.DevicePairing
import com.streamhub.core.model.DevicePairingStatus
import com.streamhub.core.net.StreamHubException
import com.streamhub.tv.AppContainer
import com.streamhub.tv.ui.QrCode
import com.streamhub.tv.ui.StreamHubColors
import com.streamhub.tv.ui.Tv
import com.streamhub.tv.ui.TvButton
import com.streamhub.tv.ui.dpadEscapes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
                var resolved = false
                while (isActive && !resolved) {
                    delay(interval)
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

/**
 * Signing in, once.
 *
 * Two ways to do it, side by side, because they suit different people rather
 * than being a first choice and a fallback. On the right, a code and a QR: no
 * typing at all, which on a remote control is the difference between a minute
 * and five. On the left, the username and password, for anyone who would rather
 * just type it and be done.
 *
 * The pairing half needs no focus and takes none — there is nothing on it to
 * activate — so the d-pad stays in the form and the remote never has to visit a
 * column it cannot use.
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

    // Focus starts on the button, not on the username field, and that is the
    // whole difference between this screen working and not. Focusing a text
    // field opens the system keyboard immediately, and on a television that
    // keyboard is half the screen — it covered the QR's lower-left corner,
    // which is one of the three squares a scanner uses to find the code at all.
    // The panel that exists to save you from the keyboard was being hidden by
    // the keyboard. Anyone who does want to type is one press away.
    val signInFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { signInFocus.requestFocus() }

    // The callback is read at the moment pairing completes rather than captured
    // when the loop starts, which may be minutes earlier.
    val signedIn by rememberUpdatedState(onSignedIn)
    LaunchedEffect(Unit) { viewModel.startPairing { signedIn() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = Tv.OverscanH, vertical = Tv.OverscanV),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.width(440.dp),
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
                    modifier = Modifier.fillMaxWidth(),
                )
                TvField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Password",
                    password = true,
                    modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.focusRequester(signInFocus),
                    onClick = { if (!state.busy) viewModel.signIn(login, password, onSignedIn) },
                )
            }

            PairingPanel(
                pairing = state.pairing,
                message = state.pairingError,
                modifier = Modifier.width(380.dp),
            )
        }
    }
}

private val QR_SIZE = 240.dp

/**
 * The half that needs no keyboard.
 *
 * Order is deliberate: the QR first, because scanning is one action and typing
 * is eight; the code under it for anyone whose camera will not cooperate; the
 * address last, because it is only needed by the person who is going to type
 * the code and wants to know where.
 */
@Composable
private fun PairingPanel(
    pairing: DevicePairing?,
    message: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Sign in with your phone",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // A fixed size, not one derived from the panel's width. A 1080p
        // television is 540dp tall and the safe area is less; sizing the code
        // off the width overflowed that, and a Column that runs out of height
        // does not clip the overflow, it crushes the last child — the address
        // under the code was rendering five pixels tall. 240dp is around 480
        // real pixels, which reads from a sofa with room to spare.
        Box(
            modifier = Modifier.size(QR_SIZE),
            contentAlignment = Alignment.Center,
        ) {
            if (pairing != null) {
                QrCode(
                    content = pairing.verificationUrlComplete,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = message ?: "Getting a code…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Text(
            // Non-breaking hyphen: the two halves of the code wrapping onto
            // separate lines would read as two things to type.
            text = pairing?.userCode?.replace('-', '‑') ?: " ",
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            text = pairing?.verificationUrl?.removePrefix("https://")?.removePrefix("http://")
                ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
                // White for focus, the same rule the whole app follows: red
                // means selected, white means the remote is here.
                color = if (focused) StreamHubColors.T1
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
