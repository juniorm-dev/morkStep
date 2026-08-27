package com.morkstep.sensing

import com.morkstep.data.PhaseType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** Supplies instantaneous walking pace in km/h. Null when unknown. */
interface PaceSource {
    val pace: StateFlow<Float?>
}

/** Supplies instantaneous heart rate in bpm. Null when unknown. */
interface HeartRateSource {
    val hr: StateFlow<Int?>
}

/**
 * Deterministic-ish simulated sensors driven by the current workout phase.
 *
 * Pace and HR random-walk toward phase-appropriate targets so the interval
 * engine has realistic live data to evaluate cues against (and so the audio
 * cue path is exercised on emulators that lack GPS/BLE hardware).
 */
class SimulatedSensors : PaceSource, HeartRateSource {
    private val _pace = MutableStateFlow<Float?>(0f)
    override val pace: StateFlow<Float?> = _pace.asStateFlow()

    private val _hr = MutableStateFlow<Int?>(95)
    override val hr: StateFlow<Int?> = _hr.asStateFlow()

    private val rng = Random(42)

    /** Target bands a phase approaches (km/h and bpm). Tuned to the default config. */
    private fun targets(phase: PhaseType): Pair<Float, Int> = when (phase) {
        PhaseType.WARMUP -> 4.5f to 105
        PhaseType.FAST -> 6.2f to 138
        PhaseType.SLOW -> 3.0f to 112
        PhaseType.COOLDOWN -> 3.2f to 100
    }

    fun setPhase(phase: PhaseType) {
        val (tPace, tHr) = targets(phase)
        // Approach the target with noise; pace stays a wrinkle away so cues have room.
        val current = _pace.value ?: tPace
        val next = current + ((tPace - current) * 0.25f) + (rng.nextFloat() - 0.5f) * 0.6f
        _pace.value = next.coerceIn(2.5f, 8.5f)
        val cHr = _hr.value ?: tHr
        val nextHr = cHr + ((tHr - cHr) * 0.2f) + rng.nextInt(-2, 3)
        _hr.value = nextHr.coerceIn(90f, 175f).toInt()
    }

    suspend fun startTicking() {
        // No-op: values change on setPhase, driven by the session engine.
        delay(1)
    }
}