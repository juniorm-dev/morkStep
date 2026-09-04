package com.morkstep.data

import com.morkstep.Constants

/**
 * Baseline profile lifecycle.
 *
 * "Create baseline" installs a short calibration workout (3 rounds, 45 s
 * push / 45 s recovery, 20 s warm-up, no cool-down). When that workout ends,
 * the active Baseline profile is re-derived into the calibrated 30-minute
 * baseline, pacing it off the phase averages actually recorded.
 *
 * Speed-band semantics (see the engine): the "ceiling" is the recovery-phase
 * cap ("Slow down" while recovery speed stays above it) and the "floor" is the
 * push-phase floor ("Speed up" while push speed stays below it). The field
 * names predate that role split and may be renamed later.
 */

/** True for the Baseline profile by name (identity used by the home label and post-workout update). */
fun isBaselineProfile(p: WorkoutProfile): Boolean = p.name == Constants.BASELINE_PROFILE_NAME

/** The calibration profile for a fresh baseline, preserving [id] when re-creating. */
fun baselineCalibrationProfile(id: Long): WorkoutProfile = WorkoutProfile(
    id = id,
    name = Constants.BASELINE_PROFILE_NAME,
    lengthMode = WorkoutLength.ROUNDS,
    rounds = Constants.BASELINE_ROUNDS,
    warmupSec = Constants.BASELINE_WARMUP_SEC,
    fastSec = Constants.BASELINE_PUSH_SEC,
    slowSec = Constants.BASELINE_RECOVERY_SEC,
    cooldownSec = Constants.BASELINE_COOLDOWN_SEC,
)

/**
 * Re-derive [baseline] after a workout: fixed 30-minute time length, 120 s /
 * 120 s / 30 s / 30 s intervals, recovery-speed ceiling and push-speed floor
 * taken from the session's speed averages, and the recovery-pace ceiling and
 * push-pace floor taken from the session's pace (steps/min) averages. Targets
 * only update when the relevant averages were recorded; otherwise the previous
 * targets are kept. Results are clamped to the Config slider ranges so they can
 * never be edited away.
 */
fun updatedBaselineProfile(
    baseline: WorkoutProfile,
    pushSpeedMph: Double?,
    recoverySpeedMph: Double?,
    pushPaceSpm: Int? = null,
    recoveryPaceSpm: Int? = null,
): WorkoutProfile {
    val ceiling = (recoverySpeedMph ?: baseline.speedCeilingMph)
        .coerceIn(Constants.BASELINE_MIN_PACE_CEILING_MPH, Constants.BASELINE_MAX_PACE_CEILING_MPH)
    val floor = (pushSpeedMph ?: baseline.speedFloorMph)
        .coerceIn(Constants.BASELINE_MIN_PACE_FLOOR_MPH, Constants.BASELINE_MAX_PACE_FLOOR_MPH)
    val paceCeiling = (recoveryPaceSpm ?: baseline.paceCeilingSpm)
        .coerceIn(Constants.BASELINE_MIN_PACE_CEILING_SPM, Constants.BASELINE_MAX_PACE_CEILING_SPM)
    val paceFloor = (pushPaceSpm ?: baseline.paceFloorSpm)
        .coerceIn(Constants.BASELINE_MIN_PACE_FLOOR_SPM, Constants.BASELINE_MAX_PACE_FLOOR_SPM)
    return baseline.copy(
        lengthMode = WorkoutLength.TIME,
        timeMinutes = Constants.BASELINE_UPDATED_TIME_MIN,
        fastSec = Constants.BASELINE_UPDATED_PUSH_SEC,
        slowSec = Constants.BASELINE_UPDATED_RECOVERY_SEC,
        warmupSec = Constants.BASELINE_UPDATED_WARMUP_SEC,
        cooldownSec = Constants.BASELINE_UPDATED_COOLDOWN_SEC,
        speedCeilingMph = ceiling,
        speedFloorMph = floor,
        paceCeilingSpm = paceCeiling,
        paceFloorSpm = paceFloor,
    )
}
