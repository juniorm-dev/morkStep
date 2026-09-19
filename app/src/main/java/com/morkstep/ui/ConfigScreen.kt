package com.morkstep.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.morkstep.Constants
import com.morkstep.data.AudioMode
import com.morkstep.data.DarkMode
import com.morkstep.data.VibrationMode
import com.morkstep.data.WorkoutLength
import com.morkstep.data.WorkoutProfile
import com.morkstep.sensing.BackgroundReadAccess

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

/**
 * The two Settings pages: [PROFILE] holds everything saved with a profile,
 * [GENERAL] holds the app-wide settings (no profile involved).
 */
private enum class SettingsPage(val label: String) {
    PROFILE("Profile"),
    GENERAL("General"),
}

/**
 * Runs [onPress] on any touch that lands on this element, without consuming it —
 * the child keeps its own click/drag behavior. Used to break the hidden-Debug
 * reveal gesture: the General-tab taps only count while nothing else is pressed.
 */
private fun Modifier.resetDebugRevealOnPress(onPress: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            onPress()
        }
    }

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

/** On/off row with a label and an explanatory line, used by the Sensors card. */
@Suppress("FunctionName")
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

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
    hcGranted: Boolean,
    hcBackgroundRead: BackgroundReadAccess,
    onHealthConnectPermission: () -> Unit,
    onDelete: (Long) -> Unit,
    onRequestPermissions: () -> Unit,
    locationGranted: Boolean,
    bluetoothGranted: Boolean,
    testAds: Boolean,
    onTestAdsChange: (Boolean) -> Unit,
    pinnedAds: Boolean,
    onPinnedAdsChange: (Boolean) -> Unit,
    smallHomeBanner: Boolean,
    onSmallHomeBannerChange: (Boolean) -> Unit,
    onExportProfiles: () -> Unit,
    onImportProfiles: () -> Unit,
    /** Hidden Debug card revealed by the General-tab gesture; owned by the view model. */
    debugUnlocked: Boolean,
    /** Reveal the hidden Debug card (called once the tap threshold is reached). */
    onDebugUnlock: () -> Unit,
    /** Newer build in the internal alpha folder, or null — see `UpdateCheck`. */
    updateAvailable: String? = null,
) {
    val profile = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    var page by rememberSaveable { mutableStateOf(SettingsPage.PROFILE) }
    // Hidden Debug card: revealed by [Constants.SETTINGS_DEBUG_UNLOCK_TAPS]
    // consecutive taps on the General tab with nothing else pressed in between —
    // any touch in the page area, or any other tab, resets the count. The reveal
    // itself lives in the view model ([debugUnlocked]) so it outlives Settings
    // and lasts until the app restarts; only the tap count is local to the visit.
    var generalTaps by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = page.ordinal) {
            SettingsPage.entries.forEach { entry ->
                Tab(
                    selected = page == entry,
                    onClick = {
                        if (entry == SettingsPage.GENERAL) {
                            generalTaps++
                            if (generalTaps >= Constants.SETTINGS_DEBUG_UNLOCK_TAPS) onDebugUnlock()
                        } else {
                            generalTaps = 0
                        }
                        page = entry
                    },
                    text = { Text(entry.label) },
                )
            }
        }
        // Everything below the tabs counts as "something else pressed": any touch
        // here breaks the consecutive General-tab reveal gesture.
        Column(
            Modifier
                .weight(1f)
                .resetDebugRevealOnPress { generalTaps = 0 }
        ) {
            // Keeps the hidden page's scroll position and in-progress edits alive
            // while the other one is shown.
            val pageState = rememberSaveableStateHolder()
            Box(Modifier.weight(1f)) {
                pageState.SaveableStateProvider(page.name) {
                    when (page) {
                        SettingsPage.PROFILE -> ProfileSettingsPage(
                            profile = profile,
                            profiles = profiles,
                            selectedId = selectedId,
                            onSelect = onSelect,
                            onSave = onSave,
                            onNewProfile = onNewProfile,
                            onCreateBaseline = onCreateBaseline,
                            onExportProfiles = onExportProfiles,
                            onImportProfiles = onImportProfiles,
                            onDelete = onDelete,
                        )
                        SettingsPage.GENERAL -> GeneralSettingsPage(
                            showDebug = debugUnlocked,
                            darkMode = darkMode,
                            onDarkModeChange = onDarkModeChange,
                            simulated = simulated,
                            sensorNote = sensorNote,
                            onSimulatedChange = onSimulatedChange,
                            wearHr = wearHr,
                            onWearHrChange = onWearHrChange,
                            wearVibrate = wearVibrate,
                            onWearVibrateChange = onWearVibrateChange,
                            hcBackfillHr = hcBackfillHr,
                            onHcBackfillChange = onHcBackfillChange,
                            debugLog = debugLog,
                            onDebugLogChange = onDebugLogChange,
                            showDebugLog = showDebugLog,
                            onShowDebugLogChange = onShowDebugLogChange,
                            forcePhonePace = forcePhonePace,
                            onForcePhonePaceChange = onForcePhonePaceChange,
                            testAds = testAds,
                            onTestAdsChange = onTestAdsChange,
                            pinnedAds = pinnedAds,
                            onPinnedAdsChange = onPinnedAdsChange,
                            smallHomeBanner = smallHomeBanner,
                            onSmallHomeBannerChange = onSmallHomeBannerChange,
                            batteryUnrestricted = batteryUnrestricted,
                            onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                            activityRecognitionGranted = activityRecognitionGranted,
                            hcGranted = hcGranted,
                            hcBackgroundRead = hcBackgroundRead,
                            onHealthConnectPermission = onHealthConnectPermission,
                            onRequestPermissions = onRequestPermissions,
                            locationGranted = locationGranted,
                            bluetoothGranted = bluetoothGranted,
                        )
                    }
                }
            }
            AppVersionFooter(updateAvailable = updateAvailable)
        }
    }
}

