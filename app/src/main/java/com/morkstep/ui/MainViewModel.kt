package com.morkstep.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.morkstep.AppContainer
import com.morkstep.MorkApplication
import com.morkstep.audio.CueSpeaker
import com.morkstep.data.IntervalConfig
import com.morkstep.data.WorkoutEntity
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

    private val _config = MutableStateFlow(IntervalConfig())
    val config: StateFlow<IntervalConfig> = _config.asStateFlow()

    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()

    init {
        viewModelScope.launch {
            container.configStore.config.collect { c ->
                _config.value = c
                setupEngine(c)
            }
        }
    }

    private fun setupEngine(c: IntervalConfig) {
        engine = null
        val e = SessionEngine(c, sensors, sensors, sink)
        e.start(viewModelScope)
        engine = e
        viewModelScope.launch {
            e.state.collect { _live.value = it }
        }
    }

    fun saveConfig(c: IntervalConfig) {
        viewModelScope.launch { container.configStore.save(c) }
    }

    fun startWorkout() {
        if (engine == null || _config.value.segments.isEmpty()) return
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
                    distanceKm = (ls.pace ?: 0f) * ls.totalSeconds / 3600f,
                )
            )
        }
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