package com.morkstep.engine

import com.morkstep.data.PhaseType
import com.morkstep.data.WorkoutLength
import com.morkstep.data.WorkoutProfile
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
    /** 1 (fast) or 2 (slow) while inside a repeat pair; 1 for warm-up/cooldown. */
    val phaseOrdinal: Int = 1,
    val secondsInPhase: Int = 0,
    val totalSeconds: Int = 0,
    val pace: Float? = null,
    val hr: Int? = null,
    val overCeilingSec: Int = 0,
    /** Completed fast (push) segments. */
    val fastSegmentsDone: Int = 0,
    /** Distance covered in miles (integrated from pace). */
    val distanceMiles: Double = 0.0,
    /** 0..1 completion for finite modes; null for ADHOC. */
    val progress: Float? = null,
    /** Number of push rounds in the plan (ROUNDS mode) or null. */
    val fastRoundsTotal: Int? = null,
    /** Human length label, e.g. "5 rounds", "35 min", "Adhoc". */
    val lengthLabel: String = "",
)

/** Wall-clock abstraction so the ticker is unit-testable. */
interface SessionClock {
    fun nowMillis(): Long
}

object SystemClock : SessionClock {
    override fun nowMillis(): Long = android.os.SystemClock.elapsedRealtime()
}

/** Where the repeating core ends and cool-down starts, and when the workout finishes. Pure. */
internal data class Plan(val coreEndSec: Long, val finishSec: Long)

internal fun planFor(p: WorkoutProfile): Plan = when (p.lengthMode) {
    WorkoutLength.ROUNDS -> {
        val core = p.warmupSec.toLong() + p.rounds.toLong() * (p.fastSec + p.slowSec)
        Plan(core, core + p.cooldownSec)
    }
    WorkoutLength.TIME -> {
        val target = p.timeMinutes.toLong() * 60
        Plan(maxOf(target - p.cooldownSec, 0L), target)
    }
    // DISTANCE latches coreEndSec live once distance is reached; ADHOC never ends.
    WorkoutLength.DISTANCE, WorkoutLength.ADHOC -> Plan(Long.MAX_VALUE, Long.MAX_VALUE)
}

/** Count of fully-completed FAST phases by elapsed [t] (plan-relative, tick-cadence independent). */
internal fun completedFastIn(t: Int, p: WorkoutProfile): Int {
    val w = p.warmupSec
    val f = p.fastSec
    val s = p.slowSec
    if (t <= w) return 0
    val pair = f + s
    val local = t - w
    return local / pair + (if (local % pair >= f) 1 else 0)
}

/** Phase, ordinal, seconds-in-phase, and completed fast count at elapsed time [t]. Pure. */
internal data class PhaseAt(
    val phase: PhaseType,
    val secondsInPhase: Int,
    val phaseOrdinal: Int,
    val fastDone: Int,
)

internal fun phaseAt(t: Int, p: WorkoutProfile, coreEndSec: Long, finishSec: Long): PhaseAt {
    val w = p.warmupSec
    val f = p.fastSec
    val s = p.slowSec
    val pair = f + s
    fun coreFast(): Int = completedFastIn(coreEndSec.coerceAtMost(Long.MAX_VALUE).toInt(), p)
    if (t >= finishSec) return PhaseAt(PhaseType.COOLDOWN, 0, 1, coreFast())
    if (p.cooldownSec > 0 && t >= coreEndSec) {
        return PhaseAt(PhaseType.COOLDOWN, (t - coreEndSec).toInt(), 1, coreFast())
    }
    if (t < w) return PhaseAt(PhaseType.WARMUP, t, 1, 0)
    val local = t - w
    val pairs = local / pair
    val rem = local % pair
    return if (rem < f) {
        PhaseAt(PhaseType.FAST, rem, 1, pairs)
    } else {
        PhaseAt(PhaseType.SLOW, rem - f, 2, pairs + 1)
    }
}

internal fun progressAt(t: Int, p: WorkoutProfile, coreEndSec: Long, finishSec: Long, distanceMiles: Double): Float? =
    when (p.lengthMode) {
        WorkoutLength.ROUNDS, WorkoutLength.TIME -> {
            val total = finishSec.coerceAtMost(1_000_000_000L).toFloat()
            if (total <= 0f) null else (t.toFloat() / total).coerceIn(0f, 1f)
        }
        WorkoutLength.DISTANCE -> if (p.distanceMiles <= 0.0) null else (distanceMiles / p.distanceMiles).toFloat().coerceIn(0f, 1f)
        WorkoutLength.ADHOC -> null
    }

/**
 * Interval Walking Training session engine with pluggable length modes.
 *
 * ROUNDS/TIME/DISTANCE run to a natural end; ADHOC runs until [endNow].
 * Emits phase-change beeps + announcements, pace/HR band cues, quarter
 * progress cues (finite modes), and every-Nth-push cues (ADHOC).
 */
