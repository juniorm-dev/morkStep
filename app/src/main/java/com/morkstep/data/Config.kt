package com.morkstep.data

import kotlinx.serialization.Serializable

/** Phase of a workout interval. */
enum class PhaseType { WARMUP, FAST, SLOW, COOLDOWN }

/** How the length of a workout is determined. */
enum class WorkoutLength { ROUNDS, DISTANCE, TIME, ADHOC }

/** Device haptics for workout cues: none, phase transitions only, or every cue. */
enum class VibrationMode { OFF, PHASE_CHANGE, ALL }

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
     * above this cap, while [pushSpeedFloorMph] floors push.
     */
    val recoverySpeedCapMph: Double = 3.2,
    /**
     * Push-phase speed floor (mph): push cues "Speed up" while speed is below
     * this; see [recoverySpeedCapMph].
     */
    val pushSpeedFloorMph: Double = 4.5,
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
    /** Audio cues enabled on/off. */
    val audioCues: Boolean = true,
    /** When to vibrate the phone (and the paired watch, if enabled): phase changes only, or all cues. */
    val vibrationMode: VibrationMode = VibrationMode.OFF,
    /** Cue vibration strength 0..1 (scales the amplitude of phone/watch haptics). */
    val vibrationIntensity: Float = 0.5f,
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