package com.morkstep.data

import kotlinx.serialization.Serializable

/** Phase of a workout interval. */
enum class PhaseType { WARMUP, FAST, SLOW, COOLDOWN }

/** How the length of a workout is determined. */
enum class WorkoutLength { ROUNDS, DISTANCE, TIME, ADHOC }

/**
 * One named workout configuration (profile).
 *
 * All pace values are miles per hour. [lengthMode] decides when the workout
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
    /** Target walking-pace band (mph). Push intervals aim within this band. */
    val paceCeilingMph: Double = 4.5,
    val paceFloorMph: Double = 3.2,
    /** Heart-rate band (bpm) targeted during push intervals. */
    val hrCeiling: Int = 150,
    val hrFloor: Int = 120,
    /** Audio cues enabled on/off. */
    val audioCues: Boolean = true,
) {
    val totalSeconds: Long
        get() = when (lengthMode) {
            WorkoutLength.ROUNDS -> warmupSec.toLong() + rounds.toLong() * (fastSec + slowSec) + cooldownSec
            WorkoutLength.TIME -> timeMinutes.toLong() * 60
            WorkoutLength.DISTANCE, WorkoutLength.ADHOC -> Long.MAX_VALUE // pace-dependent / indefinite
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