package com.streamhub.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * One fixed dark scheme, built from the web client's tokens.
 *
 * This deliberately does *not* use dynamic colour. Taking the palette from the
 * user's wallpaper is the right default for an app with no identity of its own,
 * and the wrong one here: StreamHub is already near-black with a red accent on
 * the web, people move between the two, and a lavender build of it does not read
 * as the same product. It does not follow the system light/dark setting either,
 * for the same reason the web client does not.
 */
private val StreamHubDark = darkColorScheme(
    primary = StreamHubColors.Accent,
    onPrimary = StreamHubColors.T1,
    primaryContainer = StreamHubColors.AccentHi,
    onPrimaryContainer = StreamHubColors.T1,

    secondary = StreamHubColors.S3,
    onSecondary = StreamHubColors.T1,
    secondaryContainer = StreamHubColors.S2,
    onSecondaryContainer = StreamHubColors.T1,

    tertiary = StreamHubColors.Accent,
    onTertiary = StreamHubColors.T1,

    background = StreamHubColors.Bg,
    onBackground = StreamHubColors.T1,

    surface = StreamHubColors.Bg,
    onSurface = StreamHubColors.T1,
    surfaceVariant = StreamHubColors.S2,
    onSurfaceVariant = StreamHubColors.T2,

    // The web stacks four surface levels; Material's containers map onto them.
    surfaceContainerLowest = StreamHubColors.Bg,
    surfaceContainerLow = StreamHubColors.S1,
    surfaceContainer = StreamHubColors.S1,
    surfaceContainerHigh = StreamHubColors.S2,
    surfaceContainerHighest = StreamHubColors.S3,

    outline = StreamHubColors.T3,
    outlineVariant = StreamHubColors.Border,

    error = StreamHubColors.Danger,
    onError = StreamHubColors.Bg,
    errorContainer = StreamHubColors.S2,
    onErrorContainer = StreamHubColors.Danger,

    scrim = StreamHubColors.Bg,
)

@Composable
fun StreamHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StreamHubDark, content = content)
}