/**
 * App version, shown under both pages — it describes the app, not a profile. When the internal
 * alpha update check finds a newer published build ([updateAvailable]), a second line says so.
 */
@Suppress("FunctionName")
@Composable
private fun AppVersionFooter(updateAvailable: String? = null) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: info.longVersionCode.toString()
        }.getOrNull() ?: "?"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Text(
            "morkStep  v$version",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (updateAvailable != null) {
            Text(
                "Update available · v$updateAvailable",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Profile page: everything that is saved against (and restored with) a profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionName")
@Composable
private fun ProfileSettingsPage(
    profile: WorkoutProfile?,
    profiles: List<WorkoutProfile>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onSave: (WorkoutProfile) -> Unit,
    onNewProfile: () -> Unit,
    onCreateBaseline: () -> Unit,
    onExportProfiles: () -> Unit,
    onImportProfiles: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
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
            Text("No profiles yet — tap Clone to create one.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
        var lengthMode by rememberSaveable(profile.id) { mutableStateOf(profile.lengthMode) }
        var rounds by rememberSaveable(profile.id) { mutableIntStateOf(profile.rounds) }
        var distanceMiles by rememberSaveable(profile.id) { mutableFloatStateOf(profile.distanceMiles.toFloat()) }
        var timeMinutes by rememberSaveable(profile.id) { mutableIntStateOf(profile.timeMinutes) }
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
        var resetAverages by rememberSaveable(profile.id) { mutableStateOf(profile.resetPhaseAverages) }
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
                        "Duration", "$timeMinutes min",
                        timeMinutes.toFloat(), 5f..120f, 46,
                    ) { timeMinutes = it.toInt() }
                    // ADHOC runs until the session is ended by hand, so it has no
                    // length dimension of its own to set here.
                    WorkoutLength.ADHOC -> Unit
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
                        "All cues: also announce quarter and warning cues.",
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
                        "All cues: also buzz on quarter and warning cues.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SliderRow(
                    "Intensity", "%.0f%%".format(vibrationIntensity * 100),
                    vibrationIntensity, 0f..1f, 9,
                ) { vibrationIntensity = it }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Phase averages", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Level out phase transitions",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = resetAverages, onCheckedChange = { resetAverages = it })
                }
                Text(
                    "Experimental: each phase's average starts just inside its target band (push min + 1 on push, " +
                        "recovery max - 1 on recovery) instead of carrying the previous phase's levels into the new " +
                        "phase's average, so the push/recovery transition is less polluted by the phase before it. " +
                        "It also holds warning cues back for a few seconds after a phase change, so the new phase " +
                        "does not warn on the previous phase's readings. " +
                        "The overall average is unaffected.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Backup", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Export saves every profile to a JSON file you pick; import replaces your profiles with that " +
                        "file's list and activates its first profile. Workout history has its own Export/Import on " +
                        "the History screen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExportProfiles, modifier = Modifier.fillMaxWidth()) {
                        Text("Export profiles")
                    }
                    OutlinedButton(onClick = onImportProfiles, modifier = Modifier.fillMaxWidth()) {
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
                        resetPhaseAverages = resetAverages,
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
    }
}

