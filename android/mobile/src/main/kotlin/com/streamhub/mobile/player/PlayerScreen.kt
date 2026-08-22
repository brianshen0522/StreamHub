package com.streamhub.mobile.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamhub.core.model.AdCut
import com.streamhub.mobile.AppContainer
import com.streamhub.mobile.PlaybackRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val CONTROLS_TIMEOUT_MS = 3_500L
private const val SKIP_MS = 10_000L
private const val HOLD_SPEED = 2f
private const val HOLD_THRESHOLD_MS = 350L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    container: AppContainer,
    request: PlaybackRequest,
    viewModel: PlayerViewModel,
    onNextEpisode: (String) -> Unit,
    onBack: () -> Unit,
    castAction: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val api = container.api

    val state = remember { PlayerUiState() }
    var controlsVisible by remember { mutableStateOf(true) }
    var isFullscreen by remember { mutableStateOf(false) }
    var adCuts by remember { mutableStateOf<List<AdCut>>(emptyList()) }
    var flash by remember { mutableStateOf<String?>(null) }
    var holdingSpeed by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var fillScreen by remember { mutableStateOf(false) }
    val view = LocalView.current

    // Rotating is not full screen on its own. Without hiding the bars the video
    // sits between a status bar and a navigation bar, which is what made the
    // mode feel like nothing had happened.
    DisposableEffect(isFullscreen) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (isFullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Let the picture run under a notch in landscape rather than
                // losing a black band the width of the cutout.
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = if (isFullscreen) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    }
                }
            }
        }
        onDispose {
            // Leaving the player must not leave the rest of the app without bars.
            activity?.window?.let { WindowCompat.getInsetsController(it, view) }
                ?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val player = remember(request.directUrl) {
        // Media requests go through the same authenticated client the rest of the
        // app uses, so they carry the bearer token — including the second request
        // a master playlist causes — and a token that lapses mid-playback is
        // renewed by the same single-flight refresher rather than ending the
        // stream.
        // OkHttp for the network, but wrapped: OkHttp refuses file:// URIs
        // outright, and a downloaded episode is exactly that. The default
        // factory routes each scheme to the right reader and hands http(s) to
        // the OkHttp one.
        val dataSourceFactory = DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(api.authenticatedClient),
        )

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                // The cleaned manifest, not the raw source: ads are already cut
                // and segments point straight at the CDN.
                //
                // The MIME type is not optional here. ExoPlayer infers the format
                // from the URI's extension, and this URL ends in a query string
                // rather than .m3u8, so without the hint it picks the progressive
                // source, fails to sniff the playlist with file extractors and
                // dies with UnrecognizedInputFormatException.
                // A downloaded episode is a plain file on this phone: no
                // manifest to fetch, no HLS to hint, nothing behind auth.
                val isLocal = request.directUrl.startsWith("file:")
                setMediaItem(
                    if (isLocal) {
                        MediaItem.fromUri(request.directUrl)
                    } else {
                        MediaItem.Builder()
                            .setUri(api.manifestUrl(request.directUrl))
                            .setMimeType(MimeTypes.APPLICATION_M3U8)
                            .build()
                    }
                )
                if (request.resumeAtSeconds > 0) seekTo(request.resumeAtSeconds * 1000L)
                prepare()
                playWhenReady = true
            }
    }

    // Where the ads were, so the scrub bar can mark them. The picture jumps at
    // each splice, and an unexplained jump reads as a broken stream.
    LaunchedEffect(request.directUrl) {
        // A local file already had its ads cut when it was downloaded, and
        // asking the server about a file:// path would just 400.
        if (!request.directUrl.startsWith("file:")) {
            adCuts = runCatching { api.adCuts(request.directUrl).cuts }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(player) {
        while (true) {
            state.readFrom(player)
            if (player.isPlaying) {
                viewModel.report(player.currentPosition, player.duration.coerceAtLeast(0), "progress")
            }
            delay(500)
        }
    }

    // Controls get out of the way on their own, but never while paused — a
    // paused player with hidden controls offers no way to start it again except
    // guessing that a tap will work.
    LaunchedEffect(controlsVisible, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    LaunchedEffect(flash) {
        if (flash != null) {
            delay(700)
            flash = null
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    viewModel.report(player.currentPosition, player.duration.coerceAtLeast(0), "pause")
                    controlsVisible = true
                }
            }

            override fun onTracksChanged(tracks: Tracks) = state.readTracks(tracks)

            override fun onPlaybackStateChanged(playbackState: Int) {
                ended = playbackState == Player.STATE_ENDED
                if (ended) {
                    // Finished means finished: report the end position so the
                    // server marks it complete and the next episode is offered.
                    val duration = player.duration.coerceAtLeast(0)
                    viewModel.report(duration, duration, "ended")
                    controlsVisible = true
                }
            }
        }
        player.addListener(listener)

        onDispose {
            viewModel.report(player.currentPosition, player.duration.coerceAtLeast(0), "pause")
            player.removeListener(listener)
            player.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler {
        if (isFullscreen) {
            isFullscreen = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        // The YouTube gesture: the half of the screen you hit
                        // decides the direction, and no control has to be found.
                        val forward = offset.x > size.width / 2f
                        player.seekTo((player.currentPosition + if (forward) SKIP_MS else -SKIP_MS).coerceAtLeast(0))
                        flash = if (forward) "+10s" else "-10s"
                    },
                    onPress = {
                        // Hold anywhere to run at double speed, release to drop
                        // back. The speed set from the menu is restored rather
                        // than assuming it was 1x, so holding does not quietly
                        // undo a deliberate choice.
                        //
                        // Its own state rather than the transient flash, which
                        // clears itself after a moment — the indicator has to
                        // stay up for as long as the finger is down.
                        val chosen = player.playbackParameters.speed
                        // A finger still down after this long is a hold, not a
                        // tap. Waiting for the release with a timeout is what
                        // distinguishes them; onPress runs in its own coroutine,
                        // so suspending here does not delay tap detection.
                        val releasedQuickly = withTimeoutOrNull(HOLD_THRESHOLD_MS) { tryAwaitRelease() }
                        if (releasedQuickly == null) {
                            player.setPlaybackSpeed(HOLD_SPEED)
                            holdingSpeed = true
                            tryAwaitRelease()
                            player.setPlaybackSpeed(chosen)
                            holdingSpeed = false
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // Pinch to crop the black bars away, spread to bring them back.
                // Two fingers, so it cannot be confused with a tap or a scrub,
                // and it is the gesture people already try on a video.
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom > 1.04f && !fillScreen) {
                        fillScreen = true
                        flash = "Zoomed to fill"
                    } else if (zoom < 0.96f && fillScreen) {
                        fillScreen = false
                        flash = "Fit to screen"
                    }
                }
            }
            .pointerInput(Unit) {
                var startVolume = 0
                var accumulated = 0f
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

                detectVerticalDragGestures(
                    onDragStart = {
                        startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        accumulated = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        // Only the right half adjusts volume; the left half is
                        // left alone so a stray drag over the picture does
                        // nothing surprising.
                        if (change.position.x < size.width / 2f) return@detectVerticalDragGestures
                        accumulated -= dragAmount
                        val steps = (accumulated / (size.height / maxVolume.coerceAtLeast(1))).toInt()
                        val target = (startVolume + steps).coerceIn(0, maxVolume)
                        if (target != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                            flash = "Volume ${(target * 100 / maxVolume.coerceAtLeast(1))}%"
                        }
                    },
                )
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    // The controls above replace these entirely.
                    useController = false
                    setKeepScreenOn(true)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { playerView ->
                // Fill crops the sides off a 16:9 picture to cover a taller
                // screen; fit keeps the whole frame with bars. Neither is right
                // for everyone, so it is a choice rather than a default.
                playerView.resizeMode = if (fillScreen) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        PlayerControls(
            castAction = castAction,
            state = state,
            title = request.title,
            subtitle = listOfNotNull(request.episodeLabel, request.sourceLabel).joinToString(" · "),
            adCuts = adCuts,
            // Hidden while the up-next prompt is up: both sit in the centre
            // of the screen, and a play button underneath the question is one
            // more thing to press that answers nothing.
            visible = controlsVisible && !(ended && request.nextEpisodeLabel != null),
            isFullscreen = isFullscreen,
            onPlayPause = {
                if (player.isPlaying) player.pause() else player.play()
                controlsVisible = true
            },
            onSeekTo = { player.seekTo(it) },
            onScrub = { controlsVisible = true },
            onSkip = { delta ->
                player.seekTo((player.currentPosition + delta).coerceAtLeast(0))
                controlsVisible = true
            },
            onSpeed = { player.setPlaybackSpeed(it) },
            onQuality = { height -> state.selectedHeight = height; player.applyQuality(height) },
            onSubtitles = { on -> state.subtitlesOn = on; player.applySubtitles(on) },
            fillScreen = fillScreen,
            onFillScreen = { fillScreen = it },
            onFullscreen = {
                isFullscreen = !isFullscreen
                activity?.requestedOrientation = if (isFullscreen) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            },
            onPip = { activity?.enterPip() },
            onBack = onBack,
        )

        // Only once the episode is over. A permanent button for it sat in the
        // middle row all through playback and pushed the play control off centre.
        val nextLabel = request.nextEpisodeLabel
        if (ended && nextLabel != null) {
            UpNextPrompt(
                label = nextLabel,
                onPlay = { onNextEpisode(nextLabel) },
                onDismiss = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Nothing to ask about, so nothing to show. Sitting on the last frame
        // of a finished episode is a screen with no way out of it that is not
        // the back gesture, which is worse than simply leaving.
        if (ended && nextLabel == null) {
            LaunchedEffect(Unit) { onBack() }
        }

        AnimatedVisibility(
            visible = flash != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Pill(flash.orEmpty())
        }

        // Sits near the top so a finger held in the middle of the screen is not
        // covering the one thing that explains what the finger is doing.
        AnimatedVisibility(
            visible = holdingSpeed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 90.dp),
        ) {
            Pill("${HOLD_SPEED.toInt()}x  ▶▶")
        }
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Activity.enterPip() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    runCatching {
        enterPictureInPictureMode(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
        )
    }
}
