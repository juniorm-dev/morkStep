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
 * While both are alive the **counter is authoritative**: its interval-based,
 * smoothed cadence reacts within one step and is immune to the detector's
 * window-edge lag (the detector's windowed rate lags a full
 * [Constants.PACE_WINDOW_MS] behind rate changes), so detector output is
 * ignored until the counter has been silent for
 * [Constants.PACE_COUNTER_PREFERRED_MS].
 *
 * The two sensors must never share estimator state: the counter's smoothing
 * reads the previous cadence, so letting the detector write into the same
 * state contaminates the counter with the detector's (different-window) value
 * — the log signature was a counter reading like 147 spm while the true slow
 * cadence was ~54, exactly 0.5 * detector residue + 0.5 * raw. Each path has
 * its own estimator ([PaceWindowCalculator] for the detector,
 * [PaceCounterEstimator] for the counter); the detector still feeds its
 * window while gated so the counter-less fallback stays warm.
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
    private val counterEstimator = PaceCounterEstimator()

    private var registered = false
    /** First counter event establishes the cumulative baseline; flag once per registration. */
    private var counterSeen = false
    /** Last step-counter event time; detector writes are gated while this is fresh. */
    private var lastCounterAtMs = 0L
    /** Last cadence logged per sensor, so unchanged samples don't spam the screen. */
    private var lastTraceDetectorSpm = -1
    private var lastTraceCounterSpm = -1
    /** Liveness heartbeat bookkeeping: steady counter samples still log every [Constants.PACE_STEADY_LOG_MS]. */
    private var lastCounterLogAtMs = 0L
    private var lastLoggedCount = -1L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = android.os.SystemClock.elapsedRealtime()
            when (event.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> {
                    // Always feed the estimator so its window stays warm for the
                    // counter-less fallback; the write itself is gated while the
                    // step counter is alive (its cadence is the authoritative one).
                    val spm = calculator.onStep(now)
                    val counterFresh = counterSeen && now - lastCounterAtMs <= Constants.PACE_COUNTER_PREFERRED_MS
                    if (spm != null && !counterFresh) {
                        _pace.value = spm
                        if (spm != lastTraceDetectorSpm) {
                            lastTraceDetectorSpm = spm
                            log?.log("[pace-phone] step detector -> $spm spm")
                        }
                    }
                }
                Sensor.TYPE_STEP_COUNTER -> {
                    lastCounterAtMs = now
                    val count = event.values[0].toLong()
                    if (!counterSeen) {
                        counterSeen = true
                        log?.log("[pace-phone] step counter baseline: $count steps")
                    }
                    val spm = counterEstimator.sample(now, count)
                    if (spm != null) {
                        _pace.value = spm
                        // Changed cadence logs immediately; a steady cadence
                        // logs a bounded heartbeat with the live count so a
                        // healthy run is visibly alive, not frozen.
                        if (spm != lastTraceCounterSpm || now - lastCounterLogAtMs >= Constants.PACE_STEADY_LOG_MS) {
                            val delta = if (lastLoggedCount >= 0) count - lastLoggedCount else null
                            lastTraceCounterSpm = spm
                            lastCounterLogAtMs = now
                            lastLoggedCount = count
                            if (delta != null && delta > 0) {
                                log?.log("[pace-phone] step counter -> $spm spm (+$delta steps, total $count)")
                            } else {
                                log?.log("[pace-phone] step counter -> $spm spm (steady, total $count)")
                            }
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
        lastCounterAtMs = 0L
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
        counterEstimator.reset()
        lastCounterLogAtMs = 0L
        lastLoggedCount = -1L
        _pace.value = null
        log?.log("[pace-phone] phone pedometer stopped")
    }
}

/**
 * Step-detector cadence estimator, pure and unit-testable.
 *
 * Feed step timestamps via [onStep] together with a monotonic millisecond
 * clock; each call returns the cadence over the trailing [windowMs] window
 * or the previous cadence when there is not yet enough signal.
 *
 * [onStep] divides the step count by the span from the window's oldest step,
 * excluding that oldest step from the numerator (it marks the start of the
 * span, it is not a step taken within it). Counting it overestimates cadence
 * by one step per window — worst at slow pace, where the window holds few
 * steps — so the math, the two-step minimum and the [minSpanMs] floor are the
 * accuracy contract. A short double-step gap (weight shift, shuffle) holds the
 * previous cadence instead of reading as a sprint; a 200 spm ceiling keeps a
 * genuine burst of events from presenting as an impossible cadence.
 *
 * This estimator owns NO counter state: the step counter smooths its own
 * samples in [PaceCounterEstimator] so the two sensors can never contaminate
 * each other.
 */
class PaceWindowCalculator(
    private val windowMs: Long = 10_000L,
    private val minSpanMs: Long = Constants.PACE_ESTIMATOR_MIN_SPAN_MS,
) {
    private val stepTimes = ArrayDeque<Long>()

    /** One step at [nowMs]; returns cadence over the trailing [windowMs]. */
    fun onStep(nowMs: Long): Int? {
        stepTimes.addLast(nowMs)
        while (stepTimes.size > 1 && nowMs - stepTimes.first() > windowMs) stepTimes.removeFirst()
        return if (stepTimes.size >= 2) {
            val span = nowMs - stepTimes.first()
            if (span >= minSpanMs) {
                ((stepTimes.size - 1) * 60_000L / span).toInt().coerceAtMost(200)
            } else null
        } else null
    }

    fun reset() {
        stepTimes.clear()
    }
}

/**
 * Step-counter cadence estimator, pure and unit-testable.
 *
 * Feed cumulative step-count samples via [sample] together with a monotonic
 * millisecond clock; each call returns the smoothed cadence over the latest
 * valid interval or the previous cadence while there is not enough signal.
 *
 * The first sample establishes the count baseline and emits nothing. Later
 * samples require at least 500 ms of real elapsed time with a positive step
 * delta (batched duplicates and sub-500 ms spam are rejected without moving
 * the baseline); the raw interval rate is half-life smoothed against the
 * previous estimate (50/50) so single-step jitter at slow pace is damped but
 * a rate change still converges within a few steps.
 *
 * This estimator owns NO step-detector state: [PaceWindowCalculator] handles
 * the detector's timestamps separately so the two sensors can never
 * contaminate each other's smoothing.
 */
class PaceCounterEstimator {
    private var lastCount = 0L
    private var lastCountAt = 0L
    private var lastSpm: Int? = null

    /** A cumulative step count sample at [nowMs]; returns smoothed cadence. */
    fun sample(nowMs: Long, count: Long): Int? {
        val dt = nowMs - lastCountAt
        val dCount = count - lastCount
        // First sample establishes the baseline; later samples require real
        // elapsed time and real steps, and rejected samples do not move it.
        if (lastCountAt == 0L) {
            lastCount = count
            lastCountAt = nowMs
            return null
        }
        if (dt < 500 || dCount <= 0) return lastSpm
        lastCount = count
        lastCountAt = nowMs
        val raw = (dCount * 60_000L / dt).toInt().coerceIn(1, 240)
        lastSpm = if (lastSpm == null) raw else ((lastSpm!! * 0.5) + (raw * 0.5)).toInt()
        return lastSpm
    }

    fun reset() {
        lastCount = 0L
        lastCountAt = 0L
        lastSpm = null
    }
}