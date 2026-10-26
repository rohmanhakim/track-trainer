package io.github.rohmanhakim.tracktrainer.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import io.github.rohmanhakim.tracktrainer.ui.study.StudyScreen

/**
 * T07 player screen: thin orientation-aware adapter.
 *
 * Owns the playback lifecycle (open/release, keep-screen-on, pause-on-background) and
 * manual navigation; delegates layout to [StudyScreen], which arranges video + controls
 * for portrait study mode with a graceful landscape fallback (P1-4). The ViewModel owns
 * the ExoPlayer and survives rotation; back releases the player before returning to the
 * library.
 */
@Composable
fun PlayerScreen(
    trackId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()

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

    StudyScreen(
        uiState = uiState,
        player = player,
        onBack = onBack,
        onTogglePlayPause = { viewModel.togglePlayPause() },
        onSeek = { viewModel.seekTo(it) },
        speed = uiState.speed,
        onSpeedChange = { viewModel.setSpeed(it) },
        modifier = modifier,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
