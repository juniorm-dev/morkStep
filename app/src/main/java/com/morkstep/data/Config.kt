package com.morkstep.data

import com.morkstep.Constants
import kotlinx.serialization.Serializable

/** Phase of a workout interval. */
enum class PhaseType { WARMUP, FAST, SLOW, COOLDOWN }

/** How the length of a workout is determined. */
enum class WorkoutLength { ROUNDS, DISTANCE, TIME, ADHOC }

/** Device haptics for workout cues: none, phase transitions only, or every cue. */
enum class VibrationMode { OFF, PHASE_CHANGE, ALL }

/** Audio cues for workout cues: none, phase transitions only, or every cue. */
enum class AudioMode { OFF, PHASE_CHANGE, ALL }

/** Global app theme preference; SYSTEM follows the device setting. */
enum class DarkMode { SYSTEM, DARK, LIGHT }

/**
 * One named workout configuration (profile).
 *
 * Speed values are miles per hour; pace values are steps per minute
 * (pedometer cadence). [lengthMode] decides when the workout ends; the phase
 * cycle is always warm-up → (fast/slow) repeats → cool-down.
 */
@Serializable
data class WorkoutProfile(
    val id: Long = 0,
    val name: String = "Default",
    /** How workout length is determined. */
    val lengthMode: WorkoutLength = WorkoutLength.ADHOC,
    /** ROUNDS: number of fast/recovery pairs. */
    val rounds: Int = 5,
    /** DISTANCE: target distance in miles. */
    val distanceMiles: Double = 2.0,
    /** TIME: target total duration in minutes. */
    val timeMinutes: Int = 35,
    /** ADHOC: speak a cue on every Nth completed push round (0 = off). */
    val adhocCueEveryNPush: Int = 3,
    val warmupSec: Int = 180,
    val pushSec: Int = 180,
    val slowSec: Int = 180,
    val cooldownSec: Int = 120,
    /**
     * Recovery-phase speed cap (mph): recovery cues "Slow down" while speed is
     * above this cap, while [pushSpeedFloorMph] floors push. Pinned to the
     * disabled band — a walking recovery pace never exceeds 30 mph — so no
     * recovery speed warning can fire. Hidden from the Settings sliders and
     * re-pinned on every profile load ([withSpeedCuesDisabled]); restore a
     * real bound here and remove the load-time normalization to re-enable.
     */
    val recoverySpeedCapMph: Double = Constants.DISABLED_RECOVERY_SPEED_CAP_MPH,
    /**
     * Push-phase speed floor (mph): push cues "Speed up" while speed is below
     * this; see [recoverySpeedCapMph]. Pinned to the disabled band — speed is
     * never negative — so no push speed warning can fire. Hidden from the
     * Settings sliders and re-pinned on every profile load
     * ([withSpeedCuesDisabled]); restore a real bound here and remove the
     * load-time normalization to re-enable.
     */
    val pushSpeedFloorMph: Double = Constants.DISABLED_PUSH_SPEED_FLOOR_MPH,
    /**
     * Recovery-phase pace cap (steps per minute): recovery cues "Slow down"
     * while pace stays above this. Pedometer cadence, mirroring [recoverySpeedCapMph].
     */
    val recoveryPaceCapSpm: Int = 100,
    /**
     * Push-phase pace floor (steps per minute): push cues "Speed up" while pace
     * stays below this. Pedometer cadence, mirroring [pushSpeedFloorMph].
     */
    val pushPaceFloorSpm: Int = 110,
    /**
     * Heart rate (bpm) — Push Min: the lower HR bound during push. Push cues
     * "Speed up" while HR is below this, so a push keeps HR at or above it
     * (higher effort than recovery).
     */
    val hrPushMin: Int = 150,
    /**
     * Heart rate (bpm) — Recovery Max: the upper HR bound during recovery.
     * Recovery cues "Slow down" while HR is above this, so recovery keeps HR
     * at or below it. Lower than [hrPushMin] by design: recovery targets a
     * lower HR than push.
     */
    val hrRecoveryMax: Int = 120,
    /**
     * Seconds between repeats of the same speed/HR warning cue, shared by push
     * and recovery cues. A cue repeats at most once per interval while the
     * condition holds.
     */
    val warningThresholdSec: Int = 8,
    /**
     * Audio cue class: phase-change announcements only, or every cue (phase
     * intros, quarters, push rounds, warnings). OFF silences all audio; the
     * finish announcement counts as a phase change.
     */
    val audioMode: AudioMode = AudioMode.ALL,
    /** When to vibrate the phone (and the paired watch, if enabled): phase changes only, or all cues. */
    val vibrationMode: VibrationMode = VibrationMode.OFF,
    /** Cue vibration strength 0..1 (scales the amplitude of phone/watch haptics). */
    val vibrationIntensity: Float = 0.5f,
    /**
     * Experimental: when a phase starts, seed that phase's average accumulators
     * to just inside its target band (push min + 1 on push, recovery max - 1 on
     * recovery) instead of carrying the previous phase's levels into the new
     * phase's average. Levels out the push/recovery transition without the new
     * phase being polluted by the previous one. ON by default; on-off per
     * profile; the overall average is never seeded.
     *
     * The toggle also mutes warning cues for
     * [com.morkstep.Constants.PHASE_TRANSITION_SETTLE_MS] after a phase change:
     * the seeded averages are reported state, while the cue verdict reads the
     * live sensor signal, which right after the transition is still the previous
     * phase's.
     */
    val resetPhaseAverages: Boolean = true,
    ) {
    val totalSeconds: Long
        get() = when (lengthMode) {
            WorkoutLength.ROUNDS -> warmupSec.toLong() + rounds.toLong() * (pushSec + slowSec) + cooldownSec
            WorkoutLength.TIME -> timeMinutes.toLong() * 60
            WorkoutLength.DISTANCE, WorkoutLength.ADHOC -> Long.MAX_VALUE // speed-dependent / indefinite
        }

    /** Short human description of the length, e.g. "5 rounds" / "2.0 mi" / "35 min" / "Adhoc". */
    fun lengthLabel(): String = when (lengthMode) {
        WorkoutLength.ROUNDS -> "$rounds rounds"
        WorkoutLength.DISTANCE -> "%.1f mi".format(distanceMiles)
        WorkoutLength.TIME -> "$timeMinutes min"
        WorkoutLength.ADHOC -> "Adhoc"
    }
}

/** The default profile: adhoc length (no preset end). */
fun defaultProfile(): WorkoutProfile = WorkoutProfile()

/**
 * Pins the speed targets to the disabled band so no speed warning cue can ever
 * fire, regardless of stored or imported profile values. Applied on every
 * profile load ([com.morkstep.data.ConfigStore]) — legacy profiles saved
 * before the speed sliders were hidden carry real speed values that would
 * otherwise re-arm the engine's speed-cue checks. Restore real bounds in the
 * [WorkoutProfile] defaults and remove the load-time normalization to
 * re-enable speed cues.
 */
fun WorkoutProfile.withSpeedCuesDisabled(): WorkoutProfile = copy(
    recoverySpeedCapMph = Constants.DISABLED_RECOVERY_SPEED_CAP_MPH,
    pushSpeedFloorMph = Constants.DISABLED_PUSH_SPEED_FLOOR_MPH,
)