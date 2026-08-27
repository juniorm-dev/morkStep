package com.morkstep.engine

import com.morkstep.data.IntervalConfig
import com.morkstep.data.IntervalSegment
import com.morkstep.data.PhaseType
import com.morkstep.sensing.HeartRateSource
import com.morkstep.sensing.PaceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/** Receives audio cues from the session engine (abstracted for testability). */
interface CueSink {
    fun beep()
    fun speak(text: String)
}

/** Live snapshot of the running interval session. */
data class LiveState(
    val running: Boolean = false,
    val finished: Boolean = false,
    val phase: PhaseType = PhaseType.WARMUP,
    val phaseIndex: Int = 0,
    val totalSegments: Int = 1,
    val secondsInPhase: Int = 0,
    val totalSeconds: Int = 0,
    val totalPlannedSec: Int = 1,
    val pace: Float? = null,
    val hr: Int? = null,
    val overCeilingSec: Int = 0,
    val fastSegmentsDone: Int = 0,
)

/** Wall-clock abstraction so the ticker is unit-testable. */
interface SessionClock {
    fun nowMillis(): Long
}

object SystemClock : SessionClock {
    override fun nowMillis(): Long = android.os.SystemClock.elapsedRealtime()
}

/** Index of the segment covering [totalSec], and seconds elapsed within it. Pure. */
internal fun segmentIndexFor(totalSec: Int, segments: List<IntervalSegment>): Int {
    var acc = 0
    for ((i, seg) in segments.withIndex()) {
        if (totalSec < acc + seg.seconds) return i
        acc += seg.seconds
    }
    return segments.lastIndex
}

internal fun secondsInSegment(totalSec: Int, segments: List<IntervalSegment>): Int {
    val idx = segmentIndexFor(totalSec, segments)
    var acc = 0
    for (i in 0 until idx) acc += segments[i].seconds
    return totalSec - acc
}
/** Number of FAST segments fully completed by [totalSec] (plan-relative, tick-cadence independent). */
internal fun completedFastSegments(totalSec: Int, segments: List<IntervalSegment>): Int {
    var acc = 0
    var done = 0
    for (seg in segments) {
        acc += seg.seconds
        if (acc <= totalSec && seg.type == PhaseType.FAST) done++
    }
    return done
}

/**
 * Interval Walking Training session engine.
 *
 * Advances the configured segment plan on wall-clock time and emits spoken
 * audio cues when pace/HR cross the configured boundaries. Observe sensor
 * values via [start], drive the elapsed clock forward with [tick].
 */
class SessionEngine(
    private val config: IntervalConfig,
    private val paceSource: PaceSource,
    private val hrSource: HeartRateSource,
    private val cue: CueSink,
    private val clock: SessionClock = SystemClock,
) {
    private val _state = MutableStateFlow(
        LiveState(totalPlannedSec = config.totalSeconds, totalSegments = config.segments.size)
    )
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var launchedAtMs = 0L
    private val lastCueAt = mutableMapOf<String, Long>()
    private val cueCooldownMs = 8_000L

    val snapshot: LiveState get() = _state.value

    /** Start observing sensor values. Call once when the engine is owned. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(paceSource.pace, hrSource.hr) { p, h -> p to h }.collect { (p, h) ->
                if (snapshot.running && !snapshot.finished) {
                    _state.value = snapshot.copy(pace = p, hr = h)
                }
            }
        }
    }

    /** Begin the workout (no-op if already running or finished). */
    fun run() {
        if (snapshot.running || snapshot.finished) return
        launchedAtMs = clock.nowMillis()
        _state.value = snapshot.copy(
            running = true, finished = false, totalSeconds = 0, secondsInPhase = 0,
            phaseIndex = 0, phase = config.segments.first().type, fastSegmentsDone = 0,
            pace = paceSource.pace.value, hr = hrSource.hr.value,
        )
    }

    /** Advance the session to the current wall-clock time. Call ~1 Hz while running. */
    fun tick() {
        if (!snapshot.running || snapshot.finished) return
        val nowSec = ((clock.nowMillis() - launchedAtMs) / 1000L).toInt().coerceIn(0, config.totalSeconds)
        if (nowSec == snapshot.totalSeconds && !snapshot.finished) return
        val idx = segmentIndexFor(nowSec, config.segments)
        val secIn = secondsInSegment(nowSec, config.segments)
        val phase = config.segments[idx].type
        val entered = nowSec != snapshot.totalSeconds && idx != snapshot.phaseIndex
        if (entered) {
            cue.beep()
            onPhaseEnter(config.segments[idx])
            lastCueAt.clear()
        }
        val fastDone = completedFastSegments(nowSec, config.segments)
        _state.value = snapshot.copy(
            totalSeconds = nowSec,
            phaseIndex = idx,
            phase = phase,
            secondsInPhase = secIn,
            fastSegmentsDone = fastDone,
            finished = nowSec >= config.totalSeconds,
        )
        if (!snapshot.finished) ratePhase()
    }

    private fun onPhaseEnter(seg: IntervalSegment) {
        when (seg.type) {
            PhaseType.WARMUP -> cue.speak("Begin with an easy warm-up walk")
            PhaseType.FAST -> cue.speak("Push phase. Maintain a brisk pace")
            PhaseType.SLOW -> cue.speak("Recovery walking. Stay relaxed")
            PhaseType.COOLDOWN -> cue.speak("Cooldown. Ease down")
        }
    }

    private fun ratePhase() {
        val s = snapshot
        when (s.phase) {
            PhaseType.FAST -> {
                if (s.pace != null && s.pace < config.paceFloor.toFloat()) cueIf("paceUp", "Speed up a little")
                if (s.hr != null && s.hr > config.hrCeiling) {
                    _state.value = s.copy(overCeilingSec = s.overCeilingSec + 1)
                    cueIf("hrHigh", "That's quite high. Ease off a touch")
                }
            }
            PhaseType.SLOW -> {
                if (s.pace != null && s.pace > config.paceFloor.toFloat()) cueIf("paceSlow", "Stand easy now")
            }
            else -> Unit
        }
    }

    private fun cueIf(key: String, text: String) {
        val now = clock.nowMillis()
        if (now - (lastCueAt[key] ?: 0L) < cueCooldownMs) return
        lastCueAt[key] = now
        cue.speak(text)
    }
}