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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
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
import com.morkstep.data.AudioMode
import com.morkstep.data.DarkMode
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

/** Audio-mode menu options (labels mirror the user-facing setting names). */
private val AUDIO_MODES = listOf(
    AudioMode.OFF to "Off",
    AudioMode.PHASE_CHANGE to "On phase change",
    AudioMode.ALL to "All cues",
)

/** Global dark-mode options (labels mirror the user-facing setting names). */
private val DARK_MODES = listOf(
    DarkMode.SYSTEM to "System",
    DarkMode.DARK to "Dark",
    DarkMode.LIGHT to "Light",
)

/** Material `Slider` `steps` count giving [step] granularity across [range] (interval count minus the two endpoints). */
private fun sliderSteps(range: ClosedFloatingPointRange<Float>, step: Float): Int =
    ((range.endInclusive - range.start) / step).toInt() - 1

@Suppress("FunctionName")
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

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionName")
@Composable
fun ConfigScreen(
    profiles: List<WorkoutProfile>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onSave: (WorkoutProfile) -> Unit,
    onNewProfile: () -> Unit,
    onCreateBaseline: () -> Unit,
    darkMode: DarkMode,
    onDarkModeChange: (DarkMode) -> Unit,
    simulated: Boolean,
    sensorNote: String,
    onSimulatedChange: (Boolean) -> Unit,
    wearHr: Boolean,
    onWearHrChange: (Boolean) -> Unit,
    wearVibrate: Boolean,
    onWearVibrateChange: (Boolean) -> Unit,
    hcBackfillHr: Boolean,
    onHcBackfillChange: (Boolean) -> Unit,
    debugLog: Boolean,
    onDebugLogChange: (Boolean) -> Unit,
    showDebugLog: Boolean,
    onShowDebugLogChange: (Boolean) -> Unit,
    forcePhonePace: Boolean,
    onForcePhonePaceChange: (Boolean) -> Unit,
    batteryUnrestricted: Boolean,
    onRequestBatteryUnrestricted: () -> Unit,
    activityRecognitionGranted: Boolean,
    onRequestActivityRecognition: () -> Unit,
    onMaybeRequestActivityRecognition: () -> Unit,
    hcGranted: Boolean,
    onHealthConnectPermission: () -> Unit,
    onDelete: (Long) -> Unit,
    onRequestPermissions: () -> Unit,
    locationGranted: Boolean,
    bluetoothGranted: Boolean,
    onExportProfiles: () -> Unit,
    onImportProfiles: () -> Unit,
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

        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Baseline", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Creates the Baseline profile: a 3-round calibration workout (45 s push / 45 s recovery, 20 s warm-up). " +
                        "After the workout it becomes your calibrated 30-minute baseline.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onCreateBaseline, modifier = Modifier.fillMaxWidth()) {
                    Text("Create baseline")
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
        var fastSec by rememberSaveable(profile.id) { mutableIntStateOf(profile.pushSec) }
        var slowSec by rememberSaveable(profile.id) { mutableIntStateOf(profile.slowSec) }
        var warmupSec by rememberSaveable(profile.id) { mutableIntStateOf(profile.warmupSec) }
        var cooldownSec by rememberSaveable(profile.id) { mutableIntStateOf(profile.cooldownSec) }
        var paceCeil by rememberSaveable(profile.id) { mutableIntStateOf(profile.recoveryPaceCapSpm) }
        var paceFloor by rememberSaveable(profile.id) { mutableIntStateOf(profile.pushPaceFloorSpm) }
        var hrRecoveryMax by rememberSaveable(profile.id) { mutableIntStateOf(profile.hrRecoveryMax) }
        var hrPushMin by rememberSaveable(profile.id) { mutableIntStateOf(profile.hrPushMin) }
        var warnSec by rememberSaveable(profile.id) { mutableIntStateOf(profile.warningThresholdSec) }
        var audio by rememberSaveable(profile.id) { mutableStateOf(profile.audioMode) }
        var vibration by rememberSaveable(profile.id) { mutableStateOf(profile.vibrationMode) }
        var vibrationIntensity by rememberSaveable(profile.id) { mutableFloatStateOf(profile.vibrationIntensity) }
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
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var darkExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = darkExpanded,
                    onExpandedChange = { darkExpanded = it },
                ) {
                    OutlinedTextField(
                        value = DARK_MODES.first { it.first == darkMode }.second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Dark mode") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = darkExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = darkExpanded,
                        onDismissRequest = { darkExpanded = false },
                    ) {
                        DARK_MODES.forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onDarkModeChange(mode)
                                    darkExpanded = false
                                },
                            )
                        }
                    }
                }
                Text(
                    "Applies to the whole app; System follows the device setting.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Workout length", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp)) {
                var lengthExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = lengthExpanded,
                    onExpandedChange = { lengthExpanded = it },
                ) {
                    OutlinedTextField(
                        value = LENGTH_MODES.first { it.first == lengthMode }.second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Length mode") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = lengthExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = lengthExpanded,
                        onDismissRequest = { lengthExpanded = false },
                    ) {
                        LENGTH_MODES.forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    lengthMode = mode
                                    lengthExpanded = false
                                },
                            )
                        }
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
        Text("Pace (steps/min)", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SliderRow("Recovery Max spm", "$paceCeil", paceCeil.toFloat(), 90f..140f, 10) { paceCeil = it.toInt() }
                SliderRow("Push Min spm", "$paceFloor", paceFloor.toFloat(), 80f..130f, 10) { paceFloor = it.toInt() }
                Text(
                    "Pedometer cadence from the paired Wear watch. Push cues \"Speed up\" while pace stays below Push Min; recovery cues \"Slow down\" while pace stays above Recovery Max.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Heart rate (bpm)", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SliderRow("Recovery Max bpm", "$hrRecoveryMax", hrRecoveryMax.toFloat(), 70f..190f, 24) { hrRecoveryMax = it.toInt() }
                SliderRow("Push Min bpm", "$hrPushMin", hrPushMin.toFloat(), 90f..200f, 22) { hrPushMin = it.toInt() }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Warning cues", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SliderRow("Repeat warning every", "${warnSec}s", warnSec.toFloat(), 1f..60f, 59) { warnSec = it.toInt() }
                Text(
                    "Push cues \"Speed up\" while pace is below Push Min spm or heart rate below Push Min bpm; recovery cues " +
                        "\"Slow down\" while pace is above Recovery Max spm or heart rate above Recovery Max bpm. A cue repeats at most once " +
                        "per this interval while the condition holds. A sensor reading 0 (no signal) never triggers a cue; phase-change cues take precedence over all other cues — warnings and workout-length cues wait until the following tick.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Audio cues", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var audioExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = audioExpanded,
                    onExpandedChange = { audioExpanded = it },
                ) {
                    OutlinedTextField(
                        value = AUDIO_MODES.first { it.first == audio }.second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Audio mode") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = audioExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = audioExpanded,
                        onDismissRequest = { audioExpanded = false },
                    ) {
                        AUDIO_MODES.forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    audio = mode
                                    audioExpanded = false
                                },
                            )
                        }
                    }
                }
                Text(
                    "Off: no spoken cues or transition beeps. On phase change: announce warm-up, push, recovery, cooldown and finish. " +
                        "All cues: also announce quarter, push-round and warning cues.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Vibration", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var vibrationExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = vibrationExpanded,
                    onExpandedChange = { vibrationExpanded = it },
                ) {
                    OutlinedTextField(
                        value = VIBRATION_MODES.first { it.first == vibration }.second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Vibration mode") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = vibrationExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = vibrationExpanded,
                        onDismissRequest = { vibrationExpanded = false },
                    ) {
                        VIBRATION_MODES.forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    vibration = mode
                                    vibrationExpanded = false
                                },
                            )
                        }
                    }
                }
                Text(
                    "Off: no haptics. On phase change: buzz at warm-up, push, recovery, cooldown and finish. " +
                        "All cues: also buzz on quarter, push-round and warning cues.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SliderRow(
                    "Intensity", "%.0f%%".format(vibrationIntensity * 100),
                    vibrationIntensity, 0f..1f, 9,
                ) { vibrationIntensity = it }
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
                    "Off uses real hardware: GPS speed, pedometer pace from the Wear watch, and a Bluetooth heart-rate strap. " +
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
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Health Connect HR (after workout)", style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = hcBackfillHr, onCheckedChange = onHcBackfillChange)
                    }
                    Text(
                        "When the Wear relay is off, average/min/max heart rate for a finished " +
                            "workout is backfilled from Health Connect (no live readings).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Grant sensor permissions")
                        }
                        OutlinedButton(
                            onClick = onHealthConnectPermission,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Grant Health Connect access")
                        }
                    }
                    Text(
                        "Location: ${if (locationGranted) "granted" else "not granted"} · " +
                            "Bluetooth: ${if (bluetoothGranted) "granted" else "not granted"} · " +
                            "Health Connect: ${if (hcGranted) "granted" else "not granted"}",
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

        Spacer(Modifier.height(16.dp))
        Text("Debug", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Debug tracing", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = debugLog, onCheckedChange = onDebugLogChange)
                }
                Text(
                    "When on, pace connection/step events are traced live on the workout screen and can be exported " +
                        "from there with the Export log button. Off by default; no trace data is collected while off.",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Display the captured log on the workout screen (own toggle;
                // capture/export are controlled by "Debug tracing" above).
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Show debug log on workout screen", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = showDebugLog, onCheckedChange = onShowDebugLogChange)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Force phone pedometer", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = forcePhonePace, onCheckedChange = onForcePhonePaceChange)
                }
                Text(
                    "Sets the watch-fallback window to 0s: the phone's own step sensor drives pace unconditionally " +
                        "and watch pace samples are ignored. Useful to isolate whether pace comes from the phone at all.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Unrestricted battery", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (batteryUnrestricted) "Allowed — sensors stay live" else "Optimized — sensors may be gated",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = onRequestBatteryUnrestricted) {
                        Text(if (batteryUnrestricted) "Granted" else "Allow")
                    }
                }
                Text(
                    "Battery optimization can suspend sensor delivery when the screen is off. Allowing unrestricted " +
                        "battery keeps the step sensors live during workouts.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Step sensor access", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (activityRecognitionGranted) "Granted — pace sensors work"
                            else "Not granted — step sensors blocked",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = onRequestActivityRecognition) {
                        Text(if (activityRecognitionGranted) "Granted" else "Allow")
                    }
                }
                Text(
                    "Android 10+ requires the activity-recognition permission for step sensors; without it, step " +
                        "pace stays empty no matter what.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Backup", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Export saves the current profiles or workout history to a file you pick; import restores it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onExportProfiles, modifier = Modifier.weight(1f)) {
                        Text("Export profiles")
                    }
                    OutlinedButton(onClick = onImportProfiles, modifier = Modifier.weight(1f)) {
                        Text("Import profiles")
                    }
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
                        pushSec = fastSec,
                        slowSec = slowSec,
                        warmupSec = warmupSec,
                        cooldownSec = cooldownSec,
                        recoveryPaceCapSpm = paceCeil,
                        pushPaceFloorSpm = paceFloor,
                        hrPushMin = hrPushMin,
                        hrRecoveryMax = hrRecoveryMax,
                        warningThresholdSec = warnSec,
                        audioMode = audio,
                        vibrationMode = vibration,
                        vibrationIntensity = vibrationIntensity,
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
