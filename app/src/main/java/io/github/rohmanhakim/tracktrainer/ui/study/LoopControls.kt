package io.github.rohmanhakim.tracktrainer.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rohmanhakim.tracktrainer.R
import io.github.rohmanhakim.tracktrainer.library.formatDuration
import io.github.rohmanhakim.tracktrainer.loop.LoopState

/**
 * Phase 3 loop transport per §5.2 portrait layout:
 * `A ●───● B  LOOP` plus frame-step ±1 (F10).
 *
 * Pure UI: state is read from [LoopState], actions are callbacks wired to the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoopControls(
    loopState: LoopState,
    onSetA: () -> Unit,
    onSetB: () -> Unit,
    onClearA: () -> Unit,
    onClearB: () -> Unit,
    onToggleLoop: () -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aSet = loopState.positionA >= 0L
    val bSet = loopState.positionB >= 0L
    val aLabel = if (aSet) "A ${formatDuration(loopState.positionA)}" else stringResource(R.string.loop_set_a)
    val bLabel = if (bSet) "${formatDuration(loopState.positionB)} B" else stringResource(R.string.loop_set_b)

    // Stacked rows with weighted cells so no button is ever squeezed below a
    // tappable width (the old single SpaceBetween row overflowed when A+B set).
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        // Row 1: set / move loop points.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onSetA, modifier = Modifier.weight(1f)) {
                Text(text = aLabel)
            }
            Button(onClick = onSetB, modifier = Modifier.weight(1f)) {
                Text(text = bLabel)
            }
        }
        // Row 2: range visual + loop on/off toggle.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (aSet && bSet) {
                    "● ${formatDuration(loopState.positionA)} ─── ${formatDuration(loopState.positionB)} ●"
                } else {
                    "—"
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = loopState.loopEnabled,
                onClick = { onToggleLoop() },
                label = { Text(stringResource(R.string.loop_loop)) },
            )
        }
        // Row 3: clear each point independently (only when set).
        if (aSet || bSet) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (aSet) {
                    TextButton(
                        onClick = onClearA,
                        modifier = Modifier.weight(1f).defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Text(text = "Clear A")
                    }
                }
                if (bSet) {
                    TextButton(
                        onClick = onClearB,
                        modifier = Modifier.weight(1f).defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Text(text = "Clear B")
                    }
                }
            }
        }
        // Row 4: frame step ±1 (P3-5, F10); the ViewModel guards paused-only.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onStepBack, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.frame_step_back))
            }
            Button(onClick = onStepForward, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.frame_step_fwd))
            }
        }
    }
}
