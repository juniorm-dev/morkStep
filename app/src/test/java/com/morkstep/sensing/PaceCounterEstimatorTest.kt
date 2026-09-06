package com.morkstep.sensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Smoothed cadence math for the step-counter path of [PhonePaceSource]. */
class PaceCounterEstimatorTest {

    @Test
    fun firstSampleBaselinesAndEmitsNothing() {
        val est = PaceCounterEstimator()
        assertNull(est.sample(1_000L, 100L))
    }

    @Test
    fun secondSampleGivesRawIntervalRate() {
        val est = PaceCounterEstimator()
        est.sample(1_000L, 100L)
        // +30 steps over 30 s -> 60 spm.
        assertEquals(60, est.sample(31_000L, 130L))
    }

    @Test
    fun ignoresTinyIntervalsWithoutMovingBaseline() {
        val est = PaceCounterEstimator()
        est.sample(1_000L, 100L)
        // 5 steps reported 100 ms later: too fast to trust, keep previous.
        assertNull(est.sample(1_100L, 105L))
        // The rejected sample did not advance the baseline; the next real sample
        // measures 30 steps from t=1 s to t=31.1 s -> 30 / 30.1 s = ~59 spm.
        assertEquals(59, est.sample(31_100L, 130L))
    }

    @Test
    fun smoothsHalfLifeTowardsRaw() {
        val est = PaceCounterEstimator()
        est.sample(1_000L, 100L)
        // 60 spm raw (see secondSampleGivesRawIntervalRate).
        assertEquals(60, est.sample(31_000L, 130L))
        // 1 step in the next second -> raw 60, smoothed stays 60.
        assertEquals(60, est.sample(32_000L, 131L))
        // 5 steps in the next 1.5 s -> raw 200, smoothed = 0.5*60 + 0.5*200 = 130.
        assertEquals(130, est.sample(33_500L, 136L))
    }

    @Test
    fun rejectsNonAdvancingCount() {
        val est = PaceCounterEstimator()
        est.sample(1_000L, 100L)
        assertEquals(60, est.sample(31_000L, 130L))
        // Same cumulative count a second later carries no new rate signal.
        assertEquals(60, est.sample(32_000L, 130L))
    }

    @Test
    fun detectorActivityNeverContaminatesCounter() {
        // Regression: the two sensors previously shared one smoothed state, so a
        // detector burst (e.g. 240 spm) bled into the counter's next sample — a
        // real slow walk read 147 spm when the true cadence was ~54. With split
        // estimators the counter must be immune to detector writes.
        val detector = PaceWindowCalculator(windowMs = 10_000L)
        // Counter receives its FRESH baseline and first interval, entirely separately.
        val est = PaceCounterEstimator()
        est.sample(1_000L, 100L)
        assertEquals(60, est.sample(31_000L, 130L))
        // Detector goes berserk in between (old estimator read 240 here).
        detector.onStep(31_500L)
        detector.onStep(32_000L)
        // Counter's next interval is still 60 spm — no 240 residue, no 150 blend.
        assertEquals(60, est.sample(32_000L, 131L))
        // And the detector's own windowed estimate (3 steps over a 1.5 s span)
        // stays uncorrupted by the counter: 2 intervals / 1.5 s = 80, its own math.
        assertEquals(80, detector.onStep(33_000L))
    }

    @Test
    fun resetClearsBaseline() {
        val est = PaceCounterEstimator()
        est.sample(1_000L, 100L)
        est.sample(31_000L, 130L)
        est.reset()
        assertNull(est.sample(32_000L, 130L))
    }
}