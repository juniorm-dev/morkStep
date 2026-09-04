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
    /** Last state payload sent to the watch; identical snapshots are not re-sent. */
    private var lastWatchState = byteArrayOf()

    // Real sources (only live while simulated mode is OFF).
    private var gps: GpsSpeedSource? = null
    private var ble: BleHeartRateSource? = null
    private var wear: WearHeartRateSource? = null
    private var wearPace: WearPaceSource? = null
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
        refreshHealthConnectState()
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
        } else {
            gps = GpsSpeedSource(getApplication())
            ble = BleHeartRateSource(getApplication())
            wearPace = WearPaceSource(getApplication())
            if (_useWearHr.value) {
                wear = WearHeartRateSource(getApplication())
            }
            _sensorNote.value =
                if (_useWearHr.value) "GPS speed · Wear pace & heart rate" else "GPS speed · Wear pace · BLE heart rate"
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
        gps = null
        ble = null
        wear = null
        wearPace = null
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
        // Pace comes from the pedometer on the watch. In simulated mode the
        // sim drives it; otherwise the Wear relay (always created in real
        // mode). A missing watch just leaves the relay silent — no fallback.
        val paceSrc: PaceSource = if (_simulated.value) sim!! else wearPace!!
        engineJob?.cancel()
        val e = SessionEngine(p, speedSrc, hrSrc, paceSrc, sink)
        engine = e
        engineJob = viewModelScope.launch {
            // Sensor observation and live-state fan-out share one job so a
            // replaced/discarded engine is fully torn down (no stale updates).
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
        viewModelScope.launch { container.configStore.setActive(id) }
    }

    /** Save edits to any existing profile (by id). */
    fun updateProfile(updated: WorkoutProfile) {
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
        if (p.fastSec <= 0 || p.slowSec <= 0) return
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
            .putInt(ls.fastSegmentsDone)
            .putInt(ls.fastRoundsTotal ?: -1)
            .putInt(p.fastSec)
            .putInt(p.slowSec)
            .putFloat(p.speedFloorMph.toFloat())
            .putFloat(p.speedCeilingMph.toFloat())
            .putInt(p.paceFloorSpm)
            .putInt(p.paceCeilingSpm)
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
                fastSegments = ls.fastSegmentsDone,
                avgFastSpeed = ls.avgOverallSpeedMph,
                avgHeartRate = ls.avgOverallHr,
                overCeilingSec = ls.overCeilingSec,
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
                    avgHeartRate = entity.avgHeartRate ?: hc.avgOverall,
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
        // (fixed 30-minute length and 120 s intervals; the speed band comes from
        // this session's push/recovery averages).
        val active = _activeProfile.value
        if (active != null && isBaselineProfile(active)) {
            val updated = updatedBaselineProfile(
                baseline = active,
                pushSpeedMph = ls.avgPushSpeedMph?.toDouble(),
                recoverySpeedMph = ls.avgRecoverySpeedMph?.toDouble(),
                pushPaceSpm = ls.avgPushPace,
                recoveryPaceSpm = ls.avgRecoveryPace,
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