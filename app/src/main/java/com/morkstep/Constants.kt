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
     * The estimator cannot emit below ~2 steps / ~1 s of span (hard floor).
     */
    const val PACE_WINDOW_MS = 5_000L

    /** Fused-location update cadence (ms) for the GPS speed source. */
    const val GPS_UPDATE_INTERVAL_MS = 1_000L
    /** Fused-location minimum update interval (ms). */
    const val GPS_MIN_UPDATE_INTERVAL_MS = 1_000L
    /** Fused-location maximum tolerated update delay before a batch is forced (ms). */
    const val GPS_MAX_UPDATE_DELAY_MS = 2_000L

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
    // Speed-target clamp bounds, matching the Config screen sliders so a derived
    // average can never put a slider out of range (cap = recovery cap,
    // floor = push floor).
    /** Baseline calibrated: recovery-speed cap (mph) lower bound. */
    const val BASELINE_MIN_RECOVERY_SPEED_CAP_MPH = 2.0
    /** Baseline calibrated: recovery-speed cap (mph) upper bound. */
    const val BASELINE_MAX_RECOVERY_SPEED_CAP_MPH = 8.0
    /** Baseline calibrated: push-speed floor (mph) lower bound. */
    const val BASELINE_MIN_PUSH_SPEED_FLOOR_MPH = 1.5
    /** Baseline calibrated: push-speed floor (mph) upper bound. */
    const val BASELINE_MAX_PUSH_SPEED_FLOOR_MPH = 7.0
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