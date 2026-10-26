package io.github.rohmanhakim.tracktrainer.ui.study

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import io.github.rohmanhakim.tracktrainer.R
import io.github.rohmanhakim.tracktrainer.library.formatDuration
import io.github.rohmanhakim.tracktrainer.player.PlayerUiState
import io.github.rohmanhakim.tracktrainer.ui.shared.SpeedControls
import io.github.rohmanhakim.tracktrainer.ui.shared.Transport

// Portrait: video pinned top ~40 % of the study surface; control surface below
// (01-product-overview §5.2).
private const val VIDEO_PORTRAIT_HEIGHT_FRACTION = 0.40f

/**
 * Phase 1 portrait "study mode" layout with a graceful landscape fallback.
 *
 * One composable set, two arrangements (02-architecture §2, 01-product-overview §5.2):
 * - Portrait: video top ~40 %, control surface (transport + speed) below, then disabled
 *   placeholder slots for loop / tap tempo / count-in (Phases 3–5 fill them).
 * - Landscape: full-bleed video with a minimal controls strip at the bottom (full HUD
 *   deferred to a later phase).
 *
 * Pure UI: receives [PlayerUiState] and callbacks; playback lifecycle (open/release/
 * keep-screen-on / pause-on-background) stays in PlayerScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    uiState: PlayerUiState,
    player: Player?,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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
        val content = Modifier.fillMaxSize().padding(padding)
        if (isLandscape) {
            LandscapeFallback(
                uiState = uiState,
                player = player,
                onTogglePlayPause = onTogglePlayPause,
                onSeek = onSeek,
                speed = speed,
                onSpeedChange = onSpeedChange,
                modifier = content,
            )
        } else {
            PortraitStudy(
                uiState = uiState,
                player = player,
                onTogglePlayPause = onTogglePlayPause,
                onSeek = onSeek,
                speed = speed,
                onSpeedChange = onSpeedChange,
                modifier = content,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortraitStudy(
    uiState: PlayerUiState,
    player: Player?,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = uiState.error

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Video pinned top ~40 % (letterboxed black box when no player is attached yet).
        PlayerSurface(
            player = player,
            modifier = Modifier
                .weight(VIDEO_PORTRAIT_HEIGHT_FRACTION)
                .fillMaxWidth()
                .background(Color.Black),
        )

        when {
            error != null -> Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f - VIDEO_PORTRAIT_HEIGHT_FRACTION)
                    .fillMaxWidth()
                    .padding(24.dp),
            )
            player == null -> Box(
                modifier = Modifier
                    .weight(1f - VIDEO_PORTRAIT_HEIGHT_FRACTION)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            else -> ControlSurface(
                positionMs = uiState.positionMs,
                durationMs = uiState.durationMs,
                isPlaying = uiState.isPlaying,
                speed = speed,
                onTogglePlayPause = onTogglePlayPause,
                onSeek = onSeek,
                onSpeedChange = onSpeedChange,
                modifier = Modifier
                    .weight(1f - VIDEO_PORTRAIT_HEIGHT_FRACTION)
                    .fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlSurface(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    speed: Float,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Transport(
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            onTogglePlayPause = onTogglePlayPause,
            onSeek = onSeek,
        )
        SpeedControls(
            speed = speed,
            onSpeedChange = onSpeedChange,
        )
        // Disabled placeholder slots for Phase 3–5 controls — layout structure lands now.
        PlaceholderSlot("Loop (A–B)")
        PlaceholderSlot("Tap tempo")
        PlaceholderSlot("Count-in / metronome")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LandscapeFallback(
    uiState: PlayerUiState,
    player: Player?,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        PlayerSurface(
            player = player,
            modifier = Modifier.fillMaxSize().background(Color.Black),
        )

        if (uiState.error != null) {
            Text(
                text = uiState.error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
                textAlign = TextAlign.Center,
            )
        } else if (player == null) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Transport(
                    positionMs = uiState.positionMs,
                    durationMs = uiState.durationMs,
                    isPlaying = uiState.isPlaying,
                    onTogglePlayPause = onTogglePlayPause,
                    onSeek = onSeek,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                SpeedControls(
                    speed = speed,
                    onSpeedChange = onSpeedChange,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
                )
            }
        }
    }
}

