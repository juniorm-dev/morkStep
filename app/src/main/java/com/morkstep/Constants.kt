package com.morkstep

/**
 * Central phone-app configuration: tuning constants and magic numbers shared
 * across sensing, haptics, and the session engine. Device behavior is
 * adjustable from this single file.
 */
object Constants {
    // ---- sensing ----
    /** Meters-per-second to miles-per-hour conversion factor (1 m/s = 2.23694 mph). */
    const val MPH_PER_MPS = 2.23694f

    /**
     * Lowest instantaneous speed (mph) that counts as a meaningful signal.
     * Readings at or below this are treated like "no signal": they never
     * trigger a Speed up / Slow down warning cue (GPS noise while standing
     * still or walking unrealistically slowly).
     */
    const val MIN_VALID_SPEED_MPH = 1.5f
    /**
     * Lowest heart rate (bpm) that counts as a meaningful signal. Readings
     * below this are treated like "no signal": they never trigger a Speed up /
     * Slow down warning cue (a 0 reading = sensor detached / no contact).
     */
    const val MIN_VALID_HR_BPM = 1
    /**
     * Lowest pedometer pace (steps per minute) that can trigger a Speed up /
     * Slow down warning cue. Readings at or below this never cue. Unlike the
     * speed floor (which filters GPS sensor drift), a pace this low is
     * deliberate — standing still or resting — so it is an intentional break,
     * not a missed target, and warnings would just be noise.
     */
    const val MIN_VALID_PACE_SPM = 10

    // ---- speed warning cues (permanently disabled) ----
    /**
     * Recovery-phase speed cap (mph) pinned to the disabled band: recovery
     * cues "Slow down" only while speed is above this cap, and a walking
     * recovery pace never exceeds 30 mph — so no recovery speed warning can
     * ever fire. Enforced on every profile load (see
     * [com.morkstep.data.WorkoutProfile.withSpeedCuesDisabled]) so stored or
     * imported profiles cannot re-arm a fireable speed target. Restore a real
     * bound here, in the [com.morkstep.data.WorkoutProfile] defaults, and
     * remove the load-time normalization to re-enable speed cues.
     */
    const val DISABLED_RECOVERY_SPEED_CAP_MPH = 30.0
    /**
     * Push-phase speed floor (mph) pinned to the disabled band: push cues
     * "Speed up" only while speed is below this floor, and speed is never
     * negative — so no push speed warning can ever fire. Enforced on every
     * profile load (see [com.morkstep.data.WorkoutProfile.withSpeedCuesDisabled])
     * so stored or imported profiles cannot re-arm a fireable speed target.
     * Restore a real bound here, in the [com.morkstep.data.WorkoutProfile]
     * defaults, and remove the load-time normalization to re-enable speed cues.
     */
    const val DISABLED_PUSH_SPEED_FLOOR_MPH = 0.0

    /**
     * Rolling window (ms) for the phone pedometer's cadence estimate
     * ([com.morkstep.sensing.PaceWindowCalculator]).
     *
     * Tradeoff: lower = more responsive, higher = more stable.
     * - 10_000 (10 s): very stable; value updates ~2 s after a step-rate change.
     * - 5_000 (5 s): responsive yet stable; updates ~1-2 s after a step-rate
     *   change (recommended default).
     * - 3_000 (3 s): quick-reacting; starts to flicker on irregular steps.
     * - 1_000-2_000 (1-2 s): noisier; cue flicker possible — not recommended
     *   for the pace floor/ceiling cues.
     * The estimator cannot emit below ~2 steps / ~1.5 s of span (hard floor);
     * shorter double-step gaps are held, not counted as a sprint.
     */
    const val PACE_WINDOW_MS = 5_000L

    /**
     * Minimum span (ms) between the oldest and newest step in the window
     * before the cadence estimator emits a value. Together with the two-step
     * minimum this is the estimator's hard floor: a short double-step gap
     * (weight shift, shuffle, pause-resume — common at slow pace) must never
     * read as a sprint cadence, so it is held instead of emitted.
     */
    const val PACE_ESTIMATOR_MIN_SPAN_MS = 1_500L

