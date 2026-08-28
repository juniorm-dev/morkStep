package com.morkstep.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.morkstep.AppContainer
import com.morkstep.MorkApplication
import com.morkstep.audio.CueSpeaker
import com.morkstep.data.WorkoutEntity
import com.morkstep.data.WorkoutProfile
import com.morkstep.data.defaultProfile
import com.morkstep.engine.CueSink
import com.morkstep.engine.LiveState
import com.morkstep.engine.SessionEngine
import com.morkstep.sensing.SimulatedSensors
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
    private val sensors = SimulatedSensors()

    private var engine: SessionEngine? = null
    private var tickerJob: Job? = null

    private val _profiles = MutableStateFlow(emptyList<WorkoutProfile>())
    val profiles: StateFlow<List<WorkoutProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(0L)
    val activeId: StateFlow<Long> = _activeId.asStateFlow()

    /** The currently selected profile (fresh copy each emission). */
    val activeProfile: StateFlow<WorkoutProfile?>
        get() = _activeProfile.asStateFlow()
    private val _activeProfile = MutableStateFlow<WorkoutProfile?>(null)

    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()

    init {
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

    private fun refreshActive() {
        val id = _activeId.value
        _activeProfile.value = _profiles.value.firstOrNull { it.id == id }
            ?: _profiles.value.firstOrNull()
            ?: defaultProfile()
        setupEngine()
    }

    private fun setupEngine() {
        val p = _activeProfile.value ?: return
        engine = null
        val e = SessionEngine(p, sensors, sensors, sink)
        e.start(viewModelScope)
        engine = e
        viewModelScope.launch {
            e.state.collect { _live.value = it }
        }
    }

    /** Select which profile is shown on the home screen and used for the next workout. */
    fun selectProfile(id: Long) {
        if (id == _activeId.value) return
        viewModelScope.launch { container.configStore.setActive(id) }
    }

    /** Replace the currently active profile's settings. */
    fun saveActiveProfile(updated: WorkoutProfile) {
        viewModelScope.launch {
            val list = _profiles.value.map { if (it.id == updated.id) updated else it }
            container.configStore.saveProfiles(list)
            if (!list.any { it.id == _activeId.value }) {
                container.configStore.setActive(updated.id)
            }
        }
    }

    /** Start the active profile as a new workout session. */
    fun startWorkout() {
        val p = _activeProfile.value ?: return
        if (p.fastSec <= 0 || p.slowSec <= 0) return
        engine?.run()
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (engine?.snapshot?.finished != true) {
                delay(1000)
                engine?.tick()
                val ls = engine?.state?.value ?: break
                sensors.setPhase(ls.phase)
                if (ls.finished) onFinished()
            }
        }
    }

    private fun onFinished() {
        val ls = engine?.state?.value ?: return
        val ended = System.currentTimeMillis()
        val started = ended - ls.totalSeconds * 1000L
        viewModelScope.launch {
            container.workoutDao.insert(
                WorkoutEntity(
                    startTime = started,
                    endTime = ended,
                    durationSec = ls.totalSeconds,
                    fastSegments = ls.fastSegmentsDone,
                    avgFastPace = ls.pace,
                    avgHeartRate = ls.hr,
                    overCeilingSec = ls.overCeilingSec,
                    distanceMiles = ls.distanceMiles.toFloat(),
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

    fun discardWorkout() {
        tickerJob?.cancel()
        engine = null
    }

    override fun onCleared() {
        super.onCleared()
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