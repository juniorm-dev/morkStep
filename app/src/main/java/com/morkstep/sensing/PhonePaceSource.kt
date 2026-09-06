package com.morkstep.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.hardware.SensorEventListener
import android.util.Log
import com.morkstep.Constants
import com.morkstep.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phone-side pedometer cadence (steps per minute) from the built-in step
 * sensors — the fallback when no Wear companion is producing pace.
 *
 * Registers **both** [Sensor.TYPE_STEP_DETECTOR] (one event per step) and
 * [Sensor.TYPE_STEP_COUNTER] (cumulative count) when both exist; whichever
 * fires feeds the cadence estimator. Some devices report a detector sensor
 * that never emits (e.g. quiescent while the screen is off), while the
 * cumulative counter keeps delivering — registering both means pace flows as
 * long as either sensor is alive. When only one exists, that one is used.
 * No runtime permission is required: step sensors are not permission-gated
 * (unlike activity recognition, which is only needed for classification).
 *
 * Cadence is derived by [PaceWindowCalculator] over a rolling
 * [Constants.PACE_WINDOW_MS] window (default 5 s), mirroring the watch's
 * STEPS_PER_MINUTE semantics closely enough for the pace floor/ceiling cues.
 * See the constant's doc for the responsiveness-vs-stability tradeoff.
 */
class PhonePaceSource(
    context: Context,
    /** App-wide debug log; null disables logging. */
    private val log: DebugLog? = null,
) : PaceSource {
    private val _pace = MutableStateFlow<Int?>(null)
    override val pace: StateFlow<Int?> = _pace.asStateFlow()

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensors: List<Sensor> = buildList {
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let(::add)
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let(::add)
    }
    private val calculator = PaceWindowCalculator(windowMs = Constants.PACE_WINDOW_MS)

    private var registered = false
    /** First counter event establishes the cumulative baseline; flag once per registration. */
    private var counterSeen = false
    /** Last cadence logged to the trace, so unchanged samples don't spam the screen. */
    private var lastTraceSpm = -1

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = android.os.SystemClock.elapsedRealtime()
            when (event.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> {
                    val spm = calculator.onStep(now)
                    if (spm != null) {
                        _pace.value = spm
                        if (spm != lastTraceSpm) {
                            lastTraceSpm = spm
                            log?.log("[pace-phone] step detector -> $spm spm")
                        }
                    }
                }
                Sensor.TYPE_STEP_COUNTER -> {
                    val count = event.values[0].toLong()
                    if (!counterSeen) {
                        counterSeen = true
                        log?.log("[pace-phone] step counter baseline: $count steps")
                    }
                    val spm = calculator.onCumulative(now, count)
                    if (spm != null) {
                        _pace.value = spm
                        if (spm != lastTraceSpm) {
                            lastTraceSpm = spm
                            log?.log("[pace-phone] step counter -> $spm spm")
                        }
                    }
                }
                else -> Unit
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // SENSOR_STATUS_UNRELIABLE/ACCURACY_LOW right after registration
            // is a strong sign the HAL is not actually delivering this sensor.
            if (accuracy < 2) {
                log?.log("[pace-phone] accuracy ${sensor?.type ?: -1} degraded ($accuracy)")
            }
        }
    }

    /** Begin listening to the phone's step sensors; no-op if neither exists. */
    fun start() {
        // Always fully unregister first so a rebuilt engine gets a fresh
        // registration even if a previous listener was left registered.
        unregisterAll()
        if (sensors.isEmpty()) {
            Log.d("PhonePaceSource", "start: no step sensor available")
            log?.log("[pace-phone] no step sensor on this phone")
            return
        }
        Log.d("PhonePaceSource", "start: sensors=${sensors.map { it.type }}")
        log?.log("[pace-phone] phone pedometer: detector=${sensors.any { it.type == Sensor.TYPE_STEP_DETECTOR }}, counter=${sensors.any { it.type == Sensor.TYPE_STEP_COUNTER }}")
        registered = true
        counterSeen = false
        try {
            sensors.forEach { sensor ->
                val ok = sensorManager.registerListener(
                    listener, sensor, SensorManager.SENSOR_DELAY_UI
                )
                log?.log("[pace-phone] register ${sensor.type} -> ${if (ok) "ok" else "FAILED"}")
                if (!ok) registered = false
            }
        } catch (_: Exception) {
            unregisterAll()
            Log.d("PhonePaceSource", "start: registration failed")
            log?.log("[pace-phone] phone pedometer registration failed")
        }
    }

    private fun unregisterAll() {
        try {
            sensors.forEach { sensor ->
                sensorManager.unregisterListener(listener, sensor)
            }
        } catch (_: Exception) {
        }
        registered = false
    }

    fun stop() {
        unregisterAll()
        calculator.reset()
        _pace.value = null
        log?.log("[pace-phone] phone pedometer stopped")
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