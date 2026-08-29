package com.morkstep.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.morkstep.AppContainer
import com.morkstep.MorkApplication
import com.morkstep.WorkoutService
import com.morkstep.audio.CueSpeaker
import com.morkstep.data.WorkoutEntity
import com.morkstep.data.WorkoutProfile
import com.morkstep.data.defaultProfile
import com.morkstep.engine.CueSink
import com.morkstep.engine.LiveState
import com.morkstep.engine.SessionEngine
import com.morkstep.sensing.GpsPaceSource
import com.morkstep.sensing.BleHeartRateSource
import com.morkstep.sensing.HeartRateSource
import com.morkstep.sensing.PaceSource
import com.morkstep.sensing.SimulatedSensors
import com.morkstep.sensing.WearHeartRateSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private class SpeakerSink(private val speaker: CueSpeaker) : CueSink {
    override fun beep() = speaker.beep()
    override fun speak(text: String) = speaker.speak(text)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container: AppContainer = (app as MorkApplication).container
    private val speaker = CueSpeaker(app)
    private val sink = SpeakerSink(speaker)

    private var engine: SessionEngine? = null
    private var tickerJob: Job? = null
    /** Collects sensor + live-state for the current engine; cancelled when it is replaced. */
    private var engineJob: Job? = null

    // Real sources (only live while simulated mode is OFF).
    private var gps: GpsPaceSource? = null
    private var ble: BleHeartRateSource? = null
    private var wear: WearHeartRateSource? = null
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

    /** One-shot: flips true when a profile is saved; consumed back by the UI. */
    private val _savedProfile = MutableStateFlow(false)
    val savedProfile: StateFlow<Boolean> = _savedProfile.asStateFlow()

    init {
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

    /** (Re)create pace/HR sources per the simulated toggle. Real sources do NOT fall back. */
    private fun rebuildSources() {
        stopSources()
        refreshPermissions()
        if (_simulated.value) {
            sim = SimulatedSensors()
            _sensorNote.value = "Simulated sensors (debug)"
        } else {
            gps = GpsPaceSource(getApplication())
            ble = BleHeartRateSource(getApplication())
            if (_useWearHr.value) {
                wear = WearHeartRateSource(getApplication())
            }
            _sensorNote.value =
                if (_useWearHr.value) "GPS pace · Wear companion heart rate" else "GPS pace · BLE heart rate"
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
        gps = null
        ble = null
        wear = null
        sim = null
    }

    private fun refreshActive() {
        val id = _activeId.value
        _activeProfile.value = _profiles.value.firstOrNull { it.id == id }
            ?: _profiles.value.firstOrNull()
            ?: defaultProfile()
        setupEngine()
    }

    private fun setupEngine() {
        val p = _activeProfile.value ?: return
        val paceSrc: PaceSource = sim ?: gps ?: return
        val hrSrc: HeartRateSource =
            if (_simulated.value) sim!!
            else if (_useWearHr.value) wear ?: ble ?: return
            else ble ?: return
        engineJob?.cancel()
        val e = SessionEngine(p, paceSrc, hrSrc, sink)
        engine = e
        engineJob = viewModelScope.launch {
            // Sensor observation and live-state fan-out share one job so a
            // replaced/discarded engine is fully torn down (no stale updates).
            e.start(this)
            e.state.collect { _live.value = it }
        }
    }

    fun setSimulatedSensors(on: Boolean) {
        viewModelScope.launch { container.configStore.setSimulatedSensors(on) }
    }

    /** Choose heart rate source: paired Wear companion (relay) instead of BLE. */
    fun setWearHr(on: Boolean) {
        viewModelScope.launch { container.configStore.setWearHr(on) }
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
            _savedProfile.value = true
        }
    }

    /** Clear the save-confirmation flag after the UI has acted on it. */
    fun consumeSavedProfile() {
        _savedProfile.value = false
    }
    /** Clone the active profile under a fresh id and make it active. */
    fun newProfileFromActive() {
        val src = _activeProfile.value ?: return
        val fresh = src.copy(
            id = System.currentTimeMillis(),
            name = "Profile ${_profiles.value.size + 1}",
        )
        viewModelScope.launch {
            val list = _profiles.value + fresh
            container.configStore.saveProfiles(list)
            container.configStore.setActive(fresh.id)
        }
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
                delay(1000)
                engine?.tick()
                val ls = engine?.state?.value ?: break
                WorkoutService.update(getApplication(), ls)
                // Drive simulated values only when simulated mode is on.
                if (_simulated.value) sim?.setPhase(ls.phase)
                if (ls.finished) onFinished()
            }
        }
    }

    private fun onFinished() {
        val ls = engine?.state?.value ?: return
        WorkoutService.stop(getApplication())
        val ended = System.currentTimeMillis()
        val started = ended - ls.totalSeconds * 1000L
        viewModelScope.launch {
            container.workoutDao.insert(
                WorkoutEntity(
                    startTime = started,
                    endTime = ended,
                    durationSec = ls.totalSeconds,
                    fastSegments = ls.fastSegmentsDone,
                    avgFastPace = ls.avgOverallPaceMph,
                    avgHeartRate = ls.avgOverallHr,
                    overCeilingSec = ls.overCeilingSec,
                    distanceMiles = ls.distanceMiles.toFloat(),
                    avgPushPace = ls.avgPushPaceMph,
                    avgRecoveryPace = ls.avgRecoveryPaceMph,
                    avgOverallPace = ls.avgOverallPaceMph,
                    avgPushHr = ls.avgPushHr,
                    avgRecoveryHr = ls.avgRecoveryHr,
                    avgOverallHr = ls.avgOverallHr,
                )
            )
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
        // Fully reset: replace the engine so the next Start begins a clean
        // session, and live state returns to its idle snapshot.
        setupEngine()
    }

    override fun onCleared() {
        super.onCleared()
        WorkoutService.stop(getApplication())
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