    /**
     * How long (ms) the pedometer step counter must stay silent before the
     * step detector is allowed to drive pace. While the counter is producing
     * samples its interval-based smoothed cadence is authoritative — the
     * detector's windowed estimate lags a full window behind rate changes.
     * The detector takes over after this silence so the fallback still works
     * on devices whose counter stops emitting (e.g. quiescent while the
     * screen is off).
     */
    const val PACE_COUNTER_PREFERRED_MS = 5_000L

    /**
     * Minimum pause (ms) between liveness lines in the debug log when the
     * step counter keeps publishing the same cadence. Without this a steady
     * pace is indistinguishable from a frozen sensor: the value unchanged
     * triggers neither the change-log nor a StateFlow emission, so a healthy
     * run looks dead. The heartbeat carries the live cumulative step count.
     */
    const val PACE_STEADY_LOG_MS = 10_000L

    /** Fused-location update cadence (ms) for the GPS speed source. */
    const val GPS_UPDATE_INTERVAL_MS = 1_000L
    /** Fused-location minimum update interval (ms). */
    const val GPS_MIN_UPDATE_INTERVAL_MS = 1_000L
    /** Fused-location maximum tolerated update delay before a batch is forced (ms). */
    const val GPS_MAX_UPDATE_DELAY_MS = 2_000L

    // ---- health connect HR backfill ----
    /**
     * Delays (ms) between the post-workout Health Connect HR re-reads, measured
     * from the previous attempt (so the chain spans ~1 minute to an hour after
     * the finish). Health Connect holds only what another app has already
     * synced, and a wrist heart-rate source (the watch's Health Services, a
     * strap app) commonly lands its records minutes after the session ended, so
     * the read at the finish line is a first attempt rather than the only one.
     * The chain stops at the first attempt that fills heart rate.
     */
    val HC_BACKFILL_RETRY_DELAYS_MS: List<Long> = listOf(60_000L, 5 * 60_000L, 15 * 60_000L, 45 * 60_000L)

    /**
     * How many of the most recent workouts a History-open sweep re-reads from
     * Health Connect when they recorded no heart rate at all — the catch-up for
     * a session whose HR only reached Health Connect after the app was closed,
     * when the retry chain above had nothing left to run in.
     */
    const val HC_BACKFILL_SWEEP_LIMIT = 10

    /** Oldest workout (ms since its end) a History-open sweep still re-reads. */
    const val HC_BACKFILL_SWEEP_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1_000L

    /** Minimum gap (ms) between two sweep passes, so repeatedly opening History does not re-query per tap. */
    const val HC_BACKFILL_SWEEP_THROTTLE_MS = 15 * 60_000L

    // ---- haptics ----
    /** Phone cue haptic length in ms: a clearly tactile buzz for transitions and cues. */
    const val PHONE_VIBRATE_MS = 600L
    /** VibrationEffect amplitude scale: 0 (off) .. 255 (full). */
    const val HAPTIC_AMPLITUDE_MAX = 255
    /** Phone haptics floor: a 0 amplitude would be an empty effect, so clamp to 1. */
    const val PHONE_AMPLITUDE_MIN = 1
    /** Watch intensity floor: 0 means "watch default strength" on the companion. */
    const val WATCH_AMPLITUDE_MIN = 0
    /** Path cue vibrations are relayed on to the Wear companion. Must match the wear app. */
    const val VIBRATE_PATH = "/morkstep/vibrate"
    /** Watch vibrate payload marker: phase-transition cue. */
    const val WATCH_VIBRATE_TRANSITION = 1
    /** Watch vibrate payload marker: guidance cue. */
    const val WATCH_VIBRATE_GUIDANCE = 2

    // ---- engine ----
    /** Milliseconds in one second, for tick timestamps. */
    const val MILLIS_PER_SECOND = 1_000L
    /** Seconds in one hour, for integrating mph into miles. */
    const val SECONDS_PER_HOUR = 3600.0
    /**
     * Phase-average seed offset ("Level out phase transitions" per-profile
     * toggle, [com.morkstep.data.WorkoutProfile.resetPhaseAverages]): when a
     * phase starts, its average accumulators are seeded to just inside the
     * target band — push min + this offset on entering push, recovery max -
     * this offset on entering recovery — so the new phase's average opens near
     * its target instead of carrying the previous phase's levels into it. The
     * overall average is never seeded.
     */
    const val PHASE_AVG_SEED_OFFSET = 1

