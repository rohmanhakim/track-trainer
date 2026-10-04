package io.github.rohmanhakim.tracktrainer.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rohmanhakim.tracktrainer.R
import io.github.rohmanhakim.tracktrainer.data.local.Track

private val VIDEO_MIME_TYPES = arrayOf("video/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onTrackClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // SAF picker: reference-only pick, no copy (D11); launcher must outlive recomposition.
    // pendingRelinkId routes the pick: null = add new track, set = re-link that row;
    // cleared before dispatch so a cancelled pick can't hijack the next add.
    var pendingRelinkId by rememberSaveable { mutableStateOf<Long?>(null) }
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val relinkId = pendingRelinkId
        pendingRelinkId = null
        if (uri != null) {
            if (relinkId != null) viewModel.relinkTrack(relinkId, uri) else viewModel.addTrack(uri)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { pickLauncher.launch(VIDEO_MIME_TYPES) }) {
                        Text("+ " + stringResource(R.string.library_add_track))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (uiState.tracks.isEmpty()) {
            EmptyLibrary(
                onAddTrack = { pickLauncher.launch(VIDEO_MIME_TYPES) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(uiState.tracks, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        missing = track.id in uiState.missingIds,
                        onRelink = {
                            pendingRelinkId = track.id
                            pickLauncher.launch(VIDEO_MIME_TYPES)
                        },
                        onClick = { onTrackClick(track.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, missing: Boolean, onRelink: () -> Unit, onClick: () -> Unit) {
    ListItem(
        // Missing rows stay tappable: the player health-checks on open and shows the
        // error there (T04's "on track open" check) instead of crashing.
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                if (missing) {
                    Text(
                        text = stringResource(R.string.library_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (track.durationMs > 0L) {
                    Text(
                        text = formatDuration(track.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingContent = if (missing) {
            {
                TextButton(onClick = onRelink) {
                    Text(stringResource(R.string.library_relink))
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun EmptyLibrary(
    onAddTrack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.library_empty),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onAddTrack,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(
                text = "+ " + stringResource(R.string.library_add_track),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/** m:ss for <1 h, h:mm:ss beyond; minutes/seconds zero-padded to keep rows stable. */
internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
