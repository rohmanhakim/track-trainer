package io.github.rohmanhakim.tracktrainer.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rohmanhakim.tracktrainer.R
import io.github.rohmanhakim.tracktrainer.library.formatDuration

/**
 * Transport bar: seek scrubber + play/pause button + position/duration labels.
 * Extracted from [io.github.rohmanhakim.tracktrainer.player.PlayerScreen] in T07 so it
 * can be reused by both portrait study mode and the landscape fallback.
 *
 * While dragging, the thumb owns the value; position polling must not fight it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Transport(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val shownMs = (scrubMs ?: positionMs).coerceIn(0L, durationMs.coerceAtLeast(0L))

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
                    text = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                )
            }
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