/** General page: app-wide settings — nothing here is saved with a profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionName")
@Composable
private fun GeneralSettingsPage(
    showDebug: Boolean,
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
    hcGranted: Boolean,
    hcBackgroundRead: BackgroundReadAccess,
    onHealthConnectPermission: () -> Unit,
    onRequestPermissions: () -> Unit,
    locationGranted: Boolean,
    bluetoothGranted: Boolean,
    testAds: Boolean,
    onTestAdsChange: (Boolean) -> Unit,
    pinnedAds: Boolean,
    onPinnedAdsChange: (Boolean) -> Unit,
    smallHomeBanner: Boolean,
    onSmallHomeBannerChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
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
        Text("Sensors", style = MaterialTheme.typography.titleMedium)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Debug-only control: it belongs with the hidden Debug card, so
                // the switch (and the description of what "off" means) appears
                // only once that card is revealed. Leaving the mode on is still
                // visible below — the card shows the simulated-sensor note
                // instead of the hardware controls while it is on.
                if (showDebug) {
                    SwitchRow(
                        label = "Simulated sensors (debug)",
                        checked = simulated,
                        onCheckedChange = onSimulatedChange,
                    )
                    Text(
                        "Off uses real hardware: GPS speed, pedometer pace from the Wear watch, and a Bluetooth heart-rate strap. " +
                            "No automatic fallback — if off and a signal is missing, readings stay blank.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!simulated) {
                    SwitchRow(
                        label = "Heart rate from Wear companion",
                        checked = wearHr,
                        onCheckedChange = onWearHrChange,
                    )
                    SwitchRow(
                        label = "Vibrate watch",
                        checked = wearVibrate,
                        onCheckedChange = onWearVibrateChange,
                    )
                    SwitchRow(
                        label = "Health Connect HR (after workout)",
                        checked = hcBackfillHr,
                        onCheckedChange = onHcBackfillChange,
                    )
                    Text(
                        "When the Wear relay is off, average/min/max heart rate for a finished " +
                            "workout is backfilled from Health Connect (no live readings).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Grant sensor permissions")
                        }
                        OutlinedButton(
                            onClick = onHealthConnectPermission,
                            modifier = Modifier.fillMaxWidth(),
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
                    if (hcBackgroundRead != BackgroundReadAccess.UNSUPPORTED) {
                        Text(
                            "Health Connect background reads: ${hcBackgroundRead.note} — " +
                                "required for the post-workout rereads (1/6/21/66 min) " +
                                "and every History re-read.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(
                        sensorNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
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
                Column {
                    Text("Step sensor access", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (activityRecognitionGranted) "Granted — pace sensors work"
                        else "Not granted — step sensors blocked; allow it via \"Grant sensor permissions\"",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Android 10+ requires the activity-recognition permission for step sensors; without it, step " +
                        "pace stays empty no matter what.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Hidden until the General tab is tapped [Constants.SETTINGS_DEBUG_UNLOCK_TAPS]
        // times in a row — the tab counter lives in ConfigScreen.
        if (showDebug) {
            Spacer(Modifier.height(16.dp))
            Text("Debug", style = MaterialTheme.typography.titleMedium)
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchRow(
                        label = "Debug tracing",
                        checked = debugLog,
                        onCheckedChange = onDebugLogChange,
                    )
                    Text(
                        "When on, pace connection/step events are traced live on the workout screen and can be exported " +
                            "from there with the Export log button. Off by default; no trace data is collected while off.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Display the captured log on the workout screen (own toggle;
                    // capture/export are controlled by "Debug tracing" above).
                    SwitchRow(
                        label = "Show debug log on workout screen",
                        checked = showDebugLog,
                        onCheckedChange = onShowDebugLogChange,
                    )
                    SwitchRow(
                        label = "Force phone pedometer",
                        checked = forcePhonePace,
                        onCheckedChange = onForcePhonePaceChange,
                    )
                    Text(
                        "Sets the watch-fallback window to 0s: the phone's own step sensor drives pace unconditionally " +
                            "and watch pace samples are ignored. Useful to isolate whether pace comes from the phone at all.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SwitchRow(
                        label = "Test ads (debug)",
                        checked = testAds,
                        onCheckedChange = onTestAdsChange,
                    )
                    Text(
                        "Off by default, and off means no ad is requested, loaded or shown anywhere in the app. On " +
                            "serves Google's test inventory: an anchored banner under Home and a native ad card above " +
                            "the History list, both labelled as ads. No full-screen ad opens during a workout.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SwitchRow(
                        label = "Pinned ads (debug)",
                        checked = pinnedAds,
                        onCheckedChange = onPinnedAdsChange,
                    )
                    Text(
                        "Moves the banner into the bottom bar, above the tabs, where no screen's scrolling can " +
                            "carry it away — every screen shows the same pinned banner. On the History screen it " +
                            "replaces the ad card with a full-page native ad that opens on every third History " +
                            "access and stays until it is closed. Inert while Test ads is off; the full-page ad " +
                            "never opens during a workout.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SwitchRow(
                        label = "Small home banner (debug)",
                        checked = smallHomeBanner,
                        onCheckedChange = onSmallHomeBannerChange,
                    )
                    Text(
                        "Draws the Home screen's banner at the small fixed 320×50 size the Workout route uses " +
                            "instead of the large anchored adaptive default, so the two can be compared. " +
                            "Inert while Test ads is off.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