class SessionEngine(
    val profile: WorkoutProfile,
    private val paceSource: PaceSource,
    private val hrSource: HeartRateSource,
    private val cue: CueSink,
    private val clock: SessionClock = SystemClock,
) {
    private val _state = MutableStateFlow(LiveState(lengthLabel = profile.lengthLabel()))
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var launchedAtMs = 0L
    private var coreEndSec = Long.MAX_VALUE
    private var finishSec = Long.MAX_VALUE
    private var lastDistTick = 0
    private var distance = 0.0
    private var latched = false
    private var lastPhase: PhaseType? = null
    private var lastQuarter = 0
    private var lastAdhocCueN = 0
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
        val plan = planFor(profile)
        coreEndSec = plan.coreEndSec
        finishSec = plan.finishSec
        distance = 0.0
        latched = false
        lastDistTick = 0
        lastPhase = null
        lastQuarter = 0
        lastAdhocCueN = 0
        _state.value = snapshot.copy(
            running = true, finished = false, totalSeconds = 0, secondsInPhase = 0,
            phase = PhaseType.WARMUP,
            phaseOrdinal = 1, fastSegmentsDone = 0, overCeilingSec = 0, distanceMiles = 0.0,
            progress = null, pace = paceSource.pace.value, hr = hrSource.hr.value,
            lengthLabel = profile.lengthLabel(),
        )
        tick()
    }

    /** Advance the session to the current wall-clock time. Call ~1 Hz while running. */
    fun tick() {
        if (!snapshot.running || snapshot.finished) return
        val rawT = ((clock.nowMillis() - launchedAtMs) / 1000L).toInt()
        val t = if (finishSec < Long.MAX_VALUE) rawT.coerceAtMost(finishSec.toInt()) else rawT
        if (t < lastDistTick) return
        // Integrate distance from pace over the elapsed interval.
        val dt = t - lastDistTick
        lastDistTick = t
        val mph = snapshot.pace ?: 0f
        if (dt > 0) distance += mph * dt / 3600.0

        // DISTANCE: latch cool-down once the target is reached.
        if (profile.lengthMode == WorkoutLength.DISTANCE && !latched && distance >= profile.distanceMiles) {
            latched = true
            coreEndSec = t.toLong()
            finishSec = coreEndSec + profile.cooldownSec
        }

        val pa = phaseAt(t, profile, coreEndSec, finishSec)
        val entered = pa.phase != lastPhase
        if (entered) {
            cue.beep()
            announce(pa.phase)
            lastPhase = pa.phase
            lastCueAt.clear()
        }

        // Quarter progress cues (finite modes).
        val prog = progressAt(t, profile, coreEndSec, finishSec, distance)
        if (prog != null) {
            val q = when {
                prog >= 0.75f -> 3
                prog >= 0.50f -> 2
                prog >= 0.25f -> 1
                else -> 0
            }
            if (q > lastQuarter) {
                lastQuarter = q
                speak(qText(q))
            }
        }

        // ADHOC: cue on every Nth completed push round.
        val n = profile.adhocCueEveryNPush
        if (profile.lengthMode == WorkoutLength.ADHOC && n > 0 && pa.fastDone > lastAdhocCueN &&
            pa.fastDone % n == 0
        ) {
            lastAdhocCueN = pa.fastDone
            speak("Push round ${pa.fastDone} complete")
        }

        val finished = t >= finishSec

        _state.value = snapshot.copy(
            totalSeconds = t,
            phase = pa.phase,
            phaseOrdinal = pa.phaseOrdinal,
            secondsInPhase = pa.secondsInPhase,
            fastSegmentsDone = pa.fastDone,
            distanceMiles = distance,
            progress = prog,
            finished = finished,
        )

        if (finished) {
            cue.beep()
            speak("Workout complete")
            return
        }

        ratePhase()
    }

    /** Manually end an ADHOC workout (or stop any workout early). */
    fun endNow() {
        if (!snapshot.running || snapshot.finished) return
        _state.value = snapshot.copy(running = false, finished = true)
    }

    private fun announce(phase: PhaseType) {
        if (!profile.audioCues) return
        when (phase) {
            PhaseType.WARMUP -> cue.speak("Begin with an easy warm-up walk")
            PhaseType.FAST -> cue.speak("Push phase. Maintain a brisk pace")
            PhaseType.SLOW -> cue.speak("Recovery walking. Stay relaxed")
            PhaseType.COOLDOWN -> cue.speak("Cooldown. Ease down")
        }
    }

    private fun qText(q: Int): String = when (q) {
        1 -> "One quarter done"
        2 -> "Halfway there"
        else -> "Three quarters done"
    }

    private fun speak(text: String) {
        if (!profile.audioCues || text.isBlank()) return
        cue.speak(text)
    }

    private fun ratePhase() {
        val s = snapshot
        when (s.phase) {
            PhaseType.FAST -> {
                if (s.pace != null && s.pace < profile.paceFloorMph.toFloat()) cueIf("paceUp", "Speed up a little")
                if (s.hr != null && s.hr > profile.hrCeiling) {
                    _state.value = s.copy(overCeilingSec = s.overCeilingSec + 1)
                    cueIf("hrHigh", "That's quite high. Ease off a touch")
                }
            }
            PhaseType.SLOW -> {
                if (s.pace != null && s.pace > profile.paceFloorMph.toFloat()) cueIf("paceSlow", "Stand easy now")
            }
            else -> Unit
        }
    }

    private fun cueIf(key: String, text: String) {
        if (!profile.audioCues) return
        val now = clock.nowMillis()
        if (now - (lastCueAt[key] ?: 0L) < cueCooldownMs) return
        lastCueAt[key] = now
        cue.speak(text)
    }
}