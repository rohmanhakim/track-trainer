package io.github.rohmanhakim.tracktrainer.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import io.github.rohmanhakim.tracktrainer.R
import io.github.rohmanhakim.tracktrainer.loop.LoopState
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

    // Every exit path (system back, top-bar arrow, error dialog) tears the player down
    // first so no audio leaks to the library: the ViewModel is activity-scoped and
    // survives leaving composition, so release can't rely on store clearing. Idempotent —
    // safe when an error already tore the player down.
    val onLeave = {
        viewModel.releasePlayer()
        onBack()
    }
    BackHandler(onBack = onLeave)

    // P1-5: awake while playback is armed; onDispose resets so the library
    // screen can't inherit a stale keep-screen-on after back-navigation.
    // Intent (not transient isPlaying) so the seam seek's buffering flicker
    // doesn't toggle the flag every loop.
    val view = LocalView.current
    DisposableEffect(uiState.isPlayingIntent) {
        view.keepScreenOn = uiState.isPlayingIntent
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

    val scope = rememberCoroutineScope()

    StudyScreen(
        uiState = uiState,
        player = player,
        onBack = onLeave,
        onTogglePlayPause = { viewModel.togglePlayPause() },
        onSeek = { viewModel.seekTo(it) },
        speed = uiState.speed,
        onSpeedChange = { viewModel.setSpeed(it) },
        loopState = uiState.loopState,
        loopPoints = uiState.loopPoints,
        onSetA = { viewModel.setLoopA() },
        onSetB = { viewModel.setLoopB() },
        onClearA = { viewModel.clearLoopA() },
        onClearB = { viewModel.clearLoopB() },
        onToggleLoop = { viewModel.toggleLoop() },
        onStepBack = { viewModel.frameStep(false) },
        onStepForward = { viewModel.frameStep(true) },
        onSelectLoop = { id -> scope.launch { viewModel.selectLoopPoint(id) } },
        onSaveLoop = { name -> scope.launch { viewModel.saveLoopPoint(name) } },
        onRenameLoop = { id, name -> scope.launch { viewModel.renameLoopPoint(id, name) } },
        onDeleteLoop = { id -> scope.launch { viewModel.deleteLoopPoint(id) } },
        modifier = modifier,
    )

    // Error overlay: playback can't run — surfaced in the UI, never a crash. The
    // UrlExpired branch covers P2-8: the owner pastes a fresh direct URL to recover.
    val error = uiState.error
    val rePasteInputState = remember { mutableStateOf("") }
    if (error != null) {
        val isExpired = error is UrlExpired
        AlertDialog(
            onDismissRequest = {},
            title = { Text(error.message) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isExpired && uiState.title.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.player_error_expired_hint, uiState.title),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (isExpired) {
                        OutlinedTextField(
                            value = rePasteInputState.value,
                            onValueChange = { rePasteInputState.value = it },
                            label = { Text(stringResource(R.string.label_url)) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 1,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isExpired) {
                            if (rePasteInputState.value.isNotBlank()) viewModel.rePaste(trackId, rePasteInputState.value)
                        } else {
                            onLeave()
                        }
                    },
                    enabled = !isExpired || rePasteInputState.value.isNotBlank(),
                ) {
                    Text(
                        text = if (isExpired) stringResource(R.string.action_re_paste) else stringResource(R.string.player_error_back),
                    )
                }
            },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
