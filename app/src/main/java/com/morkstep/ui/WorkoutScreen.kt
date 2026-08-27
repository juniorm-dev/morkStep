package com.morkstep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.morkstep.data.IntervalConfig
import com.morkstep.data.PhaseType
import com.morkstep.engine.LiveState

private fun phaseColor(phase: PhaseType): Color = when (phase) {
    PhaseType.WARMUP -> Color(0xFF58A05C)
    PhaseType.FAST -> Color(0xFFD1402A)
    PhaseType.SLOW -> Color(0xFF2E7AC4)
    PhaseType.COOLDOWN -> Color(0xFF7B8A99)
}

private fun formatClock(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}

@Composable
fun WorkoutScreen(
    live: LiveState,
    config: IntervalConfig,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))

        // Phase pill
        Surface(
            shape = CircleShape,
            color = phaseColor(live.phase),
            modifier = Modifier.padding(4.dp),
        ) {
            Text(
                live.phase.label(),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Big timer: seconds remaining in current phase
        val remain = (config.segments.getOrNull(live.phaseIndex)?.seconds ?: 0) - live.secondsInPhase
        Text(
            formatClock(remain.coerceAtLeast(0)),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "interval time",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(4.dp))

        // Overall progress
        val progress = if (live.totalPlannedSec > 0) {
            live.totalSeconds.toFloat() / live.totalPlannedSec
        } else 0f
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
        )
        Text(
            "seg ${live.phaseIndex + 1}/${live.totalSegments} · ${formatClock(live.totalSeconds)} elapsed",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(20.dp))

        // Sensor cards
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SensorCard("PACE", live.pace?.let { "%.1f".format(it) + " km/h" } ?: "–", Modifier.weight(1f))
            SensorCard("HEART", live.hr?.let { "$it bpm" } ?: "–", Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Push done", style = MaterialTheme.typography.labelMedium)
                    Text("${live.fastSegmentsDone}", style = MaterialTheme.typography.titleLarge)
                }
            }
            Card(modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Over ceiling", style = MaterialTheme.typography.labelMedium)
                    Text("${live.overCeilingSec}s", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onStop) {
                Text("End")
            }
            Button(
                onClick = onStop,
                enabled = live.finished,
            ) {
                Text(if (live.finished) "Done" else "Finish early")
            }
        }

        if (live.finished) {
            Text(
                "Workout saved to history",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SensorCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

private fun PhaseType.label(): String = when (this) {
    PhaseType.WARMUP -> "WARM UP"
    PhaseType.FAST -> "PUSH"
    PhaseType.SLOW -> "RECOVERY"
    PhaseType.COOLDOWN -> "COOL DOWN"
}