package com.streamhub.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

/**
 * Ten-foot conventions in one place.
 *
 * A television crops the edges of the picture, so nothing may sit closer than
 * roughly 5% to any edge. And there is no pointer: whatever is focused is the
 * only thing the viewer can act on, so focus has to be visible from across the
 * room — a subtle highlight that reads fine on a phone held at arm's length
 * disappears entirely at three metres.
 */
object Tv {
    val OverscanH = 48.dp
    val OverscanV = 27.dp
    val PosterWidth = 180.dp
    val RowGap = 20.dp
}

/** Where the remote is. Never the brand colour — see [FocusCard]. */
private val FocusRing = Color.White

/**
 * Lets the d-pad leave a text field.
 *
 * A focused Compose text field treats up and down as cursor movement and
 * consumes them. On a phone that is invisible — there is a touchscreen — but on
 * a television it is a dead end: focus enters the search box and the remote can
 * never get out again, which is exactly what happened here.
 *
 * Applied to the field's *parent*, so the preview pass sees the key before the
 * field does.
 */
@Composable
fun Modifier.dpadEscapes(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> { focusManager.moveFocus(FocusDirection.Down); true }
            Key.DirectionUp -> { focusManager.moveFocus(FocusDirection.Up); true }
            else -> false
        }
    }
}

/** Posters are 2:3 everywhere, so the ratio lives in one place. */
private const val POSTER_ASPECT = 2f / 3f

/**
 * The one focusable primitive on this screen.
 *
 * Scale *and* border, not one or the other: the scale is what catches the eye
 * while moving across a row, and the border is what stays legible once the eye
 * has landed.
 *
 * The border is white, not the brand red, and that separation is the whole
 * point: red already means "this is the selected episode / the primary action",
 * so a red focus ring drawn on a red button is invisible — which is exactly
 * what happened the first time. Focus says where the remote is; red says what
 * is active. Two meanings, two colours.
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "focusScale")

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            // clickable brings focusability with it, and is what makes the
            // remote's centre key and ENTER both activate the card.
            .clickable(onClick = onClick)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) FocusRing else Color.Transparent,
                shape = shape,
            )
            .clip(shape),
    ) {
        content(focused)
    }
}

/** A poster with its title underneath, the unit every row is built from. */
@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
) {
    Column(
        modifier = modifier.width(Tv.PosterWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) { focused ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(POSTER_ASPECT)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (posterUrl != null) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // How far in the viewer already is, drawn on the artwork
                // itself. On a row of a dozen titles this is the difference
                // between "which one was I watching" and reading every label.
                if (progress != null && progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(6.dp)
                            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                                .padding(vertical = 2.dp),
                        )
                    }
                }
                if (focused) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.08f)),
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A text button sized and coloured for a remote control rather than a thumb. */
@Composable
fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    FocusCard(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(8.dp)) { focused ->
        // Focused inverts to a light fill, the way the system's own television
        // UI does. Combined with the white ring it reads from across the room
        // even when the button underneath is already red.
        val background = when {
            focused -> FocusRing
            primary -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        Box(
            modifier = Modifier
                .background(background)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    focused -> Color.Black
                    primary -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Composable
fun CentredMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
