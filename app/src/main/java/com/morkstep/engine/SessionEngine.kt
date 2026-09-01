package com.morkstep.engine

import com.morkstep.Constants
import com.morkstep.data.PhaseType
import com.morkstep.data.WorkoutLength
import com.morkstep.data.WorkoutProfile
import com.morkstep.sensing.HeartRateSource
import com.morkstep.sensing.PaceSource
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/** Haptic class of a cue: phase transitions, or any guidance cue. */
enum class CueVibration { TRANSITION, GUIDANCE }

/** Receives audio cues from the session engine (abstracted for testability). */
interface CueSink {
    fun beep()
    fun speak(text: String)
    /** Haptic mirror of a cue. Default no-op so sinks without haptics are unaffected. */
    fun vibrate(kind: CueVibration) = Unit
}

/** Live snapshot of the running interval session. */
data class LiveState(
    val running: Boolean = false,
    val finished: Boolean = false,
    /** True while the session is frozen by [SessionEngine.pause]; elapsed time, distance and cues are stopped. */
    val paused: Boolean = false,
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
    /** 1 Hz moving averages: pace in mph during push/recovery/overall. */
    val avgPushPaceMph: Float? = null,
    val avgRecoveryPaceMph: Float? = null,
    val avgOverallPaceMph: Float? = null,
    /** 1 Hz moving averages: HR in bpm during push/recovery/overall. */
    val avgPushHr: Int? = null,
    val avgRecoveryHr: Int? = null,
    val avgOverallHr: Int? = null,
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
 * Emits phase-change beeps + announcements, pace/HR warning cues, quarter
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
    private var pausedAtMs = 0L
    private var coreEndSec = Long.MAX_VALUE
    private var finishSec = Long.MAX_VALUE
    private var lastDistTick = 0
    private var distance = 0.0
    private var latched = false
    private var lastPhase: PhaseType? = null
    /** Suppresses the first warning cue in a newly-entered phase (sensor value from the previous phase is stale). */
    private var firstWarningCuePending = false
    private var lastQuarter = 0
    private var lastAdhocCueN = 0
    private val lastCueAt = mutableMapOf<String, Long>()
    /**
     * Min gap between repeats of the same warning cue, in millis. Driven by the
     * profile's shared warning threshold (seconds) so push and recovery cue cadence
     * is user-configurable.
     */
    private val cueCooldownMs: Long get() = profile.warningThresholdSec.coerceAtLeast(1) * Constants.MILLIS_PER_SECOND

    // Phase-average accumulators (pace in mph, HR in bpm).
    private var fastPaceSum = 0.0
    private var fastPaceCnt = 0
    private var slowPaceSum = 0.0
    private var slowPaceCnt = 0
    private var allPaceSum = 0.0
    private var allPaceCnt = 0
    private var fastHrSum = 0L
    private var fastHrCnt = 0
    private var slowHrSum = 0L
    private var slowHrCnt = 0
    private var allHrSum = 0L
    private var allHrCnt = 0

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
        fastPaceSum = 0.0; fastPaceCnt = 0
        slowPaceSum = 0.0; slowPaceCnt = 0
        allPaceSum = 0.0; allPaceCnt = 0
        fastHrSum = 0L; fastHrCnt = 0
        slowHrSum = 0L; slowHrCnt = 0
        allHrSum = 0L; allHrCnt = 0
        _state.value = snapshot.copy(
            running = true, finished = false, paused = false, totalSeconds = 0, secondsInPhase = 0,
            phase = PhaseType.WARMUP,
            phaseOrdinal = 1, fastSegmentsDone = 0, overCeilingSec = 0, distanceMiles = 0.0,
            progress = null, pace = paceSource.pace.value, hr = hrSource.hr.value,
            lengthLabel = profile.lengthLabel(),
        )
        tick()
    }

    /** Advance the session to the current wall-clock time. Call ~1 Hz while running. */
    fun tick() {
        if (!snapshot.running || snapshot.finished || snapshot.paused) return
        val rawT = ((clock.nowMillis() - launchedAtMs) / Constants.MILLIS_PER_SECOND).toInt()
        val t = if (finishSec < Long.MAX_VALUE) rawT.coerceAtMost(finishSec.toInt()) else rawT
        if (t < lastDistTick) return
        // Integrate distance from pace over the elapsed interval.
        val dt = t - lastDistTick
        lastDistTick = t
        val mph = snapshot.pace ?: 0f
        if (dt > 0) distance += mph * dt / Constants.SECONDS_PER_HOUR

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
            cue.vibrate(CueVibration.TRANSITION)
            announce(pa.phase, pa.fastDone + 1)
            lastPhase = pa.phase
            lastCueAt.clear()
            firstWarningCuePending = true
        }

        // Sample pace/HR once per tick into phase buckets (1 Hz averages).
        paceSource.pace.value?.let { p ->
            allPaceSum += p; allPaceCnt++
            when (pa.phase) {
                PhaseType.FAST -> { fastPaceSum += p; fastPaceCnt++ }
                PhaseType.SLOW -> { slowPaceSum += p; slowPaceCnt++ }
                else -> Unit
            }
        }
        hrSource.hr.value?.let { h ->
            allHrSum += h; allHrCnt++
            when (pa.phase) {
                PhaseType.FAST -> { fastHrSum += h; fastHrCnt++ }
                PhaseType.SLOW -> { slowHrSum += h; slowHrCnt++ }
                else -> Unit
            }
        }
        val avgPushPace = if (fastPaceCnt > 0) (fastPaceSum / fastPaceCnt).toFloat() else null
        val avgRecoveryPace = if (slowPaceCnt > 0) (slowPaceSum / slowPaceCnt).toFloat() else null
        val avgOverallPace = if (allPaceCnt > 0) (allPaceSum / allPaceCnt).toFloat() else null
        val avgPushHr = if (fastHrCnt > 0) (fastHrSum / fastHrCnt).toInt() else null
        val avgRecoveryHr = if (slowHrCnt > 0) (slowHrSum / slowHrCnt).toInt() else null
        val avgOverallHr = if (allHrCnt > 0) (allHrSum / allHrCnt).toInt() else null

        // Quarter cues keyed to the length dimension the user chose:
        // ROUNDS → quarters of the round count; DISTANCE/TIME → quarters of
        // miles/minutes; ADHOC → every-Nth-push cue only.
        val prog = progressAt(t, profile, coreEndSec, finishSec, distance)
        val q = when (profile.lengthMode) {
            WorkoutLength.ROUNDS -> {
                val target = profile.rounds
                when {
                    target <= 0 -> 0
                    pa.fastDone >= ceil(target * 0.75).toInt() -> 3
                    pa.fastDone >= ceil(target * 0.50).toInt() -> 2
                    pa.fastDone >= ceil(target * 0.25).toInt() -> 1
                    else -> 0
                }
            }
            WorkoutLength.TIME, WorkoutLength.DISTANCE -> {
                if (prog == null) 0 else when {
                    prog >= 0.75f -> 3
                    prog >= 0.50f -> 2
                    prog >= 0.25f -> 1
                    else -> 0
                }
            }
            WorkoutLength.ADHOC -> 0
        }
        if (q > lastQuarter) {
            lastQuarter = q
            speak(qText(q))
            cue.vibrate(CueVibration.GUIDANCE)
        }

        // ADHOC: cue on every Nth completed push round.
        val n = profile.adhocCueEveryNPush
        if (profile.lengthMode == WorkoutLength.ADHOC && n > 0 && pa.fastDone > lastAdhocCueN &&
            pa.fastDone % n == 0
        ) {
            lastAdhocCueN = pa.fastDone
            speak("Push round ${pa.fastDone} complete")
            cue.vibrate(CueVibration.GUIDANCE)
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
            avgPushPaceMph = avgPushPace,
            avgRecoveryPaceMph = avgRecoveryPace,
            avgOverallPaceMph = avgOverallPace,
            avgPushHr = avgPushHr,
            avgRecoveryHr = avgRecoveryHr,
            avgOverallHr = avgOverallHr,
        )

        if (finished) {
            cue.beep()
            cue.vibrate(CueVibration.TRANSITION)
            speak("Workout complete")
            return
        }

        if (!entered) ratePhase()
    }

    /** Manually end an ADHOC workout (or stop any workout early). */
    fun endNow() {
        if (!snapshot.running || snapshot.finished) return
        _state.value = snapshot.copy(running = false, finished = true, paused = false)
    }

    /** Freeze the session at the current instant: elapsed time, distance and cues stop until [resume]. */
    fun pause() {
        val s = snapshot
        if (!s.running || s.finished || s.paused) return
        pausedAtMs = clock.nowMillis()
        _state.value = s.copy(paused = true)
    }

    /** Continue a paused session; elapsed time resumes where it froze, paused wall-clock is excluded. */
    fun resume() {
        val s = snapshot
        if (!s.running || s.finished || !s.paused) return
        launchedAtMs += clock.nowMillis() - pausedAtMs
        _state.value = s.copy(paused = false)
    }

    private fun announce(phase: PhaseType, pushNumber: Int = 0) {
        if (!profile.audioCues) return
        when (phase) {
            PhaseType.WARMUP -> cue.speak("Begin with an easy warm-up walk")
            PhaseType.FAST -> cue.speak("Push phase $pushNumber. Maintain a brisk pace")
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
                if (s.hr != null && s.hr > profile.hrCeiling) {
                    _state.value = s.copy(overCeilingSec = s.overCeilingSec + 1)
                }
                // The first warning cue after a phase transition is suppressed:
                // the sensor value carried over from the previous phase is stale.
                if (firstWarningCuePending) { firstWarningCuePending = false } else {
                    // Speed up while the push target (HR/pace ceiling) is unmet.
                    // HR and pace share one cue so they never double-fire, and a
                    // reading without a meaningful signal never triggers a cue:
                    // HR below the min-signal threshold, or pace at/below its
                    // min-signal threshold.
                    val hrBelow = s.hr != null && s.hr >= Constants.MIN_VALID_HR_BPM && s.hr < profile.hrCeiling
                    val paceBelow = s.pace != null && s.pace > Constants.MIN_VALID_PACE_MPH && s.pace < profile.paceCeilingMph.toFloat()
                    if (hrBelow || paceBelow) cueIf("speedUp", "Speed up")
                }
            }
            PhaseType.SLOW -> {
                if (firstWarningCuePending) { firstWarningCuePending = false } else {
                    // Slow down while the recovery target (HR/pace floor) is unmet.
                    val hrAbove = s.hr != null && s.hr >= Constants.MIN_VALID_HR_BPM && s.hr > profile.hrFloor
                    val paceAbove = s.pace != null && s.pace > Constants.MIN_VALID_PACE_MPH && s.pace > profile.paceFloorMph.toFloat()
                    if (hrAbove || paceAbove) cueIf("slowDown", "Slow down")
                }
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
        cue.vibrate(CueVibration.GUIDANCE)
    }
}