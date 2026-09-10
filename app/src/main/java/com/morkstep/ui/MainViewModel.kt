package com.morkstep.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.morkstep.AppContainer
import com.morkstep.Constants
import com.morkstep.DebugLog
import com.morkstep.MorkApplication
import com.morkstep.WorkoutService
import com.morkstep.audio.CueSpeaker
import com.morkstep.data.DarkMode
import com.morkstep.data.PhaseType
import com.morkstep.data.TransferIO
import com.morkstep.data.VibrationMode
import com.morkstep.data.WorkoutEntity
import com.morkstep.data.WorkoutProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.morkstep.data.baselineCalibrationProfile
import com.morkstep.data.defaultProfile
import com.morkstep.data.isBaselineProfile
import com.morkstep.data.updatedBaselineProfile
import com.morkstep.engine.CueSink
import com.morkstep.engine.CueVibration
import com.morkstep.engine.LiveState
import com.morkstep.engine.SessionEngine
import com.morkstep.sensing.GpsSpeedSource
import com.morkstep.sensing.BleHeartRateSource
import com.morkstep.sensing.HeartRateSource
import com.morkstep.sensing.PaceSource
import com.morkstep.sensing.WearPaceSource
import com.morkstep.sensing.healthConnectHrForWorkout
import com.morkstep.sensing.SpeedSource
import com.morkstep.sensing.SimulatedSensors
import com.morkstep.sensing.FallbackPaceSource
import com.morkstep.sensing.PhonePaceSource
import com.morkstep.sensing.WearHeartRateSource
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private class SpeakerSink(
    private val speaker: CueSpeaker,
    private val app: Application,
    private val scope: CoroutineScope,
    /** Live vibration mode from the active profile (gates phone + watch haptics). */
    private val vibrationMode: StateFlow<VibrationMode>,
    /** Live toggle: also relay cues to the paired Wear companion for watch haptics. */
    private val wearVibrate: StateFlow<Boolean>,
    /** Live cue-vibration strength 0..1 from the active profile. */
    private val vibrationIntensity: StateFlow<Float>,
) : CueSink {
    override fun beep() = speaker.beep()
    override fun speak(text: String) = speaker.speak(text)

    /** Phone vibrates when the active profile's mode permits; watch follows if enabled. */
    override fun vibrate(kind: CueVibration) {
        val mode = vibrationMode.value
        val allowed = mode == VibrationMode.ALL ||
            (mode == VibrationMode.PHASE_CHANGE && kind == CueVibration.TRANSITION)
        if (!allowed) return
        val intensity = vibrationIntensity.value.coerceIn(0f, 1f)
        vibratePhone(intensity)
        if (wearVibrate.value) scope.launch { sendWatchVibrate(kind, intensity) }
    }

    private fun vibratePhone(intensity: Float) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        // A long, distinct cue buzz; amplitude scales with the profile's
        // intensity slider. 0 intensity still emits the effect — gating happens
        // in vibrate() via the mode.
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                Constants.PHONE_VIBRATE_MS,
                (intensity * Constants.HAPTIC_AMPLITUDE_MAX).roundToInt()
                    .coerceIn(Constants.PHONE_AMPLITUDE_MIN, Constants.HAPTIC_AMPLITUDE_MAX),
            )
        )
    }

    /** Tell the paired watch to buzz; payload distinguishes transition (1) from guidance (2)
     *  and carries the intensity 0..255 (0 = watch default strength). */
    private fun sendWatchVibrate(kind: CueVibration, intensity: Float) {
        val nodes: List<Node> = Wearable.getNodeClient(app).connectedNodes.await()
        val messageClient: MessageClient = Wearable.getMessageClient(app)
        val payload = byteArrayOf(
            (if (kind == CueVibration.TRANSITION) Constants.WATCH_VIBRATE_TRANSITION else Constants.WATCH_VIBRATE_GUIDANCE).toByte(),
            (intensity * Constants.HAPTIC_AMPLITUDE_MAX).roundToInt()
                .coerceIn(Constants.WATCH_AMPLITUDE_MIN, Constants.HAPTIC_AMPLITUDE_MAX).toByte(),
        )
        nodes.forEach { node ->
            runCatching { messageClient.sendMessage(node.id, Constants.VIBRATE_PATH, payload).await() }
        }
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container: AppContainer = (app as MorkApplication).container
    private val speaker = CueSpeaker(app)

    /** Global dark-mode preference (system / dark / light), shared by the whole app. */
    private val _darkMode = MutableStateFlow(DarkMode.SYSTEM)
    val darkMode: StateFlow<DarkMode> = _darkMode.asStateFlow()

    /** Vibration mode from the active profile's settings; gates phone + watch haptics. */
    private val _vibrationMode = MutableStateFlow(VibrationMode.OFF)
    val vibrationMode: StateFlow<VibrationMode> = _vibrationMode.asStateFlow()

    /** Relay gated cue vibrations to the paired Wear companion for watch haptics. */
    private val _wearVibrate = MutableStateFlow(false)
    val wearVibrate: StateFlow<Boolean> = _wearVibrate.asStateFlow()

    /** Post-workout Health Connect HR backfill (applies when the Wear relay is off). */
    private val _hcBackfillHr = MutableStateFlow(true)
    val hcBackfillHr: StateFlow<Boolean> = _hcBackfillHr.asStateFlow()

    /** Health Connect READ_HEART_RATE granted state; refreshed on launch and after the permission screen. */
    private val _hcGranted = MutableStateFlow(false)
    val hcGranted: StateFlow<Boolean> = _hcGranted.asStateFlow()

    /** Debug tracing toggle: gates the app debug log, the on-screen debug text and log export. */
    private val _debugEnabled = MutableStateFlow(false)
    val debugEnabled: StateFlow<Boolean> = _debugEnabled.asStateFlow()

    /** Whether the captured debug log is rendered on the workout screen. */
    private val _showDebugLog = MutableStateFlow(false)
    val showDebugLog: StateFlow<Boolean> = _showDebugLog.asStateFlow()

    /** Debug: force the phone pedometer to drive pace instead of the watch-relay fallback. */
    private val _forcePhonePace = MutableStateFlow(false)
    val forcePhonePace: StateFlow<Boolean> = _forcePhonePace.asStateFlow()

    /** Whether the app is exempt from battery optimization (sensors stay live in background). */
    private val _batteryUnrestricted = MutableStateFlow(false)
    val batteryUnrestricted: StateFlow<Boolean> = _batteryUnrestricted.asStateFlow()

    /** Android 10+ runtime gate for step sensors; without it registerListener returns false. */
    private val _activityRecognitionGranted = MutableStateFlow(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
    val activityRecognitionGranted: StateFlow<Boolean> = _activityRecognitionGranted.asStateFlow()

    /** Re-check the battery-optimization exemption state (call after the system settings screen). */
    fun refreshBatteryOptimizationState() {
        val app = getApplication<Application>()
        _batteryUnrestricted.value = runCatching {
            (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .isIgnoringBatteryOptimizations(app.packageName)
        }.getOrDefault(false)
    }

    /** Package name, for the battery-optimization exemption request. */
    fun packageName(): String = getApplication<Application>().packageName

    /** Re-check the ACTIVITY_RECOGNITION grant; call after the permission screen. */
    fun refreshActivityRecognitionState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        _activityRecognitionGranted.value =
            androidx.core.content.ContextCompat.checkSelfPermission(
                getApplication(), android.Manifest.permission.ACTIVITY_RECOGNITION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Cue-vibration strength 0..1 from the active profile. */
    private val _vibrationIntensity = MutableStateFlow(0.5f)
    val vibrationIntensity: StateFlow<Float> = _vibrationIntensity.asStateFlow()

    private val sink = SpeakerSink(speaker, app, viewModelScope, _vibrationMode, _wearVibrate, _vibrationIntensity)

    private var engine: SessionEngine? = null
    private var tickerJob: Job? = null
    /** Collects sensor + live-state for the current engine; cancelled when it is replaced. */
    private var engineJob: Job? = null
    /** Accepts pause/resume commands sent by the paired Wear companion. */
    private val wearPauseListener = object : com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener {
        override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
            if (event.path != WEAR_PAUSE_PATH) return
            val pause = event.data.firstOrNull()?.toInt() == 1
            if (pause) engine?.pause() else engine?.resume()
        }
    }
    /** Traces watch->phone message reception (state relays arrive ~1 Hz during a workout). */
    private val watchMessageListener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path == WEAR_STATE_PATH) {
            debugLog.log("[wear] watch ack ${event.sourceNodeId.takeLast(6)}")
        }
    }
    /** Last state payload sent to the watch; identical snapshots are not re-sent. */
    private var lastWatchState = byteArrayOf()

    /** Trace snapshot from the moment the last workout started (for post-workout exports). */
    private var lastWorkoutLogDump = ""

    /** App-wide debug log (sensor/wearable diagnostics): on-screen + export. */
    private val debugLog = DebugLog()

    // Real sources (only live while simulated mode is OFF).
    private var gps: GpsSpeedSource? = null
    private var ble: BleHeartRateSource? = null
    private var wear: WearHeartRateSource? = null
    private var wearPace: WearPaceSource? = null
    private var phonePace: PhonePaceSource? = null
    /** Merges watch pace (preferred) with the phone pedometer fallback. */
    private var paceMerge: FallbackPaceSource? = null
    private var sim: SimulatedSensors? = null

    private val _profiles = MutableStateFlow(emptyList<WorkoutProfile>())
    val profiles: StateFlow<List<WorkoutProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(0L)
    val activeId: StateFlow<Long> = _activeId.asStateFlow()

    private val _activeProfile = MutableStateFlow<WorkoutProfile?>(null)
    val activeProfile: StateFlow<WorkoutProfile?> = _activeProfile.asStateFlow()

    private val _simulated = MutableStateFlow(false)
    val simulated: StateFlow<Boolean> = _simulated.asStateFlow()
    /** When true, heart rate comes from the paired Wear companion (message relay) instead of BLE. */
    private val _useWearHr = MutableStateFlow(false)
    val useWearHr: StateFlow<Boolean> = _useWearHr.asStateFlow()

    /** Human-readable note about which sensor sources are in use. */
    private val _sensorNote = MutableStateFlow("")
    val sensorNote: StateFlow<String> = _sensorNote.asStateFlow()

    private val _locationGranted = MutableStateFlow(false)
    val locationGranted: StateFlow<Boolean> = _locationGranted.asStateFlow()

    private val _bluetoothGranted = MutableStateFlow(false)
    val bluetoothGranted: StateFlow<Boolean> = _bluetoothGranted.asStateFlow()

    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()

    /** One-shot: name of the just-saved profile; null when no save happened. Consumed back by the UI. */
    private val _savedProfileName = MutableStateFlow<String?>(null)
    val savedProfileName: StateFlow<String?> = _savedProfileName.asStateFlow()

    /** One-shot: set when a baseline workout finishes; the UI returns home and confirms. */
    private val _baselineCreatedMessage = MutableStateFlow<String?>(null)
    val baselineCreatedMessage: StateFlow<String?> = _baselineCreatedMessage.asStateFlow()

    /** One-shot: result of the last profile/history export or import. Consumed back by the UI. */
    private val _transferMessage = MutableStateFlow<String?>(null)
    val transferMessage: StateFlow<String?> = _transferMessage.asStateFlow()

    /** Backup/restore of profiles and workout history through the Storage Access Framework. */
    private val transfer by lazy {
        TransferIO(
            getApplication<Application>().contentResolver,
            container.configStore,
            container.workoutDao,
        )
    }

    init {
        runCatching {
            com.google.android.gms.wearable.Wearable.getMessageClient(getApplication())
                .addListener(wearPauseListener)
        }
        runCatching {
            com.google.android.gms.wearable.Wearable.getMessageClient(getApplication())
                .addListener(watchMessageListener)
        }
        viewModelScope.launch {
            val nodes = runCatching {
                com.google.android.gms.wearable.Wearable.getNodeClient(getApplication())
                    .connectedNodes.await()
            }.getOrNull()
            debugLog.log(
                if (nodes.isNullOrEmpty()) "[wear] no wearables connected"
                else "[wear] wearables connected: ${nodes.joinToString { it.displayName }}"
            )
        }
        viewModelScope.launch {
            container.configStore.simulatedSensors.collect { simOn ->
                _simulated.value = simOn
                rebuildSources()
            }
        }
        viewModelScope.launch {
            container.configStore.wearHr.collect { on ->
                _useWearHr.value = on
                rebuildSources()
            }
        }
        viewModelScope.launch {
            container.configStore.wearVibrate.collect { on ->
                _wearVibrate.value = on
            }
        }
        viewModelScope.launch {
            container.configStore.hcBackfillHr.collect { on ->
                _hcBackfillHr.value = on
            }
        }
        viewModelScope.launch {
            container.configStore.debugLog.collect { on ->
                _debugEnabled.value = on
                debugLog.enabled = on
                if (on) {
                    debugLog.log("[sys] debug tracing enabled · ${_sensorNote.value}")
                    // Re-survey wearables so the trace reflects the current link.
                    val nodes = runCatching {
                        com.google.android.gms.wearable.Wearable.getNodeClient(getApplication())
                            .connectedNodes.await()
                    }.getOrNull()
                    debugLog.log(
                        if (nodes.isNullOrEmpty()) "[wear] no wearables connected"
                        else "[wear] wearables connected: ${nodes.joinToString { it.displayName }}"
                    )
                } else {
                    debugLog.clear()
                }
            }
        }
        viewModelScope.launch {
            container.configStore.showDebugLog.collect { on ->
                _showDebugLog.value = on
            }
        }
        viewModelScope.launch {
            container.configStore.forcePhonePace.collect { on ->
                _forcePhonePace.value = on
                // The merge uses the flag at construction time; rebuild it so the
                // forced/staleness mode applies to the live pipeline immediately.
                setupEngine()
                if (on) {
                    debugLog.log("[sys] phone pedometer forced (0s watch fallback)")
                } else {
                    debugLog.log("[sys] watch fallback restored (15s)")
                }
            }
        }
        refreshHealthConnectState()
        refreshBatteryOptimizationState()
        refreshActivityRecognitionState()
        if (_activityRecognitionGranted.value) {
            debugLog.log("[sys] activity recognition: granted")
        } else {
            debugLog.log("[sys] activity recognition: NOT GRANTED — step sensors blocked")
        }
        if (_batteryUnrestricted.value) {
            debugLog.log("[sys] battery: unrestricted")
        } else {
            debugLog.log("[sys] battery: OPTIMIZED — sensors may be gated")
        }
        viewModelScope.launch {
            container.configStore.darkMode.collect { _darkMode.value = it }
        }
        viewModelScope.launch {
            container.configStore.profiles.collect { list ->
                _profiles.value = list
                refreshActive()
            }
        }
        viewModelScope.launch {
            container.configStore.activeId.collect { id ->
                _activeId.value = id
                refreshActive()
            }
        }
    }

    /** (Re)create speed/HR sources per the simulated toggle. Real sources do NOT fall back. */
    private fun rebuildSources() {
        stopSources()
        refreshPermissions()
        if (_simulated.value) {
            sim = SimulatedSensors()
            _sensorNote.value = "Simulated sensors (debug)"
            debugLog.log("[sys] simulated sources active")
        } else {
            gps = GpsSpeedSource(getApplication(), debugLog)
            ble = BleHeartRateSource(getApplication(), debugLog)
            wearPace = WearPaceSource(getApplication(), debugLog).also { it.start() }
            phonePace = PhonePaceSource(getApplication(), debugLog).also { it.start() }
            if (_useWearHr.value) {
                wear = WearHeartRateSource(getApplication(), debugLog).also { it.start() }
            }
            debugLog.log("[sys] sources: GPS + pace (watch/phone) + ${if (_useWearHr.value) "Wear HR" else "BLE HR"}")
            _sensorNote.value =
                if (_useWearHr.value) "GPS speed · pace (watch or phone) · Wear heart rate"
                else "GPS speed · pace (watch or phone) · BLE heart rate"
        }
        setupEngine()
    }

    fun refreshPermissions() {
        val app = getApplication<Application>()
        _locationGranted.value =
            androidx.core.content.ContextCompat.checkSelfPermission(
                app, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        _bluetoothGranted.value =
            androidx.core.content.ContextCompat.checkSelfPermission(
                app, android.Manifest.permission.BLUETOOTH_SCAN
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                app, android.Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun stopSources() {
        gps?.stop()
        ble?.stop()
        wear?.stop()
        wearPace?.stop()
        phonePace?.stop()
        gps = null
        ble = null
        wear = null
        wearPace = null
        phonePace = null
        sim = null
    }

    private fun refreshActive() {
        val id = _activeId.value
        _activeProfile.value = _profiles.value.firstOrNull { it.id == id }
            ?: _profiles.value.firstOrNull()
            ?: defaultProfile()
        _vibrationMode.value = _activeProfile.value?.vibrationMode ?: VibrationMode.OFF
        _vibrationIntensity.value = _activeProfile.value?.vibrationIntensity ?: 0.5f
        setupEngine()
    }

    private fun setupEngine() {
        val p = _activeProfile.value ?: return
        val speedSrc: SpeedSource = sim ?: gps ?: return
        val hrSrc: HeartRateSource =
            if (_simulated.value) sim!!
            else if (_useWearHr.value) wear ?: ble ?: return
            else ble ?: return
        // Pace: the Wear relay when the watch is streaming, else the phone's
        // own step sensor (FallbackPaceSource switches after 15 s of watch
        // silence, so a missing watch never leaves pace blank). The debug
        // "force phone pedometer" flag sets the staleness to 0, which makes
        // the phone drive pace unconditionally (watch pace ignored).
        val paceSrc: PaceSource = if (_simulated.value) sim!!
        else FallbackPaceSource(
            wearPace!!, phonePace!!,
            staleAfterMs = if (_forcePhonePace.value) 0L else 15_000L,
            log = debugLog,
        ).also { paceMerge = it }
        engineJob?.cancel()
        val e = SessionEngine(p, speedSrc, hrSrc, paceSrc, sink, log = debugLog)
        engine = e
        // The old merge (if any) was cancelled with engineJob; start the new one
        // with the fresh engine's scope.
        val merge = paceMerge
        paceMerge = null
        engineJob = viewModelScope.launch {
            // Sensor observation and live-state fan-out share one job so a
            // replaced/discarded engine is fully torn down (no stale updates).
            merge?.start(this)
            e.start(this)
            e.state.collect { _live.value = it }
        }
    }

    /** Set the global dark-mode preference. */
    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch { container.configStore.setDarkMode(mode) }
    }

    fun setSimulatedSensors(on: Boolean) {
        viewModelScope.launch { container.configStore.setSimulatedSensors(on) }
    }

    /** Choose heart rate source: paired Wear companion (relay) instead of BLE. */
    fun setWearHr(on: Boolean) {
        viewModelScope.launch { container.configStore.setWearHr(on) }
    }

    /** Whether to backfill HR from Health Connect after workouts without the watch. */
    fun setHcBackfillHr(on: Boolean) {
        viewModelScope.launch { container.configStore.setHcBackfillHr(on) }
    }

    /** Toggle the debug tracing feature set (on-screen pace trace + log export). */
    fun setDebugLog(on: Boolean) {
        viewModelScope.launch { container.configStore.setDebugLog(on) }
    }

    /** Toggle the on-screen debug log display on the workout screen. */
    fun setShowDebugLog(on: Boolean) {
        viewModelScope.launch { container.configStore.setShowDebugLog(on) }
    }

    /** Debug: force the phone pedometer to drive pace; the watch relay is ignored. */
    fun setForcePhonePace(on: Boolean) {
        viewModelScope.launch { container.configStore.setForcePhonePace(on) }
    }

    /** Export the captured debug log to the SAF document at [uri]; result shows in a snackbar. */
    fun exportDebugLog(uri: Uri) {
        val ls = engine?.snapshot
        val body = buildString {
            appendLine("morkStep debug log — " +
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            appendLine(_sensorNote.value)
            appendLine(
                "workout: ${if (ls == null) "no engine" else if (ls.running) "running sec=${ls.totalSeconds}" else "not running"}" +
                    " · pace=${ls?.pace?.toString() ?: "null"}"
            )
            appendLine(
                debugLog.text.value
                    .ifEmpty { lastWorkoutLogDump.ifEmpty { "(no trace captured — enable debug tracing in Settings)" } }
            )
        }
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(body.toByteArray(Charsets.UTF_8))
                } ?: error("Could not open export file")
            }
                .onSuccess { _transferMessage.value = "Debug log exported" }
                .onFailure { _transferMessage.value = it.message ?: "Export failed" }
        }
    }

    /** Re-check Health Connect availability and read permission (call after the permission screen). */
    fun refreshHealthConnectState() {
        val context = getApplication<Application>()
        _hcGranted.value =
            androidx.health.connect.client.HealthConnectClient.getSdkStatus(context) ==
                androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, "android.permission.health.READ_HEART_RATE"
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    /** Relay gated cue vibrations to the paired Wear companion for watch haptics. */
    fun setWearVibrate(on: Boolean) {
        viewModelScope.launch { container.configStore.setWearVibrate(on) }
    }
    /** Select which profile is shown on the home screen and used for the next workout. */
    fun selectProfile(id: Long) {
        if (id == _activeId.value) return
        val name = _profiles.value.firstOrNull { it.id == id }?.name
        if (name != null) debugLog.log("[profile] selected: $name")
        viewModelScope.launch { container.configStore.setActive(id) }
    }

    /** Save edits to any existing profile (by id). */
    fun updateProfile(updated: WorkoutProfile) {
        debugLog.log("[profile] updated: ${updated.name}")
        viewModelScope.launch {
            val list = _profiles.value.map { if (it.id == updated.id) updated else it }
            container.configStore.saveProfiles(list)
            _savedProfileName.value = updated.name
        }
    }

    /** Clear the save-confirmation event after the UI has acted on it. */
    fun consumeSavedProfile() {
        _savedProfileName.value = null
    }

    /** Clear the baseline-created event after the UI has shown it. */
    fun consumeBaselineCreatedMessage() {
        _baselineCreatedMessage.value = null
    }
    /** First free "Profile N" name (skips names already in use after deletions). */
    private fun nextFreeProfileName(): String {
        val used = _profiles.value.map { it.name }.toSet()
        var n = 1
        while ("Profile $n" in used) n++
        return "Profile $n"
    }

    /** Clone the active profile under a fresh id and make it active. */
    fun newProfileFromActive() {
        val src = _activeProfile.value ?: return
        val fresh = src.copy(
            id = System.currentTimeMillis(),
            name = nextFreeProfileName(),
        )
        debugLog.log("[profile] created: ${fresh.name}")
        viewModelScope.launch {
            val list = _profiles.value + fresh
            container.configStore.saveProfiles(list)
            container.configStore.setActive(fresh.id)
        }
    }

    /** Create (or reset) the Baseline calibration profile and make it active. */
    fun createBaselineProfile() {
        val existing = _profiles.value.firstOrNull { isBaselineProfile(it) }
        viewModelScope.launch {
            val fresh = baselineCalibrationProfile(id = existing?.id ?: System.currentTimeMillis())
                // Keep the current haptics so the calibration workout (and the
                // derived baseline, which copies all fields) is not silent.
                .copy(
                    vibrationMode = _activeProfile.value?.vibrationMode ?: VibrationMode.OFF,
                    vibrationIntensity = _activeProfile.value?.vibrationIntensity ?: 0.5f,
                )
            debugLog.log("[profile] baseline created: ${fresh.name}")
            val list = if (existing != null) {
                _profiles.value.map { if (it.id == existing.id) fresh else it }
            } else {
                _profiles.value + fresh
            }
            container.configStore.saveProfiles(list)
            container.configStore.setActive(fresh.id)
            _savedProfileName.value = Constants.BASELINE_PROFILE_NAME
        }
    }

    /** Delete a profile; if it was active, activate another (or the default). */
    fun deleteProfile(id: Long) {
        val name = _profiles.value.firstOrNull { it.id == id }?.name
        if (name != null) debugLog.log("[profile] deleted: $name")
        val remaining = _profiles.value.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            viewModelScope.launch {
                container.configStore.saveProfiles(listOf(defaultProfile()))
                container.configStore.setActive(defaultProfile().id)
            }
            return
        }
        val newActive = if (_activeId.value == id) {
            remaining.first().id
        } else _activeId.value
        viewModelScope.launch {
            container.configStore.saveProfiles(remaining)
            container.configStore.setActive(newActive)
        }
    }

    // ---- Profile / history backup (SAF) ----

    fun exportProfiles(uri: Uri) {
        viewModelScope.launch {
            runCatching { transfer.writeProfiles(uri) }
                .onSuccess { _transferMessage.value = "Profiles exported (${_profiles.value.size})" }
                .onFailure { _transferMessage.value = it.message ?: "Export failed" }
        }
    }

    /** Import a profile backup; the profile list is replaced (restore semantics). */
    fun importProfiles(uri: Uri) {
        viewModelScope.launch {
            runCatching { transfer.readProfiles(uri) }
                .onSuccess { _transferMessage.value = "$it profiles imported" }
                .onFailure { _transferMessage.value = it.message ?: "Import failed" }
        }
    }

    fun exportWorkouts(uri: Uri) {
        viewModelScope.launch {
            runCatching { transfer.writeWorkouts(uri) }
                .onSuccess { _transferMessage.value = "History exported" }
                .onFailure { _transferMessage.value = it.message ?: "Export failed" }
        }
    }

    /** Import workout history; rows are merged, imported ids win. */
    fun importWorkouts(uri: Uri) {
        viewModelScope.launch {
            runCatching { transfer.readWorkouts(uri) }
                .onSuccess { _transferMessage.value = "$it workouts imported" }
                .onFailure { _transferMessage.value = it.message ?: "Import failed" }
        }
    }

    /** Clear the transfer-result event after the UI has acted on it. */
    fun consumeTransferMessage() {
        _transferMessage.value = null
    }

    fun startWorkout() {
        val p = _activeProfile.value ?: return
        if (p.pushSec <= 0 || p.slowSec <= 0) return
        // Snapshot the trace at the moment the workout starts, so an export
        // after the workout shows exactly what happened during it (the trace
        // would otherwise be rolled over by post-workout engine rebuilds).
        debugLog.log("[sys] == workout start ==")
        lastWorkoutLogDump = debugLog.text.value
        // A discarded engine is gone and a finished engine refuses to re-run;
        // every workout must start from a fresh one.
        if (engine == null || engine?.snapshot?.finished == true) setupEngine()
        engine?.run()
        // Foreground keep-alive: wake lock + notification while the screen is
        // locked or the app is backgrounded, so ticks and cues stay on time.
        WorkoutService.start(getApplication(), _simulated.value)
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (engine?.snapshot?.finished != true) {
                @Suppress("ConvertLongToDuration")
                delay(1000)
                engine?.tick()
                val ls = engine?.state?.value ?: break
                WorkoutService.update(getApplication(), ls)
                sendWatchState(ls)
                // Drive simulated values only when simulated mode is on.
                if (_simulated.value) sim?.setPhase(ls.phase)
                if (ls.finished) onFinished()
            }
        }
    }
    /** Mirror the live session snapshot to the paired watch (phase, paused, running),
     *  seconds-in-phase, speed & pace, push progress and the profile's phase lengths and
     *  speed/pace targets — everything the watch graphics (Bars/Band/Gauge) render. */
    private fun sendWatchState(ls: LiveState) {
        val p = _activeProfile.value ?: return
        val phaseOrd = when (ls.phase) {
            PhaseType.WARMUP -> 1
            PhaseType.FAST -> 2
            PhaseType.SLOW -> 3
            PhaseType.COOLDOWN -> 4
        }
        val payload = ByteBuffer.allocate(WATCH_STATE_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(phaseOrd.toByte())
            .put((if (ls.paused) 1 else 0).toByte())
            .put((if (ls.running) 1 else 0).toByte())
            .putInt(ls.secondsInPhase)
            .putFloat(ls.speed ?: Float.NaN)
            .putInt(ls.pace ?: -1)
            .putInt(ls.pushSegmentsDone)
            .putInt(ls.pushRoundsTotal ?: -1)
            .putInt(p.pushSec)
            .putInt(p.slowSec)
            .putFloat(p.pushSpeedFloorMph.toFloat())
            .putFloat(p.recoverySpeedCapMph.toFloat())
            .putInt(p.pushPaceFloorSpm)
            .putInt(p.recoveryPaceCapSpm)
            .array()
        if (payload.contentEquals(lastWatchState)) return
        lastWatchState = payload
        val nodes = runCatching {
            com.google.android.gms.wearable.Wearable.getNodeClient(getApplication()).connectedNodes.await()
        }.getOrNull() ?: return
        val client = com.google.android.gms.wearable.Wearable.getMessageClient(getApplication())
        nodes.forEach { node ->
            runCatching { client.sendMessage(node.id, WEAR_STATE_PATH, payload).await() }
        }
    }

    private fun onFinished() {
        val ls = engine?.state?.value ?: return
        WorkoutService.stop(getApplication())
        val ended = System.currentTimeMillis()
        val started = ended - ls.totalSeconds * 1000L
        val activeProfileAtFinish = _activeProfile.value
        viewModelScope.launch {
            val entity = WorkoutEntity(
                startTime = started,
                endTime = ended,
                durationSec = ls.totalSeconds,
                pushSegments = ls.pushSegmentsDone,
                overPushMinSec = ls.overPushMinSec,
                distanceMiles = ls.distanceMiles.toFloat(),
                avgPushSpeed = ls.avgPushSpeedMph,
                avgRecoverySpeed = ls.avgRecoverySpeedMph,
                avgOverallSpeed = ls.avgOverallSpeedMph,
                avgPushHr = ls.avgPushHr,
                avgRecoveryHr = ls.avgRecoveryHr,
                avgOverallHr = ls.avgOverallHr,
                avgPushPace = ls.avgPushPace,
                avgRecoveryPace = ls.avgRecoveryPace,
                avgOverallPace = ls.avgOverallPace,
            )
            val id = container.workoutDao.insert(entity)
            // Health Connect backfill: only when the Wear relay is off; real-time
            // values already recorded (BLE strap) are never overwritten — each
            // backfilled field fills only what is still null.
            if (_hcBackfillHr.value && !_useWearHr.value && activeProfileAtFinish != null) {
                val hc = runCatching {
                    healthConnectHrForWorkout(getApplication(), entity.copy(id = id), activeProfileAtFinish)
                }.getOrNull() ?: return@launch
                val merged = entity.copy(
                    id = id,
                    avgOverallHr = entity.avgOverallHr ?: hc.avgOverall,
                    avgPushHr = entity.avgPushHr ?: hc.avgPush,
                    avgRecoveryHr = entity.avgRecoveryHr ?: hc.avgRecovery,
                    minHr = hc.minHr,
                    maxHr = hc.maxHr,
                )
                if (merged != entity) container.workoutDao.update(merged)
            }
        }
        // Baseline: after any baseline workout, re-derive the calibrated profile
        // (fixed 30-minute length and 120 s intervals; the pace and HR bands
        // come from this session's push/recovery averages, while the speed
        // targets are preserved untouched).
        val active = _activeProfile.value
        if (active != null && isBaselineProfile(active)) {
            val updated = updatedBaselineProfile(
                baseline = active,
                pushPaceSpm = ls.avgPushPace,
                recoveryPaceSpm = ls.avgRecoveryPace,
                pushHrBpm = ls.avgPushHr,
                recoveryHrBpm = ls.avgRecoveryHr,
            )
            if (updated != active) {
                viewModelScope.launch {
                    container.configStore.saveProfiles(
                        _profiles.value.map { if (it.id == active.id) updated else it }
                    )
                }
            }
            // One-shot: the UI opens Settings and confirms the baseline was made.
            _baselineCreatedMessage.value = "Baseline created"
        }
    }

    /** End the running session now (ADHOC finish, or early stop for finite modes). */
    fun endWorkout() {
        if (engine?.snapshot?.running != true) return
        engine?.endNow()
        onFinished()
        tickerJob?.cancel()
    }
    /** Pause or resume the running session (toggles on the live paused flag). */
    fun togglePause() {
        val s = engine?.snapshot ?: return
        if (s.paused) engine?.resume() else engine?.pause()
    }

    fun discardWorkout() {
        debugLog.log("[workout] discard")
        tickerJob?.cancel()
        WorkoutService.stop(getApplication())
        lastWatchState = byteArrayOf()
        // Fully reset: replace the engine so the next Start begins a clean
        // session, and live state returns to its idle snapshot.
        setupEngine()
    }

    override fun onCleared() {
        super.onCleared()
        WorkoutService.stop(getApplication())
        runCatching {
            com.google.android.gms.wearable.Wearable.getMessageClient(getApplication())
                .removeListener(wearPauseListener)
        }
        stopSources()
        speaker.shutdown()
    }
}

class MainViewModelFactory(
    private val app: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MainViewModel(app) as T
}

/** Paths shared with the Wear companion over the Wearable message layer. */
private const val WEAR_PAUSE_PATH = "/morkstep/pause"
private const val WEAR_STATE_PATH = "/morkstep/state"
/** Bytes of the /morkstep/state payload; the watch decodes exactly this many. */
private const val WATCH_STATE_BYTES = 47

/** Await a Google Play Services [Task] (mirrors the wear app). */
private fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    com.google.android.gms.tasks.Tasks.await(this)