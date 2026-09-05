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
 * Speed-band semantics (see the engine): the recovery-speed cap cues "Slow
 * down" while recovery speed stays above it; the push-speed floor cues "Speed
 * up" while push speed stays below it.
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
    pushSec = Constants.BASELINE_PUSH_SEC,
    slowSec = Constants.BASELINE_RECOVERY_SEC,
    cooldownSec = Constants.BASELINE_COOLDOWN_SEC,
)

/**
 * Re-derive [baseline] after a workout: fixed 30-minute time length, 120 s /
 * 120 s / 30 s / 30 s intervals, recovery-speed ceiling and push-speed floor
 * taken from the session's speed averages, the recovery-pace ceiling and
 * push-pace floor taken from the session's pace (steps/min) averages, and the
 * recovery-HR cap and push-HR floor taken from the session's bpm averages.
 * Targets only update when the relevant averages were recorded; otherwise the
 * previous targets are kept. Results are clamped to the Config slider ranges so
 * they can never be edited away.
 */
fun updatedBaselineProfile(
    baseline: WorkoutProfile,
    pushSpeedMph: Double?,
    recoverySpeedMph: Double?,
    pushPaceSpm: Int? = null,
    recoveryPaceSpm: Int? = null,
    pushHrBpm: Int? = null,
    recoveryHrBpm: Int? = null,
): WorkoutProfile {
    val recoverySpeedCap = (recoverySpeedMph ?: baseline.recoverySpeedCapMph)
        .coerceIn(Constants.BASELINE_MIN_RECOVERY_SPEED_CAP_MPH, Constants.BASELINE_MAX_RECOVERY_SPEED_CAP_MPH)
    val pushSpeedFloor = (pushSpeedMph ?: baseline.pushSpeedFloorMph)
        .coerceIn(Constants.BASELINE_MIN_PUSH_SPEED_FLOOR_MPH, Constants.BASELINE_MAX_PUSH_SPEED_FLOOR_MPH)
    val recoveryPaceCap = (recoveryPaceSpm ?: baseline.recoveryPaceCapSpm)
        .coerceIn(Constants.BASELINE_MIN_RECOVERY_PACE_CAP_SPM, Constants.BASELINE_MAX_RECOVERY_PACE_CAP_SPM)
    val pushPaceFloor = (pushPaceSpm ?: baseline.pushPaceFloorSpm)
        .coerceIn(Constants.BASELINE_MIN_PUSH_PACE_FLOOR_SPM, Constants.BASELINE_MAX_PUSH_PACE_FLOOR_SPM)
    val recoveryHrCap = (recoveryHrBpm ?: baseline.hrRecoveryMax)
        .coerceIn(Constants.BASELINE_MIN_HR_RECOVERY_MAX_BPM, Constants.BASELINE_MAX_HR_RECOVERY_MAX_BPM)
    val pushHrFloor = (pushHrBpm ?: baseline.hrPushMin)
        .coerceIn(Constants.BASELINE_MIN_HR_PUSH_MIN_BPM, Constants.BASELINE_MAX_HR_PUSH_MIN_BPM)
    return baseline.copy(
        lengthMode = WorkoutLength.TIME,
        timeMinutes = Constants.BASELINE_UPDATED_TIME_MIN,
        pushSec = Constants.BASELINE_UPDATED_PUSH_SEC,
        slowSec = Constants.BASELINE_UPDATED_RECOVERY_SEC,
        warmupSec = Constants.BASELINE_UPDATED_WARMUP_SEC,
        cooldownSec = Constants.BASELINE_UPDATED_COOLDOWN_SEC,
        recoverySpeedCapMph = recoverySpeedCap,
        pushSpeedFloorMph = pushSpeedFloor,
        recoveryPaceCapSpm = recoveryPaceCap,
        pushPaceFloorSpm = pushPaceFloor,
        hrRecoveryMax = recoveryHrCap,
        hrPushMin = pushHrFloor,
    )
}
