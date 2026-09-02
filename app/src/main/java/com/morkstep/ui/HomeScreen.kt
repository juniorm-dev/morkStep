package com.morkstep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.morkstep.data.VibrationMode
import com.morkstep.data.WorkoutProfile
import com.morkstep.data.isBaselineProfile
import kotlin.math.roundToInt

/** Format a duration in seconds as "m:ss"; under a minute, just "Ns". */
private fun formatSeconds(sec: Int): String =
    if (sec < 60) "${sec}s" else "%d:%02d".format(sec / 60, sec % 60)

@Suppress("FunctionName")
@Composable
fun HomeScreen(
    profiles: List<WorkoutProfile>,
    activeId: Long,
    /** True while a workout session is running (or paused) — the start button turns into a status label. */
    workoutActive: Boolean = false,
    onSelectProfile: (Long) -> Unit,
    onStart: () -> Unit,
    onConfig: () -> Unit,
    onHistory: () -> Unit,
) {
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Text("morkStep", style = MaterialTheme.typography.headlineLarge)
        Text("Interval Walking Training", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Select profile", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                profiles.filterNot { isBaselineProfile(it) }.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = p.id == activeId,
                            onClick = { onSelectProfile(p.id) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${p.lengthLabel()} · pace ${p.paceFloorMph}–${p.paceCeilingMph} mph · HR ${p.hrFloor}–${p.hrCeiling}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        active?.let { p ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Workout plan · ${p.name}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${p.lengthLabel()} · ${formatSeconds(p.fastSec)} push / ${formatSeconds(p.slowSec)} recovery",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Warm-up ${formatSeconds(p.warmupSec)} · cool-down ${formatSeconds(p.cooldownSec)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (p.vibrationMode != VibrationMode.OFF) {
                        Text(
                            "Vibration: ${
                                when (p.vibrationMode) {
                                    VibrationMode.PHASE_CHANGE -> "phase changes"
                                    VibrationMode.ALL -> "all cues"
                                    else -> "off"
                                }
                            } · ${(p.vibrationIntensity * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    if (workoutActive) "Active workout"
                    else if (isBaselineProfile(active)) "Start baseline"
                    else "Start workout",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onConfig, modifier = Modifier.weight(1f)) {
                    Text("Settings")
                }
                OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f)) {
                    Text("History")
                }
            }
        }
    }
}