package com.morkstep.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.morkstep.data.VibrationMode
import com.morkstep.data.WorkoutLength
import com.morkstep.data.WorkoutProfile

private val LENGTH_MODES = listOf(
    WorkoutLength.ROUNDS to "Rounds",
    WorkoutLength.DISTANCE to "Distance",
    WorkoutLength.TIME to "Time",
    WorkoutLength.ADHOC to "Adhoc",
)

/** Vibration-mode radio options (labels mirror the user-facing setting names). */
private val VIBRATION_MODES = listOf(
    VibrationMode.OFF to "Off",
    VibrationMode.PHASE_CHANGE to "On phase change",
    VibrationMode.ALL to "All cues",
)

/** Material `Slider` `steps` count giving [step] granularity across [range] (interval count minus the two endpoints). */
private fun sliderSteps(range: ClosedFloatingPointRange<Float>, step: Float): Int =
    ((range.endInclusive - range.start) / step).toInt() - 1

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
    profiles: List<WorkoutProfile>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onSave: (WorkoutProfile) -> Unit,
    onNewProfile: () -> Unit,
    simulated: Boolean,
    sensorNote: String,
    onSimulatedChange: (Boolean) -> Unit,
    wearHr: Boolean,
    onWearHrChange: (Boolean) -> Unit,
    wearVibrate: Boolean,
    onWearVibrateChange: (Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onRequestPermissions: () -> Unit,
    locationGranted: Boolean,
    bluetoothGranted: Boolean,
) {
    val profile = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Profile settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        // Profile picker: select any saved profile to edit it.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Profiles", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = onNewProfile) {
                        Text("Clone")
                    }
                }
                Spacer(Modifier.height(4.dp))
                profiles.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = p.id == selectedId, onClick = { onSelect(p.id) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = p.id == selectedId, onClick = { onSelect(p.id) })
                        Column {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text(p.lengthLabel(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (profile == null) {
            Spacer(Modifier.height(16.dp))
            Text("No profiles yet — tap New to create one.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
        var lengthMode by rememberSaveable(profile.id) { mutableStateOf(profile.lengthMode) }
        var rounds by rememberSaveable(profile.id) { mutableIntStateOf(profile.rounds) }
        var distanceMiles by rememberSaveable(profile.id) { mutableFloatStateOf(profile.distanceMiles.toFloat()) }
        var timeMinutes by rememberSaveable(profile.id) { mutableIntStateOf(profile.timeMinutes) }
        var adhocCueEveryNPush by rememberSaveable(profile.id) { mutableIntStateOf(profile.adhocCueEveryNPush) }
        var fastSec by rememberSaveable(profile.id) { mutableIntStateOf(profile.fastSec) }
        var slowSec by rememberSaveable(profile.id) { mutableIntStateOf(profile.slowSec) }
        var warmupSec by rememberSaveable(profile.id) { mutableIntStateOf(profile.warmupSec) }
        var cooldownSec by rememberSaveable(profile.id) { mutableIntStateOf(profile.cooldownSec) }
        var paceCeil by rememberSaveable(profile.id) { mutableFloatStateOf(profile.paceCeilingMph.toFloat()) }
        var paceFloor by rememberSaveable(profile.id) { mutableFloatStateOf(profile.paceFloorMph.toFloat()) }
        var hrCeil by rememberSaveable(profile.id) { mutableIntStateOf(profile.hrCeiling) }
        var hrFloor by rememberSaveable(profile.id) { mutableIntStateOf(profile.hrFloor) }
        var warnSec by rememberSaveable(profile.id) { mutableIntStateOf(profile.warningThresholdSec) }
        var audio by rememberSaveable(profile.id) { mutableStateOf(profile.audioCues) }
        var vibration by rememberSaveable(profile.id) { mutableStateOf(profile.vibrationMode) }

        Spacer(Modifier.height(16.dp))
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Name", style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Workout length", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp)) {
                LENGTH_MODES.forEach { (mode, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = lengthMode == mode, onClick = { lengthMode = mode })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = lengthMode == mode, onClick = { lengthMode = mode })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (lengthMode) {
                    WorkoutLength.ROUNDS -> SliderRow(
                        "Push rounds", "$rounds",
                        rounds.toFloat(), 1f..20f, 19,
                    ) { rounds = it.toInt() }
                    WorkoutLength.DISTANCE -> SliderRow(
                        "Distance (mi)", "%.1f".format(distanceMiles),
                        distanceMiles, 0.5f..20f, 78,
                    ) { distanceMiles = it }
                    WorkoutLength.TIME -> SliderRow(
                        "Duration (min)", "$timeMinutes min",
                        timeMinutes.toFloat(), 5f..120f, 46,
                    ) { timeMinutes = it.toInt() }
                    WorkoutLength.ADHOC -> SliderRow(
                        "Cue every N push rounds (0 = off)", "$adhocCueEveryNPush",
                        adhocCueEveryNPush.toFloat(), 0f..10f, 10,
                    ) { adhocCueEveryNPush = it.toInt() }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Intervals (seconds)", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SliderRow("Push interval", "${fastSec}s", fastSec.toFloat(), 15f..600f, sliderSteps(15f..600f, 5f)) { fastSec = it.toInt() }
                SliderRow("Recovery interval", "${slowSec}s", slowSec.toFloat(), 15f..600f, sliderSteps(15f..600f, 5f)) { slowSec = it.toInt() }
                SliderRow("Warm-up (0 = none)", "${warmupSec}s", warmupSec.toFloat(), 0f..600f, sliderSteps(0f..600f, 5f)) { warmupSec = it.toInt() }
                SliderRow("Cool-down (0 = none)", "${cooldownSec}s", cooldownSec.toFloat(), 0f..600f, sliderSteps(0f..600f, 5f)) { cooldownSec = it.toInt() }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Pace band (mph)", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SliderRow("Pace ceiling", "%.1f".format(paceCeil), paceCeil, 2f..8f, 24) { paceCeil = it }
                SliderRow("Pace floor", "%.1f".format(paceFloor), paceFloor, 1.5f..7f, 24) { paceFloor = it }
                Text(
                    "Push phase targets between floor and ceiling (mph).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Heart-rate band (bpm)", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SliderRow("HR ceiling", "$hrCeil", hrCeil.toFloat(), 90f..200f, 22) { hrCeil = it.toInt() }
                SliderRow("HR floor", "$hrFloor", hrFloor.toFloat(), 70f..190f, 24) { hrFloor = it.toInt() }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Warning cues", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SliderRow("Repeat warning every", "${warnSec}s", warnSec.toFloat(), 1f..60f, 59) { warnSec = it.toInt() }
                Text(
                    "Push warns when pace or HR drops below the floor; recovery warns when pace or HR " +
                        "exceeds the ceiling. A cue repeats at most once per this interval while out of band.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Vibration", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                VIBRATION_MODES.forEach { (mode, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = vibration == mode, onClick = { vibration = mode })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = vibration == mode, onClick = { vibration = mode })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text(
                    "Off: no haptics. On phase change: buzz at warm-up, push, recovery, cooldown and finish. " +
                        "All cues: also buzz on quarter, push-round and band-warning cues.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Sensors", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Simulated sensors (debug)", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = simulated, onCheckedChange = onSimulatedChange)
                }
                Text(
                    "Off uses real hardware: GPS pace and a Bluetooth heart-rate strap. " +
                        "No automatic fallback — if off and a signal is missing, readings stay blank.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!simulated) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Heart rate from Wear companion", style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = wearHr, onCheckedChange = onWearHrChange)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Vibrate watch", style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = wearVibrate, onCheckedChange = onWearVibrateChange)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Grant sensor permissions")
                        }
                    }
                    Text(
                        "Location: ${if (locationGranted) "granted" else "not granted"} · " +
                            "Bluetooth: ${if (bluetoothGranted) "granted" else "not granted"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        sensorNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                onSave(
                    profile.copy(
                        name = name.ifBlank { "Profile" },
                        lengthMode = lengthMode,
                        rounds = rounds,
                        distanceMiles = distanceMiles.toDouble(),
                        timeMinutes = timeMinutes,
                        adhocCueEveryNPush = adhocCueEveryNPush,
                        fastSec = fastSec,
                        slowSec = slowSec,
                        warmupSec = warmupSec,
                        cooldownSec = cooldownSec,
                        paceCeilingMph = Math.round(paceCeil * 10) / 10.0,
                        paceFloorMph = Math.round(paceFloor * 10) / 10.0,
                        hrCeiling = hrCeil,
                        hrFloor = hrFloor,
                        warningThresholdSec = warnSec,
                        audioCues = audio,
                        vibrationMode = vibration,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save profile")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { onDelete(profile.id) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Delete profile")
        }

        Spacer(Modifier.height(24.dp))

        val context = LocalContext.current
        val version = remember {
            runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                info.versionName ?: info.longVersionCode.toString()
            }.getOrNull() ?: "?"
        }
        Text(
            "morkStep  v$version",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
