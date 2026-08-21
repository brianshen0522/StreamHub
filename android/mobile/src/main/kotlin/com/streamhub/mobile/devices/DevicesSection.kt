package com.streamhub.mobile.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.streamhub.core.model.DeviceSession
import com.streamhub.core.net.StreamHubException
import com.streamhub.mobile.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DevicesUiState(
    val loading: Boolean = true,
    val devices: List<DeviceSession> = emptyList(),
    val error: String? = null,
)

class DevicesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.devices.isEmpty(), error = null) }
            try {
                _state.update { it.copy(loading = false, devices = container.api.sessions()) }
            } catch (error: StreamHubException) {
                _state.update { it.copy(loading = false, error = error.message) }
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = "Could not reach the server.") }
            }
        }
    }

    fun revoke(device: DeviceSession, onSignedOutHere: () -> Unit) {
        viewModelScope.launch {
            runCatching { container.api.revokeSession(device.id) }
            if (device.current) {
                // Ending this device's own session means this device is signed
                // out; the app has to act on that rather than sit on a session
                // the server has forgotten.
                container.sessionStore.clear()
                onSignedOutHere()
            } else {
                refresh()
            }
        }
    }
}

/**
 * The devices signed in to this account.
 *
 * A refresh token is good for thirty days, so a sign-in from somewhere the
 * account holder does not recognise is worth being able to see and end. It sits
 * under Status because that is where someone goes when something is not right.
 */
@Composable
fun DevicesSection(
    viewModel: DevicesViewModel,
    onSignedOutHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<DeviceSession?>(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Devices", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = viewModel::refresh, enabled = !state.loading) {
                Text(if (state.loading) "Loading…" else "Refresh")
            }
        }

        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        for (device in state.devices) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.deviceName + if (device.current) "  ·  this device" else "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = device.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { confirming = device }) {
                    Text(if (device.current) "Sign out" else "Remove")
                }
            }
        }

        if (state.devices.isEmpty() && !state.loading && state.error == null) {
            Text(
                "No other devices are signed in.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    confirming?.let { device ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(if (device.current) "Sign out?" else "Remove ${device.deviceName}?") },
            text = {
                Text(
                    if (device.current) {
                        "You will need to sign in again on this device."
                    } else {
                        "That device will be signed out and will need the password again."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    viewModel.revoke(device, onSignedOutHere)
                }) { Text(if (device.current) "Sign out" else "Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            },
        )
    }
}

private fun DeviceSession.describe(): String {
    val kind = when (clientKind) {
        "tv" -> "Television"
        "android" -> "Phone"
        "ios" -> "iPhone"
        null -> "Web browser"
        else -> clientKind
    }
    // A timestamp would be better read as "2 hours ago", but that needs a clock
    // and a formatter; the date alone already answers "is this one of mine".
    val seen = lastSeenAt?.take(10)
    return listOfNotNull(kind, seen?.let { "last used $it" }).joinToString(" · ")
}
