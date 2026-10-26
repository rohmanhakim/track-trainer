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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Speed control UI: preset chips (0.5× / 0.75× / 1×) plus a fine slider (0.25×–2×).
 * Value is session-level and applied live via [io.github.rohmanhakim.tracktrainer.player.PlayerViewModel.setSpeed].
 * Extracted from PlayerScreen in T07 for reuse across orientations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedControls(
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
