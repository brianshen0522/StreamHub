package com.streamhub.tv.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamhub.core.model.AdCut
import com.streamhub.core.model.CastCommand
import com.streamhub.core.model.CastPlaybackState
import com.streamhub.core.playback.RecoveryLadder
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

    // ── mid-stream failure recovery ──────────────────────────────────────────
    // The reported symptom this exists for: the picture goes black mid-episode
    // and nothing responds. That is a fatal player error with nobody
    // listening. The ladder retries in place, then routes the stream through
    // the server, and only then admits defeat — visibly, with a retry button.
    val recovery = remember(request.directUrl) { RecoveryLadder() }
    var faultNonce by remember(request.directUrl) { mutableLongStateOf(0L) }
    var faultMessage by remember(request.directUrl) { mutableStateOf<String?>(null) }
    var recoveringLabel by remember(request.directUrl) { mutableStateOf<String?>(null) }
    var fatal by remember(request.directUrl) { mutableStateOf<String?>(null) }
    var lastGoodPositionMs by remember(request.directUrl) {
        mutableLongStateOf(request.resumeAtSeconds * 1000L)
    }
    var lastFaultAt by remember(request.directUrl) { mutableLongStateOf(0L) }

    // The MIME type is not optional. ExoPlayer infers the format from the
    // URI's extension, and these URLs end in a query string rather than
    // .m3u8, so without the hint it picks the progressive source and dies
    // with UnrecognizedInputFormatException.
    val mediaItemForTier = { tier: Int ->
        MediaItem.Builder()
            // Tier 0 is the cleaned manifest: ads already cut, segments
            // straight at the CDN. Tier 1 is the same stream relayed through
            // the server — not ad-filtered, but reachable when the
            // television's own path to the CDN has died.
            .setUri(if (tier == 0) api.manifestUrl(request.directUrl) else api.streamUrl(request.directUrl))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
    }

    val player = remember(request.directUrl) {
        val dataSourceFactory = OkHttpDataSource.Factory(api.authenticatedClient)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // These sources come off scraped CDNs that stall and vanish
            // mid-episode; every buffered minute is a minute those failures
            // cannot touch. Three minutes ahead instead of the stock fifty
            // seconds, and a minute behind so a skip back never refetches.
            // The byte cap must win over the time targets: ExoPlayer buffers
            // samples on the Java heap, and a television heap can be as small
            // as 48 MB — three unbounded minutes of video was an OOM crash
            // mid-episode, not a tuning choice.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(50_000, 180_000, 2_500, 5_000)
                    .setBackBuffer(60_000, true)
                    .setTargetBufferBytes(
                        (Runtime.getRuntime().maxMemory() / 4)
                            .coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024)
                            .toInt()
                    )
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build()
            )
            .build()
            .apply {
                setMediaItem(mediaItemForTier(0))
                if (request.resumeAtSeconds > 0) seekTo(request.resumeAtSeconds * 1000L)
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        faultMessage = error.errorCodeName
                        faultNonce += 1
                    }
                })
            }
    }

    // Each fault climbs the ladder. Position is carried across every rung so
    // a recovered stream resumes where the picture died, not at zero.
    LaunchedEffect(faultNonce) {
        if (faultNonce == 0L) return@LaunchedEffect
        lastFaultAt = System.currentTimeMillis()
        val position = maxOf(lastGoodPositionMs, player.currentPosition.coerceAtLeast(0))
        when (recovery.next()) {
            RecoveryLadder.Step.RETRY -> {
                recoveringLabel = "Reconnecting…"
                player.setMediaItem(mediaItemForTier(recovery.tier), position)
                player.prepare()
                player.playWhenReady = true
            }
            RecoveryLadder.Step.SWITCH_TO_RELAY -> {
                recoveringLabel = "Switching to the server relay…"
                player.setMediaItem(mediaItemForTier(1), position)
                player.prepare()
                player.playWhenReady = true
            }
            RecoveryLadder.Step.GIVE_UP -> {
                recoveringLabel = null
                fatal = faultMessage ?: "Playback failed"
            }
        }
        controlsVisible = true
        controlsShownAt = System.currentTimeMillis()
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
        var stallSinceMs = -1L
        var stallAnchorMs = -1L
        while (true) {
            isPlaying = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING

            // Healthy playback clears the recovery banner, remembers where the
            // picture is, and — after a minute of it — forgives the ladder, so
            // an error at minute 40 starts fresh instead of inheriting the
            // strikes of one at minute 2.
            if (player.isPlaying) {
                recoveringLabel = null
                if (player.currentPosition > 0) lastGoodPositionMs = player.currentPosition
                if (lastFaultAt > 0 && System.currentTimeMillis() - lastFaultAt > 60_000) {
                    recovery.reset()
                    lastFaultAt = 0
                }
            }

            // The stall watchdog: a player can also die without a fatal error,
            // buffering forever against a source that stopped answering. That
            // is the same black screen to the person on the couch, so after
            // 45 seconds pinned in place it climbs the same ladder. Measured
            // on the wall clock, not by counting loop turns — a throttled
            // delay() stretches the loop, and a watchdog that ticks slower
            // exactly when the system is struggling guards nothing.
            if (buffering && fatal == null) {
                val now = System.currentTimeMillis()
                if (player.currentPosition != stallAnchorMs || stallSinceMs < 0) {
                    stallAnchorMs = player.currentPosition
                    stallSinceMs = now
                }
                if (now - stallSinceMs >= 45_000) {
                    stallSinceMs = now
                    faultMessage = "Stream stalled"
                    faultNonce += 1
                }
            } else {
                stallSinceMs = -1
                stallAnchorMs = -1
            }

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

    // What this screen is doing, as one snapshot. `paused` reports intent
    // (playWhenReady), not isPlaying: a buffering player that means to play is
    // "playing, buffering" to the remote, or its play button reads paused and
    // a tap on it does nothing.
    fun publishState() {
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
                paused = !player.playWhenReady,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                hasNext = request.nextEpisodeLabel != null,
                hasPrevious = request.prevEpisodeLabel != null,
                // The badge's driver, on the same 12-second freshness.
                controlledBy = driver,
            )
        )
    }

    // Tell the account's phones what is on screen. Once a second, which is what
    // the phone's remote is built to interpolate between; transitions echo
    // immediately from the command collector below.
    LaunchedEffect(player, request.directUrl) {
        while (true) {
            publishState()
            delay(1_000)
        }
    }

    // Commands from a phone. Raising the controls on each one is deliberate:
    // someone watching should see that the picture changed because a person did
    // it, not wonder whether the app misbehaved.
    var driverSeenAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(driverSeenAt) {
        if (driverSeenAt == 0L) return@LaunchedEffect
        // A controller that goes quiet is no longer visibly in charge; the
        // badge fades rather than naming a phone someone put down an hour ago.
        delay(12_000)
        if (System.currentTimeMillis() - driverSeenAt >= 12_000) driver = null
    }

    LaunchedEffect(player) {
        receiver.transport.collect { command ->
            driver = receiver.controlledBy.value
            driverSeenAt = System.currentTimeMillis()
            show()
            when (command) {
                is CastCommand.Pause -> player.pause()
                is CastCommand.Resume -> player.play()
                is CastCommand.Seek -> player.seekTo(command.positionMs)
                is CastCommand.Stop -> onBack()
                is CastCommand.Next -> request.nextEpisodeLabel?.let(onNextEpisode)
                // The same navigation as Next: onNextEpisode is really "play
                // the episode with this label" and does not care which way.
                is CastCommand.Previous -> request.prevEpisodeLabel?.let(onNextEpisode)
                is CastCommand.Play -> Unit // handled by the app root, which navigates
                // A television is already edge to edge; the command exists for
                // windowed receivers like a browser.
                is CastCommand.Fullscreen -> Unit
            }
            // The echo: whoever pressed the button on a phone is watching
            // their remote for the answer, and the next heartbeat is up to a
            // second away. A moment's delay lets the player settle first.
            if (command !is CastCommand.Stop && command !is CastCommand.Play) {
                delay(60)
                publishState()
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
                // So does the error panel: an errored player ignores play()
                // anyway, and the panel's own buttons need the remote.
                if (fatal != null) return@onKeyEvent false
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
                    // The person holding this television's remote outranks the
                    // account: MENU while being driven refuses remote control —
                    // the set leaves every picker and goes deaf to commands
                    // until the app restarts. Only while driven, so the key
                    // stays free for anything else the rest of the time.
                    Key.Menu -> {
                        if (driver != null) {
                            receiver.refuse()
                            driver = null
                            show()
                            true
                        } else false
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

        driver?.let { name ->
            // The driven screen names its driver, with a pulse so it reads as
            // live rather than as a stale caption.
            val pulse by rememberInfiniteTransition(label = "driven")
                .animateFloat(
                    initialValue = 1f,
                    targetValue = 0.4f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "driven-dot",
                )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .alpha(pulse)
                        .background(StreamHubColors.Accent, CircleShape)
                )
                Text(
                    "Controlled from $name",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.92f),
                )
                // Names the way out, for whoever is holding this remote.
                Text(
                    "· MENU to stop",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        }

        if ((buffering || recoveringLabel != null) && fatal == null) {
            Text(
                recoveringLabel ?: "Loading…",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (fatal != null) {
            PlaybackErrorPanel(
                message = fatal!!,
                onRetry = {
                    fatal = null
                    recovery.reset()
                    recoveringLabel = "Reconnecting…"
                    player.setMediaItem(mediaItemForTier(0), lastGoodPositionMs)
                    player.prepare()
                    player.playWhenReady = true
                },
                onBack = onBack,
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
private fun PlaybackErrorPanel(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
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
                "Playback stopped",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                // The error code name, not a stack trace: enough to report,
                // short enough to read from a couch.
                message,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton(
                label = "Try again",
                primary = true,
                onClick = onRetry,
                modifier = Modifier.focusRequester(focus),
            )
            TvButton(label = "Back", onClick = onBack)
        }
    }
}

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
