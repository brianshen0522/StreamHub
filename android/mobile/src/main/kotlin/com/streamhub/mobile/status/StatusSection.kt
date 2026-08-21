package com.streamhub.mobile.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamhub.core.model.ProviderInfo
import com.streamhub.mobile.ui.StreamHubColors

/** How a row reads at a glance, before anyone reads the words next to it. */
enum class StatusLevel { GOOD, WARNING, BAD, OFF }

@Composable
private fun StatusLevel.color(): Color = when (this) {
    // The web client already has semantic colours for exactly this; using its
    // --green and --orange keeps a healthy provider the same green in both.
    // Deliberately not the accent, which means "StreamHub", not "fine".
    StatusLevel.GOOD -> StreamHubColors.Green
    StatusLevel.WARNING -> StreamHubColors.Orange
    StatusLevel.BAD -> MaterialTheme.colorScheme.error
    StatusLevel.OFF -> MaterialTheme.colorScheme.outline
}

@Composable
fun StatusSection(viewModel: StatusViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = viewModel::refresh, enabled = !state.checking) {
                Text(if (state.checking) "Checking…" else "Check again")
            }
        }

        // Deliberately no address, version or millisecond figure. Those describe
        // the deployment rather than anything a viewer can act on, and this
        // screen is for telling one kind of failure from another.
        StatusRow(
            level = when {
                !state.serverReachable -> StatusLevel.BAD
                state.slowToRespond -> StatusLevel.WARNING
                else -> StatusLevel.GOOD
            },
            label = "Connection",
            detail = when {
                state.serverError != null -> state.serverError!!
                state.slowToRespond -> "Reachable, but responding slowly"
                state.serverReachable -> "Connected"
                else -> "Unknown"
            },
        )

        StatusRow(
            level = if (state.realtimeConnected) StatusLevel.GOOD else StatusLevel.WARNING,
            label = "Live updates",
            detail = if (state.realtimeConnected) {
                "Connected"
            } else {
                "Not connected — other devices' changes will not appear until this screen is reopened"
            },
        )

        if (state.providers.isNotEmpty()) {
            Text(
                text = "Providers",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            for (provider in state.providers) {
                StatusRow(
                    level = provider.level(),
                    label = provider.name,
                    detail = provider.detail(),
                )
            }
        }
    }
}

private fun ProviderInfo.level(): StatusLevel = when {
    !isEnabled || !allowed -> StatusLevel.OFF
    status == "DOWN" -> StatusLevel.BAD
    status == "DEGRADED" -> StatusLevel.WARNING
    status == "HEALTHY" -> StatusLevel.GOOD
    else -> StatusLevel.WARNING
}

private fun ProviderInfo.detail(): String {
    unavailableReason?.let { return it }
    return when (status) {
        "HEALTHY" -> "Responding"
        "DEGRADED" -> "Slow to respond"
        null -> "Not checked yet"
        else -> "Unknown"
    }
}

@Composable
private fun StatusRow(level: StatusLevel, label: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Nudged down to sit on the label's line rather than centred against a
        // detail line that may wrap to two.
        Spacer(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(level.color())
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

