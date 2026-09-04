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
 * All speed values are miles per hour. [lengthMode] decides when the workout
 * ends; the phase cycle is always warm-up → (fast/slow) repeats → cool-down.
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
    val fastSec: Int = 180,
    val slowSec: Int = 180,
    val cooldownSec: Int = 120,
    /**
     * Recovery-phase speed cap (mph): recovery cues "Slow down" while speed is
     * above this. (Named "ceiling" for historical reasons; it caps recovery,
     * while [speedFloorMph] floors push — the naming may change in a future update.)
     */
    val speedCeilingMph: Double = 4.5,
    /**
     * Push-phase speed floor (mph): push cues "Speed up" while speed is below
     * this. (Named "floor" for historical reasons; see [speedCeilingMph].)
     */
    val speedFloorMph: Double = 3.2,
    /**
     * Heart rate (bpm) — Recovery Max: the upper HR bound. Push cues
     * "Speed up" while HR is below this; recovery keeps HR below this.
     */
    val hrCeiling: Int = 150,
    /**
     * Heart rate (bpm) — Push Min: the lower HR bound. Recovery cues
     * "Slow down" while HR is above this; a push keeps HR above this.
     */
    val hrFloor: Int = 120,
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
            WorkoutLength.ROUNDS -> warmupSec.toLong() + rounds.toLong() * (fastSec + slowSec) + cooldownSec
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