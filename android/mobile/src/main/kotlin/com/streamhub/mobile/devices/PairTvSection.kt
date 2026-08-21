package com.streamhub.mobile.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.streamhub.core.model.PendingDevice
import com.streamhub.core.model.UserCode
import com.streamhub.core.net.StreamHubException
import com.streamhub.mobile.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where the phone is in signing a television in.
 *
 * Deliberately a small state machine rather than a dialog that grants on tap:
 * looking a code up and acting on it are separate steps because the step in
 * between — naming the device — is the only thing standing between this and a
 * button that hands the account to whoever asked last.
 */
sealed interface PairTvStep {
    /** Waiting for the code off the television. */
    data object EnteringCode : PairTvStep

    /** The code is real; this is what it belongs to. */
    data class Confirming(val device: PendingDevice) : PairTvStep

    data class Signed(val deviceName: String) : PairTvStep

    data object Refused : PairTvStep
}

data class PairTvUiState(
    val step: PairTvStep = PairTvStep.EnteringCode,
    val busy: Boolean = false,
    val error: String? = null,
)

class PairTvViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(PairTvUiState())
    val state: StateFlow<PairTvUiState> = _state.asStateFlow()

    fun lookUp(code: String) {
        if (!UserCode.isComplete(code)) {
            _state.update { it.copy(error = "That code is eight characters long.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val device = container.api.pendingDevice(code)
                _state.update { it.copy(busy = false, step = PairTvStep.Confirming(device)) }
            } catch (error: StreamHubException) {
                // Expired, already used, and never existed all arrive as 404 and
                // all mean the same thing to somebody holding a phone.
                val message = if (error.status == 404 || error.status == 400) {
                    "That code has expired or was already used. Get a fresh one on your television."
                } else {
                    error.message
                }
                _state.update { it.copy(busy = false, error = message) }
            } catch (error: Exception) {
                _state.update { it.copy(busy = false, error = "Could not reach the server.") }
            }
        }
    }

    fun decide(approve: Boolean, onApproved: () -> Unit) {
        val current = _state.value.step as? PairTvStep.Confirming ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val code = current.device.userCode
                if (approve) container.api.approveDevice(code) else container.api.denyDevice(code)
                _state.update {
                    it.copy(
                        busy = false,
                        step = if (approve) PairTvStep.Signed(current.device.deviceName) else PairTvStep.Refused,
                    )
                }
                // The television appears in the list below once it collects its
                // session, which is one poll away rather than immediate — but
                // refreshing now is still the difference between it showing up
                // on its own and the person wondering whether it worked.
                if (approve) onApproved()
            } catch (error: StreamHubException) {
                _state.update { it.copy(busy = false, error = error.message, step = PairTvStep.EnteringCode) }
            } catch (error: Exception) {
                _state.update { it.copy(busy = false, error = "Could not reach the server.", step = PairTvStep.EnteringCode) }
            }
        }
    }

    fun startOver() {
        _state.update { PairTvUiState() }
    }
}

/**
 * Draws the code grouped without putting the separator in the value.
 *
 * The state stays the eight characters the server wants; only the rendering
 * gains `ABCD-EFGH`. The offset mapping is what keeps the caret honest — one
 * character of difference on either side of the break.
 */
private object GroupedCode : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val shown = if (raw.length > 4) "${raw.take(4)}-${raw.drop(4)}" else raw
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset <= 4) offset else offset + 1
            override fun transformedToOriginal(offset: Int) = if (offset <= 4) offset else offset - 1
        }
        return TransformedText(AnnotatedString(shown), mapping)
    }
}

/**
 * Signing a television in by typing the code it is showing.
 *
 * The phone's own camera already handles the QR — scanning it opens the
 * approval page in a browser — so this is the other half: the way in when the
 * camera will not cooperate, or when the television is in another room and
 * somebody read the code out.
 *
 * Sits directly above the device list, because that is where the television
 * appears once it signs in, and "add one" belongs next to "the ones you have".
 */
@Composable
fun PairTvSection(
    viewModel: PairTvViewModel,
    onPaired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect a TV", style = MaterialTheme.typography.titleMedium)

        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        when (val step = state.step) {
            PairTvStep.EnteringCode -> {
                Text(
                    "Enter the code your television is showing, instead of typing a password on a remote.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    // Only ever *removes* — uppercases, drops anything that is
                    // not a code character, and stops at eight. The separator is
                    // drawn by the transformation below and never written into
                    // the value: inserting a character here moves the cursor to
                    // somewhere the keyboard is not expecting, and every
                    // character after the fourth then lands at the wrong offset.
                    // Typed slowly, one at a time, "3vxja5wj" came out as
                    // "3VXJ-5WJA".
                    onValueChange = { code = UserCode.normalise(it).take(UserCode.LENGTH) },
                    visualTransformation = GroupedCode,
                    label = { Text("Code") },
                    placeholder = { Text("ABCD-EFGH") },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        letterSpacing = 3.sp,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { viewModel.lookUp(code) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { viewModel.lookUp(code) },
                    enabled = !state.busy && UserCode.isComplete(code),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.busy) "Checking…" else "Continue")
                }
            }

            is PairTvStep.Confirming -> {
                Text(step.device.deviceName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "It gets everything you have: your history, your favourites, and every provider " +
                        "you can search. Only continue if this is your television and it is showing " +
                        "${step.device.userCode}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.decide(approve = true, onApproved = onPaired) },
                        enabled = !state.busy,
                    ) {
                        Text(if (state.busy) "Signing in…" else "Sign it in")
                    }
                    TextButton(
                        onClick = { viewModel.decide(approve = false, onApproved = onPaired) },
                        enabled = !state.busy,
                    ) {
                        Text("Not my device")
                    }
                }
            }

            is PairTvStep.Signed -> {
                Text("${step.deviceName} is signed in.", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { code = ""; viewModel.startOver() }) { Text("Connect another") }
            }

            PairTvStep.Refused -> {
                Text(
                    "That code no longer works, and nothing was given access.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { code = ""; viewModel.startOver() }) { Text("Enter another code") }
            }
        }
    }
}
