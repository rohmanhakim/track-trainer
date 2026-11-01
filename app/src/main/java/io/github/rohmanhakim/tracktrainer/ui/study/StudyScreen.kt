package io.github.rohmanhakim.tracktrainer.ui.study

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.rohmanhakim.tracktrainer.data.local.LoopPoint
import io.github.rohmanhakim.tracktrainer.library.formatDuration
import io.github.rohmanhakim.tracktrainer.player.PlayerUiState
import io.github.rohmanhakim.tracktrainer.loop.LoopState
import io.github.rohmanhakim.tracktrainer.ui.shared.SpeedControls
import io.github.rohmanhakim.tracktrainer.ui.shared.Transport

// Portrait: video pinned top of the study surface; control surface below
// (01-product-overview §5.2). Slightly under 40 % so the loop controls + Save
// button stay fully above the nav bar even with A/B set (T03 UX fix).
private const val VIDEO_PORTRAIT_HEIGHT_FRACTION = 0.36f

/**
 * Phase 1 portrait "study mode" layout with a graceful landscape fallback.
 *
 * One composable set, two arrangements (02-architecture §2, 01-product-overview §5.2):
 * - Portrait: video top ~40 %, control surface (transport + speed + loop) below.
 *   Phase 3 replaces the disabled "Loop (A–B)" placeholder with real loop controls.
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
    loopState: LoopState,
    loopPoints: List<LoopPoint>,
    onSetA: () -> Unit,
    onSetB: () -> Unit,
    onClearA: () -> Unit,
    onClearB: () -> Unit,
    onToggleLoop: () -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onSelectLoop: (Long) -> Unit,
    onSaveLoop: (String) -> Unit,
    onRenameLoop: (Long, String) -> Unit,
    onDeleteLoop: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Rename / delete dialog state (Phase 3 loop points).
    var renameDialogOpen by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf(-1L) }
    var renameName by remember { mutableStateOf("") }
    var deleteDialogOpen by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf(-1L) }

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
                loopState = uiState.loopState,
                loopPoints = uiState.loopPoints,
                onSetA = onSetA,
                onSetB = onSetB,
                onClearA = onClearA,
                onClearB = onClearB,
                onToggleLoop = onToggleLoop,
                onStepBack = onStepBack,
                onStepForward = onStepForward,
                onSelectLoop = onSelectLoop,
                onSaveLoop = onSaveLoop,
                onRenameClick = { id ->
                    renameId = id
                    renameName = uiState.loopPoints.find { it.id == id }?.label ?: ""
                    renameDialogOpen = true
                },
                onDeleteClick = { id ->
                    deleteId = id
                    deleteDialogOpen = true
                },
                modifier = content,
            )
        }
    }

    // --- Phase 3 loop-point dialogs -------------------------------------

    // Rename loop point: prefill with the current label, confirm renames it.
    if (renameDialogOpen) {
        AlertDialog(
            onDismissRequest = { renameDialogOpen = false },
            title = { Text(stringResource(R.string.loop_rename)) },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    label = { Text(stringResource(R.string.loop_label_hint)) },
                    maxLines = 1,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = renameName.trim().takeIf { it.isNotBlank() } ?: renameName
                        onRenameLoop(renameId, name)
                        renameDialogOpen = false
                        renameName = ""
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Delete loop point confirmation.
    if (deleteDialogOpen) {
        AlertDialog(
            onDismissRequest = { deleteDialogOpen = false },
            title = { Text(stringResource(R.string.loop_delete)) },
            text = { Text("Delete this loop point?") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteLoop(deleteId)
                    deleteDialogOpen = false
                }) {
                    Text(stringResource(R.string.loop_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
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
    loopState: LoopState,
    loopPoints: List<LoopPoint>,
    onSetA: () -> Unit,
    onSetB: () -> Unit,
    onClearA: () -> Unit,
    onClearB: () -> Unit,
    onToggleLoop: () -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onSelectLoop: (Long) -> Unit,
    onSaveLoop: (String) -> Unit,
    onRenameClick: (Long) -> Unit,
    onDeleteClick: (Long) -> Unit,
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
                text = error.message,
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
                isPlaying = uiState.isPlayingIntent,
                speed = speed,
                onTogglePlayPause = onTogglePlayPause,
                onSeek = onSeek,
                onSpeedChange = onSpeedChange,
                loopState = loopState,
                loopPoints = loopPoints,
                onSetA = onSetA,
                onSetB = onSetB,
                onClearA = onClearA,
                onClearB = onClearB,
                onToggleLoop = onToggleLoop,
                onStepBack = onStepBack,
                onStepForward = onStepForward,
                onSelectLoop = onSelectLoop,
                onSaveLoop = onSaveLoop,
                onRenameClick = onRenameClick,
                onDeleteClick = onDeleteClick,
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
    loopState: LoopState,
    loopPoints: List<LoopPoint>,
    onSetA: () -> Unit,
    onSetB: () -> Unit,
    onClearA: () -> Unit,
    onClearB: () -> Unit,
    onToggleLoop: () -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onSelectLoop: (Long) -> Unit,
    onSaveLoop: (String) -> Unit,
    onRenameClick: (Long) -> Unit,
    onDeleteClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Scrollable so every control stays reachable even on short screens or when
    // the loop-points list grows (T03 UX fix).
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
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
        // Phase 3: real loop controls replace the disabled "Loop (A–B)" placeholder.
        LoopControls(
            loopState = loopState,
            onSetA = onSetA,
            onSetB = onSetB,
            onClearA = onClearA,
            onClearB = onClearB,
            onToggleLoop = onToggleLoop,
            onStepBack = onStepBack,
            onStepForward = onStepForward,
        )
        // Save (always creates a new named loop point — multiple loops per track).
        Button(
            onClick = { onSaveLoop("Loop ${loopPoints.size + 1}") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(text = stringResource(R.string.loop_save))
        }
        LoopPointsList(
            loopPoints = loopPoints,
            activeId = loopState.activeLoopPointId,
            onSelect = onSelectLoop,
            onRename = onRenameClick,
            onDelete = onDeleteClick,
        )
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
                text = uiState.error.message,
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
                    isPlaying = uiState.isPlayingIntent,
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