    /**
     * How long (ms) warning cues stay muted after entering a new phase when the
     * profile levels out transitions
     * ([com.morkstep.data.WorkoutProfile.resetPhaseAverages], on by default).
     *
     * The seeded phase averages are display/history state — the cue verdict in
     * `SessionEngine.ratePhase()` reads the *instantaneous* merged
     * speed/HR/pace, so a seeded average cannot hold a cue back on its own. The
     * phase's first readings are still the previous phase's, and for pace a
     * batch-delivered counter sample ("+3 steps" in a 1 s delivery gap) reads
     * ~180 spm raw and lands the smoothed estimate above a recovery cap that the
     * walk never crossed — the "[warncue] Slow down: pace 130 > 110" one tick
     * after a transition. [PACE_WINDOW_MS] + one tick is the shortest window in
     * which the phone pedometer's cadence can be rebuilt from steps taken inside
     * the new phase. This is deliberately far below the shortest interval
     * ([BASELINE_PUSH_SEC] = 45 s), so a genuinely off-target phase still cues
     * promptly.
     */
    const val PHASE_TRANSITION_SETTLE_MS = 6_000L

    // ---- baseline profile ----
    /** Name of the Baseline profile; identity used by the home label and post-workout update. */
    const val BASELINE_PROFILE_NAME = "Baseline"
    // Calibration workout (installed by "Create baseline"): a few quick push/recovery rounds.
    /** Baseline calibration: number of push/recovery rounds. */
    const val BASELINE_ROUNDS = 3
    /** Baseline calibration: push interval in seconds. */
    const val BASELINE_PUSH_SEC = 45
    /** Baseline calibration: recovery interval in seconds. */
    const val BASELINE_RECOVERY_SEC = 45
    /** Baseline calibration: warm-up in seconds. */
    const val BASELINE_WARMUP_SEC = 20
    /** Baseline calibration: cool-down in seconds. */
    const val BASELINE_COOLDOWN_SEC = 0
    // Calibrated profile (applied after a baseline workout completes).
    /** Baseline calibrated: workout length in minutes. */
    const val BASELINE_UPDATED_TIME_MIN = 30
    /** Baseline calibrated: push interval in seconds. */
    const val BASELINE_UPDATED_PUSH_SEC = 120
    /** Baseline calibrated: recovery interval in seconds. */
    const val BASELINE_UPDATED_RECOVERY_SEC = 120
    /** Baseline calibrated: warm-up in seconds. */
    const val BASELINE_UPDATED_WARMUP_SEC = 30
    /** Baseline calibrated: cool-down in seconds. */
    const val BASELINE_UPDATED_COOLDOWN_SEC = 30
    // Speed targets are NOT part of baseline calibration: the disabled 30 mph
    // recovery cap / 0 mph push floor (the WorkoutProfile defaults) are
    // preserved untouched across the calibration workout, so completing a
    // baseline can never re-arm a fireable speed target.
    /** Baseline calibrated: recovery-pace cap (spm) lower bound. */
    const val BASELINE_MIN_RECOVERY_PACE_CAP_SPM = 90
    /** Baseline calibrated: recovery-pace cap (spm) upper bound. */
    const val BASELINE_MAX_RECOVERY_PACE_CAP_SPM = 140
    /** Baseline calibrated: push-pace floor (spm) lower bound. */
    const val BASELINE_MIN_PUSH_PACE_FLOOR_SPM = 80
    /** Baseline calibrated: push-pace floor (spm) upper bound. */
    const val BASELINE_MAX_PUSH_PACE_FLOOR_SPM = 130
    // Heart-rate-target clamp bounds, matching the Config screen sliders
    // (Recovery Max 70..190, Push Min 90..200) so a derived average can never
    // put a slider out of range (cap = recovery cap, floor = push floor).
    /** Baseline calibrated: recovery-HR cap (bpm) lower bound. */
    const val BASELINE_MIN_HR_RECOVERY_MAX_BPM = 70
    /** Baseline calibrated: recovery-HR cap (bpm) upper bound. */
    const val BASELINE_MAX_HR_RECOVERY_MAX_BPM = 190
    /** Baseline calibrated: push-HR floor (bpm) lower bound. */
    const val BASELINE_MIN_HR_PUSH_MIN_BPM = 90
    /** Baseline calibrated: push-HR floor (bpm) upper bound. */
    const val BASELINE_MAX_HR_PUSH_MIN_BPM = 200
}