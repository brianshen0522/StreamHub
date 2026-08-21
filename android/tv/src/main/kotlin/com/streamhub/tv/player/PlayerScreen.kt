package com.streamhub.tv.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamhub.core.model.AdCut
import com.streamhub.core.model.CastCommand
import com.streamhub.core.model.CastPlaybackState
import com.streamhub.tv.AppContainer
import com.streamhub.tv.PlaybackRequest
import com.streamhub.tv.ui.StreamHubColors
import androidx.compose.foundation.shape.RoundedCornerShape
import com.streamhub.tv.ui.TvButton
import com.streamhub.tv.ui.Tv
import kotlinx.coroutines.delay

private const val SEEK_STEP_MS = 10_000L
private const val JUMP_STEP_MS = 60_000L
private const val CONTROLS_TIMEOUT_MS = 4_000L

/**
 * Playback on a television.
 *
 * There is no pointer and no scrub bar to drag, so the remote *is* the
 * transport: centre toggles, left and right step, the media keys do the same
 * for anyone whose remote has them. Every one of those also raises the
 * controls, because on a television the only way to know what a press did is to
 * see it happen.
 *
 * This screen is also the cast receiver's business end. It reports position to
 * the account's phones once a second and obeys the transport commands they
 * send, which is why a phone can drive it without this screen knowing anything
 * about phones.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    container: AppContainer,
    request: PlaybackRequest,
    viewModel: PlayerViewModel,
    onNextEpisode: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val api = container.api
    val receiver = container.castReceiver

    var isPlaying by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var adCuts by remember { mutableStateOf<List<AdCut>>(emptyList()) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsShownAt by remember { mutableLongStateOf(0L) }
    var driver by remember { mutableStateOf<String?>(null) }

    val focus = remember { FocusRequester() }

    val player = remember(request.directUrl) {
        val dataSourceFactory = OkHttpDataSource.Factory(api.authenticatedClient)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                // The cleaned manifest, not the raw source: ads are already cut
                // and segments point straight at the CDN.
                //
                // The MIME type is not optional. ExoPlayer infers the format
                // from the URI's extension, and this URL ends in a query string
                // rather than .m3u8, so without the hint it picks the
                // progressive source and dies with
                // UnrecognizedInputFormatException.
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(api.manifestUrl(request.directUrl))
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                )
                if (request.resumeAtSeconds > 0) seekTo(request.resumeAtSeconds * 1000L)
                prepare()
                playWhenReady = true
            }
    }

    /** Raises the controls and restarts their countdown. */
    val show = {
        controlsVisible = true
        controlsShownAt = System.currentTimeMillis()
    }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        show()
    }

    // Where the ads were, so the bar can mark them. The picture jumps at each
    // splice, and an unexplained jump reads as a broken stream.
    LaunchedEffect(request.directUrl) {
        adCuts = runCatching { api.adCuts(request.directUrl).cuts }.getOrDefault(emptyList())
    }

    LaunchedEffect(player) {
        while (true) {
            isPlaying = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
            if (player.playbackState == Player.STATE_ENDED && !ended) {
                ended = true
                // Finished means finished: reporting the end position is what
                // marks the episode complete, which is what moves Continue
                // watching on to the next one.
                val end = player.duration.coerceAtLeast(0)
                viewModel.report(end, end, "ended")
                show()
            }
            positionMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.coerceAtLeast(0)
            if (player.isPlaying) {
                viewModel.report(positionMs, durationMs, "progress")
            }
            delay(500)
        }
    }

    // Tell the account's phones what is on screen. Once a second, which is what
    // the phone's remote is built to interpolate between.
    LaunchedEffect(player, request.directUrl) {
        while (true) {
            receiver.publish(
                CastPlaybackState(
                    provider = request.providerKey,
                    itemUrl = request.itemUrl,
                    title = request.title,
                    subtitle = request.sourceLabel,
                    posterUrl = request.posterUrl,
                    episodeLabel = request.episodeLabel,
                    positionMs = player.currentPosition.coerceAtLeast(0),
                    durationMs = player.duration.coerceAtLeast(0),
                    paused = !player.isPlaying,
                    buffering = player.playbackState == Player.STATE_BUFFERING,
                )
            )
            delay(1_000)
        }
    }

    // Commands from a phone. Raising the controls on each one is deliberate:
    // someone watching should see that the picture changed because a person did
    // it, not wonder whether the app misbehaved.
    LaunchedEffect(player) {
        receiver.transport.collect { command ->
            driver = receiver.controlledBy.value
            show()
            when (command) {
                is CastCommand.Pause -> player.pause()
                is CastCommand.Resume -> player.play()
                is CastCommand.Seek -> player.seekTo(command.positionMs)
                is CastCommand.Stop -> onBack()
                is CastCommand.Next -> request.nextEpisodeLabel?.let(onNextEpisode)
                is CastCommand.Play -> Unit // handled by the app root, which navigates
            }
        }
    }

    // Controls get out of the way on their own, but never while paused — a
    // paused player with hidden controls offers no visible way to start it
    // again.
    LaunchedEffect(controlsShownAt, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        delay(CONTROLS_TIMEOUT_MS)
        if (System.currentTimeMillis() - controlsShownAt >= CONTROLS_TIMEOUT_MS) {
            controlsVisible = false
        }
    }

    DisposableEffect(player) {
        onDispose {
            viewModel.report(player.currentPosition, player.duration.coerceAtLeast(0), "pause")
            // Back to idle, or the phone goes on showing a title that stopped.
            receiver.publish(null)
            player.release()
        }
    }

    // Back closes the controls first. Leaving outright on the first press makes
    // the button feel like an ejector seat when all someone wanted was the
    // overlay gone.
    BackHandler(enabled = true) {
        if (controlsVisible && isPlaying) controlsVisible = false else onBack()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // The up-next prompt owns the remote while it is showing. Left
                // and right still mean "seek" to this handler, and consuming
                // them would leave the prompt's own buttons unreachable.
                if (ended && request.nextEpisodeLabel != null) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                        if (player.isPlaying) player.pause() else player.play()
                        show(); true
                    }
                    Key.MediaPlay -> { player.play(); show(); true }
                    Key.MediaPause -> { player.pause(); show(); true }
                    Key.DirectionLeft -> {
                        player.seekTo((player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
                        show(); true
                    }
                    Key.DirectionRight -> {
                        player.seekTo(player.currentPosition + SEEK_STEP_MS)
                        show(); true
                    }
                    Key.MediaRewind -> {
                        player.seekTo((player.currentPosition - JUMP_STEP_MS).coerceAtLeast(0))
                        show(); true
                    }
                    Key.MediaFastForward -> {
                        player.seekTo(player.currentPosition + JUMP_STEP_MS)
                        show(); true
                    }
                    Key.MediaNext -> {
                        request.nextEpisodeLabel?.let(onNextEpisode); true
                    }
                    // Up and down have nothing to steer, so they do the one
                    // thing someone pressing a key in the dark actually wants.
                    Key.DirectionUp, Key.DirectionDown -> { show(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering) {
            Text(
                "Loading…",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (ended && request.nextEpisodeLabel != null) {
            UpNextPrompt(
                label = request.nextEpisodeLabel!!,
                onPlay = { onNextEpisode(request.nextEpisodeLabel!!) },
                onDismiss = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Nothing to ask about. A finished episode frozen on its last frame is
        // a screen whose only remaining control is the back button, which on a
        // television is a worse place to leave someone than the title page.
        if (ended && request.nextEpisodeLabel == null) {
            LaunchedEffect(Unit) { onBack() }
        }

        // Hidden while the prompt is up: the hint row would still be offering
        // "OK Play" and "10s" when the remote no longer does either, which is
        // worse than showing nothing.
        if (controlsVisible && !(ended && request.nextEpisodeLabel != null)) {
            Controls(
                title = request.title,
                subtitle = listOfNotNull(
                    request.episodeLabel?.let { "Episode $it" },
                    request.sourceLabel,
                ).joinToString("  ·  "),
                driver = driver,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                adCuts = adCuts,
                hasNext = request.nextEpisodeLabel != null,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

/**
 * Offered when the episode ends.
 *
 * Two choices and no countdown: a countdown that starts the next episode
 * unless it is stopped is exactly the behaviour that leaves a television
 * playing to an empty room. Play takes focus, so the common answer is one
 * press of the centre key.
 */
@Composable
private fun UpNextPrompt(
    label: String,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.86f), RoundedCornerShape(16.dp))
            .padding(horizontal = 40.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Up next",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                "Episode $label",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton(
                label = "Play",
                primary = true,
                onClick = onPlay,
                modifier = Modifier.focusRequester(focus),
            )
            TvButton(label = "Done", onClick = onDismiss)
        }
    }
}

@Composable
private fun Controls(
    title: String,
    subtitle: String,
    driver: String?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    adCuts: List<AdCut>,
    hasNext: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = Tv.OverscanH, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
            if (driver != null) {
                // White, not the accent. Red on this overlay reads as
                // something having gone wrong, and a phone taking control is
                // information, not a fault.
                Text(
                    text = "Controlled from $driver",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }

        ProgressBar(
            positionMs = positionMs,
            durationMs = durationMs,
            adCuts = adCuts,
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${formatTime(positionMs)}  /  ${formatTime(durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            // The remote has no labels on it, so the screen carries them. Only
            // the keys that do something here, and only while the overlay is up.
            Text(
                text = buildString {
                    append(if (isPlaying) "OK Pause" else "OK Play")
                    append("   ◀ ▶ 10s")
                    if (hasNext) append("   ⏭ Next episode")
                    append("   BACK Exit")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * Position, with the removed ad breaks marked.
 *
 * Hand-drawn rather than a slider: nothing here is draggable, so a control that
 * looks draggable would be a lie about what the remote can do.
 */
@Composable
private fun ProgressBar(
    positionMs: Long,
    durationMs: Long,
    adCuts: List<AdCut>,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    // Orange, matching the phone. The secondary role here is a dark grey, which
    // on a dark bar would be a mark nobody can see.
    val marker = StreamHubColors.Orange

    Canvas(modifier = modifier) {
        val duration = durationMs.coerceAtLeast(1)
        val progress = (positionMs.toFloat() / duration).coerceIn(0f, 1f)

        drawRect(color = Color.White.copy(alpha = 0.25f), size = size)
        drawRect(color = accent, size = Size(size.width * progress, size.height))

        for (cut in adCuts) {
            val at = ((cut.at * 1000.0) / duration).toFloat()
            if (at !in 0f..1f) continue
            drawRect(
                color = marker,
                topLeft = Offset(size.width * at - 2f, 0f),
                size = Size(4f, size.height),
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val total = millis / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
