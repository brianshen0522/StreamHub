package com.streamhub.mobile.cast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    /** The chosen device is unreachable: still shown, so there is a way back. */
    lost: Boolean = false,
) {
    if (receivers.isEmpty() && !connected) return
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "Play on another device" },
    ) {
        CastIcon(
            connected = connected && !lost,
            tint = when {
                lost -> MaterialTheme.colorScheme.error
                connected -> MaterialTheme.colorScheme.primary
                else -> tint
            },
        )
    }
}

/**
 * The "Not connected" group: televisions signed in to the account whose app is
 * not running.
 *
 * One row per device name, not one per sign-in — a television signed in a few
 * times has several sessions, and rows sharing a name are indistinguishable.
 * A name that is already listed above (connected, or the chosen device that
 * dropped) is left out too: the same television once as "Ready" and again as
 * "Open StreamHub on this device" reads as two televisions, one broken.
 */
fun notConnectedDevices(
    sessions: List<DeviceSession>,
    receivers: List<CastReceiver>,
    lostTarget: CastReceiver?,
    limit: Int = 3,
): List<DeviceSession> {
    val listedIds = receivers.map { it.sessionId }.toSet() + listOfNotNull(lostTarget?.sessionId)
    val listedNames = receivers.map { it.deviceName }.toSet() + listOfNotNull(lostTarget?.deviceName)
    return sessions
        .filter { it.isTelevision && it.id !in listedIds && it.deviceName !in listedNames }
        .sortedByDescending { it.lastSeenAt ?: "" }
        .distinctBy { it.deviceName }
        .take(limit)
}

/** What a connected device's row says under its name. */
fun receiverDetail(receiver: CastReceiver): String {
    val state = receiver.state?.takeIf { !it.idle } ?: return "Ready"
    val title = state.title ?: return "Ready"
    return if (state.paused) "Paused · $title" else "Playing $title"
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
    lost: Boolean,
    onPick: (CastReceiver) -> Unit,
    onPlayHere: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sessions by remember { mutableStateOf<List<DeviceSession>>(emptyList()) }

    // Keyed on *who* is connected, not on the list itself: a playing
    // television re-sends the list every second with a new position, and
    // keying on that asked the server for the account's sessions once a
    // second for as long as the sheet was open.
    val connectedIds = receivers.map { it.sessionId }.toSet()
    LaunchedEffect(connectedIds) {
        sessions = runCatching { container.api.sessions() }.getOrDefault(sessions)
    }
    val lostTarget = target?.takeIf { lost }
    val offline = notConnectedDevices(sessions, receivers, lostTarget)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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
                    detail = receiverDetail(receiver),
                    selected = receiver.sessionId == target?.sessionId,
                    enabled = true,
                    onClick = { onPick(receiver) },
                )
            }

            // The chosen device, gone. Listed so the sheet still shows what
            // the cast button is pointing at; picking "This phone" is the way
            // out.
            if (lostTarget != null) {
                DeviceRow(
                    name = lostTarget.deviceName,
                    detail = "Disconnected — waiting for it to come back",
                    selected = true,
                    enabled = false,
                    onClick = {},
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
    paused: Boolean,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = target.state?.takeIf { !it.idle }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 56.dp),
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
        when {
            // Gone: the one useful thing is a way back to the phone without a
            // detour through the picker.
            lost -> IconButton(onClick = onDisconnect) {
                Icon(Icons.Default.Close, contentDescription = "Stop casting to ${target.deviceName}")
            }
            state?.title != null -> IconButton(onClick = onTogglePlay) {
                PlayPauseGlyph(paused = paused, tint = MaterialTheme.colorScheme.onSurface)
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
