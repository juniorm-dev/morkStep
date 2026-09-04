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

/** Supplies instantaneous pedometer cadence in steps per minute. Null when unknown. */
interface PaceSource {
    val pace: StateFlow<Int?>
}

/** Supplies instantaneous heart rate in bpm. Null when unknown. */
interface HeartRateSource {
    val hr: StateFlow<Int?>
}

/**
 * Deterministic-ish simulated sensors driven by the current workout phase.
 *
 * Speed, pace and HR random-walk toward phase-appropriate targets so the
 * interval engine has realistic live data to evaluate cues against (and so
 * the audio cue path is exercised on emulators lacking GPS/BLE/Wear hardware).
 */
class SimulatedSensors : SpeedSource, PaceSource, HeartRateSource {
    private val _speed = MutableStateFlow<Float?>(0f)
    override val speed: StateFlow<Float?> = _speed.asStateFlow()

    private val _pace = MutableStateFlow<Int?>(0)
    override val pace: StateFlow<Int?> = _pace.asStateFlow()

    private val _hr = MutableStateFlow<Int?>(95)
    override val hr: StateFlow<Int?> = _hr.asStateFlow()

    private val rng = Random(42)

    /** Target bands a phase approaches (mph, spm and bpm). Tuned to the default profile. */
    private fun targets(phase: PhaseType): Triple<Float, Int, Int> = when (phase) {
        PhaseType.WARMUP -> Triple(2.9f, 100, 105)
        PhaseType.FAST -> Triple(4.0f, 118, 138)
        PhaseType.SLOW -> Triple(2.2f, 92, 112)
        PhaseType.COOLDOWN -> Triple(2.3f, 95, 100)
    }

    fun setPhase(phase: PhaseType) {
        val (tSpeed, tPace, tHr) = targets(phase)
        // Approach the target with noise; speed stays a wrinkle away so cues have room.
        val current = _speed.value ?: tSpeed
        val next = current + ((tSpeed - current) * 0.25f) + (rng.nextFloat() - 0.5f) * 0.4f
        _speed.value = next.coerceIn(1.6f, 5.4f)
        val cPace = _pace.value ?: tPace
        val nextPace = (cPace + ((tPace - cPace) * 0.3f).toInt() + rng.nextInt(-4, 5))
        _pace.value = nextPace.coerceIn(80, 140)
        val cHr = _hr.value ?: tHr
        val nextHr = cHr + ((tHr - cHr) * 0.2f) + rng.nextInt(-2, 3)
        _hr.value = nextHr.coerceIn(90f, 175f).toInt()
    }
}