package io.github.rohmanhakim.tracktrainer.ui.study

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rohmanhakim.tracktrainer.data.local.LoopPoint
import io.github.rohmanhakim.tracktrainer.library.formatDuration

/**
 * Saved loop points for the open track: select (loads A/B into the controller),
 * rename, delete. Empty state shown when none are saved yet.
 */
@Composable
fun LoopPointsList(
    loopPoints: List<LoopPoint>,
    activeId: Long?,
    onSelect: (Long) -> Unit,
    onRename: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (loopPoints.isEmpty()) {
            Text(
                text = "No saved loops yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Cap height and scroll so a growing list never pushes the
            // frame-step / Save buttons off-screen (T03 UX fix).
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                loopPoints.forEach { point ->
                    val selected = point.id == activeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Text(
                                text = point.label,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text =
                                    "${formatDuration(point.positionA)} – ${formatDuration(point.positionB)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onSelect(point.id) }) {
                            Text(text = if (selected) "Active" else "Select")
                        }
                        TextButton(onClick = { onRename(point.id) }) {
                            Text(text = "Rename")
                        }
                        TextButton(onClick = { onDelete(point.id) }) {
                            Text(text = "Delete")
                        }
                    }
                }
            }
        }
    }
}
