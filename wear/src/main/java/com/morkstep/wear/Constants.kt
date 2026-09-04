package com.morkstep.wear

import androidx.compose.ui.graphics.Color

/**
 * Central wear-app configuration: tuning constants and magic numbers shared
 * across the relay layer, haptics, session defaults, and graphics. Device
 * behavior and the cross-device protocol are adjustable from this single file.
 */
object Constants {
    // ---- Relay protocol (paths must match the phone app) ----
    /** Heart-rate samples are pushed to the phone on this path. */
    const val HR_PATH = "/morkstep/hr"
    /** Pedometer pace samples (steps per minute) are pushed to the phone on this path. */
    const val PACE_PATH = "/morkstep/pace"
    /** The phone relays gated cue vibrations on this path. */
    const val VIBRATE_PATH = "/morkstep/vibrate"
    /** The phone streams its live session state on this path. */
    const val STATE_PATH = "/morkstep/state"
    /** Pause/resume commands are sent back to the phone on this path. */
    const val PAUSE_PATH = "/morkstep/pause"
    /** Full size of the phone's `/morkstep/state` payload, in bytes. */
    const val STATE_PAYLOAD_BYTES = 47
    /** Smallest accepted state payload before decoding is even attempted. */
    const val STATE_MIN_PAYLOAD_BYTES = 3
    /** Pause-command payload markers: 1 = pause, 0 = resume. */
    const val PAUSE_ON = 1
    const val PAUSE_OFF = 0

    // ---- Haptics ----
    /** Watch cue haptic length in ms when the phone relays a cue. */
    const val WATCH_VIBRATE_MS = 300L
    /** VibrationEffect amplitude scale: 0 (off) .. 255 (full). */
    const val HAPTIC_AMPLITUDE_MAX = 255
    /** Lowest amplitude worth emitting; below this the watch uses its default strength. */
    const val HAPTIC_AMPLITUDE_MIN = 1

    // ---- Session defaults (mirror the phone's default profile) ----
    /** Default push-segment length in seconds, shown before the first state relay. */
    const val DEFAULT_FAST_SEC = 180
    /** Default recovery-segment length in seconds, shown before the first state relay. */
    const val DEFAULT_SLOW_SEC = 180
    /** Default push speed floor (mph), mirroring the phone's profile default. */
    const val DEFAULT_SPEED_FLOOR_MPH = 4.5f
    /** Default recovery speed ceiling (mph), mirroring the phone's profile default. */
    const val DEFAULT_SPEED_CEILING_MPH = 3.2f
    /** Default push pace floor (spm), mirroring the phone's profile default. */
    const val DEFAULT_PACE_FLOOR_SPM = 110
    /** Default recovery pace ceiling (spm), mirroring the phone's profile default. */
    const val DEFAULT_PACE_CEILING_SPM = 100

    // ---- Phase palette (mirrors the phone tracker) ----
    val PHASE_WARMUP_COLOR = Color(0xFF58A05C)
    val PHASE_FAST_COLOR = Color(0xFFD1402A)
    val PHASE_SLOW_COLOR = Color(0xFF2E7AC4)
    val PHASE_COOLDOWN_COLOR = Color(0xFF7B8A99)
    /** Neutral tint used while paused or when the phase is unknown. */
    val PHASE_PAUSED_COLOR = Color(0xFF9E9E9E)
    /** "On target" green for the graphics status color. */
    val OK_COLOR = Color(0xFF2E9E4F)

    // ---- Graphics tuning ----
    /** Headroom multiplier for the band/gauge scale relative to the widest target. */
    const val SCALE_HEADROOM = 1.2f
    /** Start angle of the gauge arc, degrees. */
    const val GAUGE_START_ANGLE_DEG = 150f
    /** Angular sweep of the gauge arc, degrees. */
    const val GAUGE_SWEEP_DEG = 240f
}