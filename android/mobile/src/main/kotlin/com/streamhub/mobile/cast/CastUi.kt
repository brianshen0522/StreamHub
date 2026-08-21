package com.streamhub.mobile.cast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.streamhub.core.model.CastReceiver
import com.streamhub.core.model.DeviceSession
import com.streamhub.mobile.AppContainer

/**
 * The cast glyph, drawn rather than imported.
 *
 * The Material core icon set has no Cast, and pulling in the extended set for
 * one 24dp shape would add several thousand vector drawables to the APK.
 */
@Composable
fun CastIcon(
    connected: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        val stroke = Stroke(width = 2f * unit)

        // The screen. Its bottom-left corner is left open for the arcs, which
        // is what makes the glyph read as "cast" and not "monitor".
        val left = 3f * unit
        val top = 4f * unit
        val right = 21f * unit
        val bottom = 18f * unit
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * unit, 2f * unit),
            style = stroke,
            alpha = if (connected) 0.45f else 1f,
        )
        if (connected) {
            // Filled while something is playing there, so the state reads at a
            // glance from across the room rather than needing the label.
            drawRoundRect(
                color = tint,
                topLeft = Offset(left + 2f * unit, top + 2f * unit),
                size = Size(right - left - 4f * unit, bottom - top - 4f * unit),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f * unit, 1f * unit),
            )
        }

        // Signal arcs radiating from the bottom-left corner.
        val origin = Offset(3f * unit, 20f * unit)
        for (radius in listOf(4f, 8f)) {
            drawArc(
                color = tint,
                startAngle = 270f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(origin.x - radius * unit, origin.y - radius * unit),
                size = Size(radius * 2f * unit, radius * 2f * unit),
                style = stroke,
            )
        }
        drawCircle(color = tint, radius = 1.4f * unit, center = origin)
    }
}

/**
 * Opens the device picker. Renders nothing when there is nowhere to cast to.
 *
 * A control that is always visible but usually does nothing teaches people to
 * ignore it; this one appearing is itself the signal that a television is on.
 */
@Composable
fun CastButton(
    receivers: List<CastReceiver>,
    connected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (receivers.isEmpty() && !connected) return
    IconButton(onClick = onClick, modifier = modifier) {
        CastIcon(
            connected = connected,
            tint = if (connected) MaterialTheme.colorScheme.primary else tint,
        )
    }
}

/**
 * Picks where playback happens.
 *
 * Also lists televisions that are signed in but not currently connected. They
 * cannot be cast to, and saying so is the point: without that line the honest
 * answer to "why is my television missing" is invisible, and the list looks
 * broken rather than accurate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSheet(
    container: AppContainer,
    receivers: List<CastReceiver>,
    target: CastReceiver?,
    onPick: (CastReceiver) -> Unit,
    onPlayHere: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var offline by remember { mutableStateOf<List<DeviceSession>>(emptyList()) }

    LaunchedEffect(receivers) {
        val connectedIds = receivers.map { it.sessionId }.toSet()
        offline = runCatching { container.api.sessions() }
            .getOrDefault(emptyList())
            .filter { it.isTelevision && it.id !in connectedIds }
            // One row per device, not one per sign-in. A television that has
            // been signed in a few times has several live sessions, and rows
            // sharing a name are indistinguishable to whoever is reading them —
            // so all but the most recent are noise.
            .sortedByDescending { it.lastSeenAt ?: "" }
            .distinctBy { it.deviceName }
            .take(3)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Play on",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            DeviceRow(
                name = "This phone",
                detail = "Play here",
                selected = target == null,
                enabled = true,
                onClick = onPlayHere,
            )

            for (receiver in receivers) {
                DeviceRow(
                    name = receiver.deviceName,
                    detail = receiver.state?.title?.let { "Playing $it" } ?: "Ready",
                    selected = receiver.sessionId == target?.sessionId,
                    enabled = true,
                    onClick = { onPick(receiver) },
                )
            }

            if (offline.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    "Not connected",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                for (device in offline) {
                    DeviceRow(
                        name = device.deviceName,
                        detail = "Open StreamHub on this device",
                        selected = false,
                        enabled = false,
                        onClick = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    name: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CastIcon(
            connected = selected,
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The strip that keeps a remote session reachable from anywhere in the app.
 *
 * Without it, walking out of the remote screen is indistinguishable from
 * stopping the cast: the television keeps playing and the phone shows no sign
 * of it.
 */
@Composable
fun CastBar(
    target: CastReceiver,
    lost: Boolean,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = target.state
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CastIcon(
            connected = !lost,
            tint = if (lost) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state?.title ?: target.deviceName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                text = when {
                    lost -> "${target.deviceName} disconnected"
                    state?.title != null -> "On ${target.deviceName}"
                    else -> "Ready to play"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (lost) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (!lost && state?.title != null) {
            IconButton(onClick = onTogglePlay) {
                PlayPauseGlyph(paused = state.paused, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Two bars or a triangle. Also drawn, since the core set has no Pause. */
@Composable
fun PlayPauseGlyph(paused: Boolean, tint: Color, modifier: Modifier = Modifier, sizeDp: Int = 24) {
    if (paused) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Resume",
            tint = tint,
            modifier = modifier.size(sizeDp.dp),
        )
        return
    }
    Row(
        modifier = modifier.size(sizeDp.dp),
        horizontalArrangement = Arrangement.spacedBy((sizeDp / 8).dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) {
            Box(
                modifier = Modifier
                    .size(width = (sizeDp / 6).dp, height = (sizeDp * 0.6f).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(tint),
            )
        }
    }
}
