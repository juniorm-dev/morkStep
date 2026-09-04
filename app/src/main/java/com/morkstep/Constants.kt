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
    // average can never put a slider out of range (ceiling = recovery cap,
    // floor = push floor).
    /** Baseline calibrated: recovery-speed ceiling (mph) lower bound. */
    const val BASELINE_MIN_PACE_CEILING_MPH = 2.0
    /** Baseline calibrated: recovery-speed ceiling (mph) upper bound. */
    const val BASELINE_MAX_PACE_CEILING_MPH = 8.0
    /** Baseline calibrated: push-speed floor (mph) lower bound. */
    const val BASELINE_MIN_PACE_FLOOR_MPH = 1.5
    /** Baseline calibrated: push-speed floor (mph) upper bound. */
    const val BASELINE_MAX_PACE_FLOOR_MPH = 7.0
}