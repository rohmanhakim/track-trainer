package io.github.rohmanhakim.tracktrainer.ui.study

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

/**
 * Media3's PlayerView behind a Compose-agnostic seam: the caller owns sizing/background
 * (portrait ~40% box vs landscape full-bleed), we own the player surface. The default
 * controller is disabled because StudyScreen provides its own Compose transport.
 */
@Composable
fun PlayerSurface(
    player: Player?,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                // Default resize mode is RESIZE_MODE_FIT — kept implicit because
                // AspectRatioFrameLayout's constant is still @UnstableApi (lint error).
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}
