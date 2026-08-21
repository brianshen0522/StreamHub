package com.streamhub.tv.ui

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * One fixed dark scheme, built from the web client's tokens.
 *
 * There is no light variant and no dynamic colour. A ten-foot UI is watched in a
 * dark room, and taking the palette from a wallpaper would make this a different
 * product from the web app and the phone — which is exactly what happened on the
 * phone before it was pinned to these values.
 */
private val StreamHubTvDark = darkColorScheme(
    primary = StreamHubColors.Accent,
    onPrimary = StreamHubColors.T1,
    primaryContainer = StreamHubColors.AccentHi,
    onPrimaryContainer = StreamHubColors.T1,

    secondary = StreamHubColors.S3,
    onSecondary = StreamHubColors.T1,
    secondaryContainer = StreamHubColors.S2,
    onSecondaryContainer = StreamHubColors.T1,

    background = StreamHubColors.Bg,
    onBackground = StreamHubColors.T1,

    surface = StreamHubColors.Bg,
    onSurface = StreamHubColors.T1,
    surfaceVariant = StreamHubColors.S2,
    onSurfaceVariant = StreamHubColors.T2,

    // TV Material calls these border rather than outline. The focused-card
    // border is the single most important affordance on this platform, so it
    // gets the accent rather than a grey.
    border = StreamHubColors.Accent,
    borderVariant = StreamHubColors.Border,

    error = StreamHubColors.Danger,
    onError = StreamHubColors.Bg,

    scrim = StreamHubColors.Bg,
)

@Composable
fun StreamHubTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StreamHubTvDark, content = content)
}
