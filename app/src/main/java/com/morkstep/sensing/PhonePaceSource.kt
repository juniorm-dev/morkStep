package com.morkstep.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phone-side pedometer cadence (steps per minute) from the built-in step
 * sensors — the fallback when no Wear companion is producing pace.
 *
 * Prefers [Sensor.TYPE_STEP_DETECTOR] (one event per step); falls back to
 * [Sensor.TYPE_STEP_COUNTER] (cumulative count) when the detector is absent.
 * No runtime permission is required: step sensors are not permission-gated
 * (unlike activity recognition, which is only needed for classification).
 *
 * Cadence is derived by [PaceWindowCalculator] over a rolling 10-second
 * window, mirroring the watch's STEPS_PER_MINUTE semantics closely enough for
 * the pace floor/ceiling cues.
 */
class PhonePaceSource(context: Context) : PaceSource {
    private val _pace = MutableStateFlow<Int?>(null)
    override val pace: StateFlow<Int?> = _pace.asStateFlow()

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepDetector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val stepCounter: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val calculator = PaceWindowCalculator()

    private var registered = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = android.os.SystemClock.elapsedRealtime()
            when (event.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> calculator.onStep(now)
                    ?.let { _pace.value = it }
                Sensor.TYPE_STEP_COUNTER -> calculator.onCumulative(now, event.values[0].toLong())
                    ?.let { _pace.value = it }
                else -> Unit
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Begin listening to the phone's step sensor; no-op if neither sensor exists. */
    fun start() {
        if (registered) return
        val sensor = stepDetector ?: stepCounter ?: return
        registered = true
        try {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (_: Exception) {
            registered = false
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            sensorManager.unregisterListener(listener)
        } catch (_: Exception) {
        }
        calculator.reset()
        _pace.value = null
    }
}

/**
 * Rolling steps-per-minute derivation, pure and unit-testable.
 *
 * Feed step timestamps ([onStep], from the step detector) or cumulative-count
 * samples ([onCumulative], from the step counter) together with a monotonic
 * millisecond clock; each call returns the cadence over the trailing window
 * or the previous cadence when there is not yet enough signal.
 */
class PaceWindowCalculator(
    private val windowMs: Long = 10_000L,
) {
    private val stepTimes = ArrayDeque<Long>()
    private var lastCount = 0L
    private var lastCountAt = 0L
    private var lastSpm: Int? = null

    /** One step at [nowMs]; returns cadence over the trailing [windowMs]. */
    fun onStep(nowMs: Long): Int? {
        stepTimes.addLast(nowMs)
        while (stepTimes.size > 1 && nowMs - stepTimes.first() > windowMs) stepTimes.removeFirst()
        val spm = if (stepTimes.size >= 2) {
            val span = nowMs - stepTimes.first()
            if (span > 0) (stepTimes.size * 60_000L / span).toInt().coerceIn(1, 240) else null
        } else null
        spm?.let { lastSpm = it }
        return spm
    }

    /** A cumulative step count sample at [nowMs]; returns smoothed cadence. */
    fun onCumulative(nowMs: Long, count: Long): Int? {
        val dt = nowMs - lastCountAt
        val dCount = count - lastCount
        // First sample establishes the baseline; later samples require real
        // elapsed time and real steps, and rejected samples do not move it.
        if (lastCountAt == 0L) {
            lastCount = count
            lastCountAt = nowMs
            return lastSpm
        }
        if (dt < 500 || dCount <= 0) return lastSpm
        lastCount = count
        lastCountAt = nowMs
        val raw = (dCount * 60_000L / dt).toInt().coerceIn(1, 240)
        lastSpm = if (lastSpm == null) raw else ((lastSpm!! * 0.5) + (raw * 0.5)).toInt()
        return lastSpm
    }

    fun reset() {
        stepTimes.clear()
        lastCount = 0L
        lastCountAt = 0L
        lastSpm = null
    }
}