package io.github.rohmanhakim.tracktrainer.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import io.github.rohmanhakim.tracktrainer.R
import io.github.rohmanhakim.tracktrainer.library.formatDuration

private const val VIDEO_ASPECT_RATIO = 16f / 9f

/**
 * T05 player screen: video surface + Compose transport. The ViewModel owns the player
 * (survives rotation); back releases it before returning to the library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    trackId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val error = uiState.error

    LaunchedEffect(trackId) { viewModel.openTrack(trackId) }

    // System back while the player is open: release first so no audio leaks, then
    // let the owner's manual nav state drop back to the library.
    BackHandler {
        viewModel.releasePlayer()
        onBack()
    }

    // P1-5: awake only while actually playing; onDispose resets so the library
    // screen can't inherit a stale keep-screen-on after back-navigation.
    val view = LocalView.current
    DisposableEffect(uiState.isPlaying) {
        view.keepScreenOn = uiState.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    // No MediaSession in Phase 1: leaving the app pauses instead of leaking
    // uncontrolled background audio. Config-change stops are excluded because onStop
    // fires on every recreation — pausing there would break rotation continuity (P1-4).
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && context.findActivity()?.isChangingConfigurations != true) {
                viewModel.pause()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.title.ifEmpty { stringResource(R.string.app_name) },
                        maxLines = 2,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.player_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PlayerSurface(
                player = player,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(VIDEO_ASPECT_RATIO)
                    .background(Color.Black),
            )
            when {
                error != null -> Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                )
                player == null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                else -> Column {
                    Transport(
                        uiState = uiState,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onSeek = viewModel::seekTo,
                    )
                    SpeedControls(
                        speed = uiState.speed,
                        onSpeedChange = viewModel::setSpeed,
                    )
                }
            }
        }
    }
}

/**
 * Media3's PlayerView behind a Compose-agnostic seam: own transport below (the default
 * controller would duplicate it), letterboxed into a fixed 16:9 box for T05 — T07 owns
 * the real study-mode arrangement.
 */
@Composable
private fun PlayerSurface(player: Player?, modifier: Modifier = Modifier) {
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

@Composable
private fun Transport(
    uiState: PlayerUiState,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // While dragging, the thumb owns the value; position polling must not fight it.
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val durationMs = uiState.durationMs
    val shownMs = (scrubMs ?: uiState.positionMs)
        .coerceIn(0L, durationMs.coerceAtLeast(0L))

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Slider(
            value = shownMs.toFloat(),
            onValueChange = { scrubMs = it.toLong() },
            onValueChangeFinished = {
                scrubMs?.let(onSeek)
                scrubMs = null
            },
            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
            enabled = durationMs > 0L,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatDuration(shownMs),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onTogglePlayPause) {
                Text(
                    stringResource(
                        if (uiState.isPlaying) R.string.player_pause else R.string.player_play,
                    ),
                )
            }
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Speed control UI: preset chips (0.5× / 0.75× / 1×) plus a fine slider (0.25×–2×).
 * Value is session-level and applied live via [PlayerViewModel.setSpeed].
 */
@Composable
private fun SpeedControls(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = listOf(0.5f, 0.75f, 1f)
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { preset ->
                Button(
                    onClick = { onSpeedChange(preset) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "${preset.toString()}x")
                }
            }
        }
        var scrubSpeed by remember { mutableStateOf<Float?>(null) }
        val shownSpeed = scrubSpeed ?: speed
        Slider(
            value = shownSpeed,
            onValueChange = { scrubSpeed = it },
            onValueChangeFinished = {
                scrubSpeed?.let(onSpeedChange)
                scrubSpeed = null
            },
            valueRange = 0.25f..2.0f,
            steps = 35, // 0.05× steps
            enabled = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = String.format("x%.2f", shownSpeed),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
