package com.morkstep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.morkstep.data.WorkoutLength
import com.morkstep.data.WorkoutProfile
import com.morkstep.engine.LiveState

private fun phaseColor(phase: com.morkstep.data.PhaseType): Color = when (phase) {
    com.morkstep.data.PhaseType.WARMUP -> Color(0xFF58A05C)
    com.morkstep.data.PhaseType.FAST -> Color(0xFFD1402A)
    com.morkstep.data.PhaseType.SLOW -> Color(0xFF2E7AC4)
    com.morkstep.data.PhaseType.COOLDOWN -> Color(0xFF7B8A99)
}

private fun formatClock(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Suppress("FunctionName")
@Composable
fun WorkoutScreen(
    live: LiveState,
    profile: WorkoutProfile,
    simulated: Boolean,
    onEnd: () -> Unit,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val adhoc = profile.lengthMode == WorkoutLength.ADHOC
    var phaseView by rememberSaveable { mutableStateOf(WorkoutPhaseView.BARS) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))

        Text(profile.name, style = MaterialTheme.typography.titleMedium)
        Text(live.lengthLabel.ifEmpty { profile.lengthLabel() }, style = MaterialTheme.typography.bodySmall)
        if (simulated) {
            Text(
                "Simulated sensors (debug) — no live hardware readings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Spacer(Modifier.height(8.dp))

        Surface(
            shape = CircleShape,
            color = if (live.paused) Color(0xFF9E9E9E) else phaseColor(live.phase),
            modifier = Modifier.padding(4.dp),
        ) {
            Text(
                if (live.paused) "PAUSED" else live.phase.label(),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Timer display: count down (time left in phase) or count up (seconds
        // elapsed in phase). Adhoc phases have fixed lengths too — only the
        // overall workout end is open — so the toggle applies to every mode.
        var countUp by rememberSaveable { mutableStateOf(false) }
        val segSec = when (live.phase) {
            com.morkstep.data.PhaseType.FAST -> profile.fastSec
            com.morkstep.data.PhaseType.SLOW -> profile.slowSec
            com.morkstep.data.PhaseType.WARMUP -> profile.warmupSec
            com.morkstep.data.PhaseType.COOLDOWN -> profile.cooldownSec
        }
        val showCountdown = !countUp
        val bigSeconds = if (showCountdown) (segSec - live.secondsInPhase).coerceAtLeast(0)
        else live.secondsInPhase
        Text(
            formatClock(bigSeconds),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (showCountdown) "time left in phase" else "seconds in phase",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Clear visual gap between the switch pill and its label.
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Switch(checked = countUp, onCheckedChange = { countUp = it })
            Text(if (countUp) "Count up" else "Count down", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(4.dp))

        // Overall progress: finite modes only
        if (live.progress != null) {
            LinearProgressIndicator(
                progress = { live.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
            Text(
                "${formatClock(live.totalSeconds)} elapsed",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "${formatClock(live.totalSeconds)} elapsed",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(20.dp))

        // Sensor cards
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SensorCard("PACE", live.pace?.let { "%.1f".format(it) + " mph" } ?: "–", Modifier.weight(1f))
            SensorCard("HEART", live.hr?.let { "$it bpm" } ?: "–", Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SensorCard("PUSH", "${live.fastSegmentsDone}", Modifier.weight(1f))
            SensorCard("DIST (mi)", "%.2f".format(live.distanceMiles), Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))

        WorkoutPhasePanel(
            live = live,
            profile = profile,
            view = phaseView,
            onViewChange = { phaseView = it },
        )

        Spacer(Modifier.height(20.dp))

        // Slim horizontal padding so "Finish early" fits on one line in its
        // third of the row; centered text keeps wrapped lines balanced on
        // narrower screens.
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("Discard", textAlign = TextAlign.Center)
            }
            Button(
                onClick = onTogglePause,
                modifier = Modifier.weight(1f),
                enabled = !live.finished,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(if (live.paused) "Resume" else "Pause", textAlign = TextAlign.Center)
            }
            Button(
                onClick = onEnd,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    if (adhoc) "Finish" else if (live.finished) "Done" else "Finish early",
                    textAlign = TextAlign.Center,
                )
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

@Suppress("FunctionName")
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

private fun com.morkstep.data.PhaseType.label(): String = when (this) {
    com.morkstep.data.PhaseType.WARMUP -> "WARM UP"
    com.morkstep.data.PhaseType.FAST -> "PUSH"
    com.morkstep.data.PhaseType.SLOW -> "RECOVERY"
    com.morkstep.data.PhaseType.COOLDOWN -> "COOL DOWN"
}