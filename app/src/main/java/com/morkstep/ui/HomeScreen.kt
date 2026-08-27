package com.morkstep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.morkstep.data.IntervalConfig
import com.morkstep.data.PhaseType

@Composable
fun HomeScreen(
    config: IntervalConfig,
    onStart: () -> Unit,
    onConfig: () -> Unit,
    onHistory: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("morkStep", style = MaterialTheme.typography.headlineLarge)
        Text("Interval Walking Training", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Workout plan", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val fast = config.segments.count { it.type == PhaseType.FAST }
                val totalMin = config.totalSeconds / 60
                Text("$fast push intervals · ~$totalMin min total")
                Text(
                    "Pace ${config.paceFloor}–${config.paceCeiling} km/h  ·  HR ${config.hrFloor}–${config.hrCeiling} bpm",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("Start workout", style = MaterialTheme.typography.titleMedium)
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