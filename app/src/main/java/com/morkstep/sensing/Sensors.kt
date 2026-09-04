package com.morkstep.sensing

import com.morkstep.data.PhaseType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** Supplies instantaneous walking speed in mph. Null when unknown. */
interface SpeedSource {
    val speed: StateFlow<Float?>
}

/** Supplies instantaneous heart rate in bpm. Null when unknown. */
interface HeartRateSource {
    val hr: StateFlow<Int?>
}

/**
 * Deterministic-ish simulated sensors driven by the current workout phase.
 *
 * Speed and HR random-walk toward phase-appropriate targets (mph/bpm) so the
 * interval engine has realistic live data to evaluate cues against (and so
 * the audio cue path is exercised on emulators lacking GPS/BLE hardware).
 */
class SimulatedSensors : SpeedSource, HeartRateSource {
    private val _speed = MutableStateFlow<Float?>(0f)
    override val speed: StateFlow<Float?> = _speed.asStateFlow()

    private val _hr = MutableStateFlow<Int?>(95)
    override val hr: StateFlow<Int?> = _hr.asStateFlow()

    private val rng = Random(42)

    /** Target bands a phase approaches (mph and bpm). Tuned to the default profile. */
    private fun targets(phase: PhaseType): Pair<Float, Int> = when (phase) {
        PhaseType.WARMUP -> 2.9f to 105
        PhaseType.FAST -> 4.0f to 138
        PhaseType.SLOW -> 2.2f to 112
        PhaseType.COOLDOWN -> 2.3f to 100
    }

    fun setPhase(phase: PhaseType) {
        val (tSpeed, tHr) = targets(phase)
        // Approach the target with noise; speed stays a wrinkle away so cues have room.
        val current = _speed.value ?: tSpeed
        val next = current + ((tSpeed - current) * 0.25f) + (rng.nextFloat() - 0.5f) * 0.4f
        _speed.value = next.coerceIn(1.6f, 5.4f)
        val cHr = _hr.value ?: tHr
        val nextHr = cHr + ((tHr - cHr) * 0.2f) + rng.nextInt(-2, 3)
        _hr.value = nextHr.coerceIn(90f, 175f).toInt()
    }
}