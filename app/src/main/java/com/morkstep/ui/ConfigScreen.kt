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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.morkstep.data.IntervalConfig

/** Generic labeled slider row. */
@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
fun ConfigScreen(
    config: IntervalConfig,
    onSave: (IntervalConfig) -> Unit,
) {
    var fastSec by rememberSaveable { mutableStateOf(config.fastSeconds()) }
    var slowSec by rememberSaveable { mutableStateOf(config.slowSeconds()) }
    var warmSec by rememberSaveable { mutableStateOf(config.warmSeconds()) }
    var coolSec by rememberSaveable { mutableStateOf(config.coolSeconds()) }
    var count by rememberSaveable { mutableStateOf(config.fastCount()) }
    var paceCeil by rememberSaveable { mutableStateOf(config.paceCeiling.toFloat()) }
    var paceFloor by rememberSaveable { mutableStateOf(config.paceFloor.toFloat()) }
    var hrCeil by rememberSaveable { mutableStateOf(config.hrCeiling.toFloat()) }
    var hrFloor by rememberSaveable { mutableStateOf(config.hrFloor.toFloat()) }
    var audio by rememberSaveable { mutableStateOf(config.audioCues) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Interval settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SliderRow(
                    "Push interval", "${fastSec}s",
                    fastSec.toFloat(), 30f..600f, 18,
                ) { fastSec = it.toInt() }
                SliderRow(
                    "Recovery interval", "${slowSec}s",
                    slowSec.toFloat(), 30f..600f, 18,
                ) { slowSec = it.toInt() }
                SliderRow(
                    "Push rounds", "$count",
                    count.toFloat(), 1f..10f, 9,
                ) { count = it.toInt() }
                SliderRow(
                    "Warm-up", "${warmSec}s",
                    warmSec.toFloat(), 0f..600f, 12,
                ) { warmSec = it.toInt() }
                SliderRow(
                    "Cool-down", "${coolSec}s",
                    coolSec.toFloat(), 0f..600f, 12,
                ) { coolSec = it.toInt() }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Pace band (km/h)", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SliderRow(
                    "Pace ceiling", "%.1f".format(paceCeil),
                    paceCeil, 3f..12f, 18,
                ) { paceCeil = it }
                SliderRow(
                    "Pace floor", "%.1f".format(paceFloor),
                    paceFloor, 2f..11f, 18,
                ) { paceFloor = it }
                Text(
                    "Push phase targets between floor and ceiling.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Heart-rate band (bpm)", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SliderRow(
                    "HR ceiling", "${hrCeil.toInt()}",
                    hrCeil, 90f..200f, 22,
                ) { hrCeil = it }
                SliderRow(
                    "HR floor", "${hrFloor.toInt()}",
                    hrFloor, 70f..190f, 24,
                ) { hrFloor = it }
                Text(
                    "Cued when HR exceeds ceiling during push, or pace drops below floor.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Audio cues", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = audio, onCheckedChange = { audio = it })
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                onSave(
                    IntervalConfig(
                        segments = buildList {
                            if (warmSec > 0) add(com.morkstep.data.IntervalSegment(com.morkstep.data.PhaseType.WARMUP, warmSec))
                            repeat(count) {
                                add(com.morkstep.data.IntervalSegment(com.morkstep.data.PhaseType.FAST, fastSec))
                                add(com.morkstep.data.IntervalSegment(com.morkstep.data.PhaseType.SLOW, slowSec))
                            }
                            if (coolSec > 0) add(com.morkstep.data.IntervalSegment(com.morkstep.data.PhaseType.COOLDOWN, coolSec))
                        },
                        paceCeiling = paceCeil.toDouble(),
                        paceFloor = paceFloor.toDouble(),
                        hrCeiling = hrCeil.toInt(),
                        hrFloor = hrFloor.toInt(),
                        audioCues = audio,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save settings")
        }
    }
}

private fun IntervalConfig.fastSeconds(): Int = segments.firstOrNull { it.type == com.morkstep.data.PhaseType.FAST }?.seconds ?: 180
private fun IntervalConfig.slowSeconds(): Int = segments.firstOrNull { it.type == com.morkstep.data.PhaseType.SLOW }?.seconds ?: 180
private fun IntervalConfig.warmSeconds(): Int = segments.firstOrNull { it.type == com.morkstep.data.PhaseType.WARMUP }?.seconds ?: 0
private fun IntervalConfig.coolSeconds(): Int = segments.firstOrNull { it.type == com.morkstep.data.PhaseType.COOLDOWN }?.seconds ?: 0
private fun IntervalConfig.fastCount(): Int = segments.count { it.type == com.morkstep.data.PhaseType.FAST }