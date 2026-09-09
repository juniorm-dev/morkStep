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
 * Speed cues are permanently disabled: the recovery-speed cap and push-speed
 * floor are pinned to the disabled band (30 mph / 0 mph) on every profile load
 * ([WorkoutProfile.withSpeedCuesDisabled]), so speed never cues "Slow down" or
 * "Speed up" regardless of stored or imported values.
 */

/** True for the Baseline profile by name (identity used by the home label and post-workout update). */
fun isBaselineProfile(p: WorkoutProfile): Boolean = p.name == Constants.BASELINE_PROFILE_NAME

/** The calibration profile for a fresh baseline, preserving [id] when re-creating.
 *  Audio is phase-change only: the short calibration workout announces
 *  transitions (so the user knows what is happening) without the chatter of
 *  quarters/warnings; the calibrated profile that follows it re-enables all
 *  cues. */
fun baselineCalibrationProfile(id: Long): WorkoutProfile = WorkoutProfile(
    id = id,
    name = Constants.BASELINE_PROFILE_NAME,
    lengthMode = WorkoutLength.ROUNDS,
    rounds = Constants.BASELINE_ROUNDS,
    warmupSec = Constants.BASELINE_WARMUP_SEC,
    pushSec = Constants.BASELINE_PUSH_SEC,
    slowSec = Constants.BASELINE_RECOVERY_SEC,
    cooldownSec = Constants.BASELINE_COOLDOWN_SEC,
    audioMode = AudioMode.PHASE_CHANGE,
)

/**
 * Re-derive [baseline] after a workout: fixed 30-minute time length, 120 s /
 * 120 s / 30 s / 30 s intervals, the recovery-pace ceiling and push-pace
 * floor from the session's pace (steps/min) averages, and the recovery-HR cap
 * and push-HR floor from the session's bpm averages. Targets only update when
 * the relevant averages were recorded; otherwise the previous targets are
 * kept. Speed targets are left untouched: the disabled 30 mph recovery cap /
 * 0 mph push floor survive recalibration (and are re-pinned on every load
 * anyway), so completing a baseline can never re-arm a fireable speed target.
 * Results are clamped to the Config slider ranges so they can never be edited
 * away.
 */
fun updatedBaselineProfile(
    baseline: WorkoutProfile,
    pushPaceSpm: Int? = null,
    recoveryPaceSpm: Int? = null,
    pushHrBpm: Int? = null,
    recoveryHrBpm: Int? = null,
): WorkoutProfile {
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
        // The calibrated baseline is the "real" profile the user works out
        // with, so all audio cues come back on once calibration finishes.
        audioMode = AudioMode.ALL,
        // recoverySpeedCapMph / pushSpeedFloorMph deliberately NOT in the copy:
        // baseline recalibration must never reset the speed values.
        recoveryPaceCapSpm = recoveryPaceCap,
        pushPaceFloorSpm = pushPaceFloor,
        hrRecoveryMax = recoveryHrCap,
        hrPushMin = pushHrFloor,
    )
}
