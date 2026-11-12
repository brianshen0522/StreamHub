package com.streamhub.tv.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvGradients
import com.streamhub.tv.ui.theme.TvMotion
import kotlinx.coroutines.delay

/**
 * `Backdrop` (`home.rs:313-375`, CSS `styles.css:231-277`): two stacked
 * layers, each a faint full-canvas wash of the poster under the poster itself,
 * right-aligned at full height with its left edge masked. [url] (already
 * proxied, "" for none) swaps in 200 ms after its last change: the back layer
 * takes it and becomes the front once the image has loaded, cross-fading over
 * 420 ms. An empty URL clears the back layer and flips at once, fading to
 * `--bg`. Never more than two images.
 */
@Composable
fun HomeBackdrop(url: String) {
    val layers = remember { mutableStateListOf("", "") }
    var front by remember { mutableIntStateOf(0) }
    LaunchedEffect(url) {
        // A newer change cancels this pending swap.
        delay(BACKDROP_DEBOUNCE_MS)
        val shown = front
        if (layers[shown] == url) return@LaunchedEffect
        val back = 1 - shown
        if (url.isEmpty()) {
            layers[back] = ""
            front = back
        } else {
            // Flipped by the image's load, so it never fades in empty.
            layers[back] = url
        }
    }
    Box(Modifier.fillMaxSize().background(TvColors.Bg)) {
        for (i in 0..1) {
            val src = layers[i]
            val shown = front == i && src.isNotEmpty()
            val a by animateFloatAsState(if (shown) 1f else 0f, TvMotion.tween(TvMotion.TSlow, TvMotion.EaseInOut), label = "backdrop-$i")
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = a }) {
                // A layer with no image is invisible (`img[src=""] { visibility: hidden }`).
                if (src.isNotEmpty()) {
                    AsyncImage(
                        model = src,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(0.16f),
                    )
                    AsyncImage(
                        model = src,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onSuccess = { if (layers[i].isNotEmpty()) front = i },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(360.dp, 540.dp)
                            .graphicsLayer {
                                alpha = 0.92f
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawWithContent {
                                drawContent()
                                // The poster's own left edge fades into the wash (a static mask).
                                drawRect(TvGradients.BackdropMask, blendMode = BlendMode.DstIn)
                            },
                    )
                }
            }
        }
        // `.tv-scrim`: three gradients, the first listed painted on top.
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(TvGradients.HomeScrimBottom)
                drawRect(TvGradients.HomeScrimLeft)
                drawRect(TvGradients.homeScrimTop(density))
            },
        )
    }
}
