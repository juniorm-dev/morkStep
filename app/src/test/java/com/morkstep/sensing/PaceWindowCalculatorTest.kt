package com.morkstep.sensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure cadence-derivation math for [PhonePaceSource]. */
class PaceWindowCalculatorTest {

    @Test
    fun onStep_requiresTwoStepsBeforeEmit() {
        val calc = PaceWindowCalculator(windowMs = 10_000L)
        assertNull(calc.onStep(1_000L))
        // Two steps, 1 s apart -> 2 steps over 1 s = 120 spm.
        assertEquals(120, calc.onStep(2_000L))
    }

    @Test
    fun onStep_rollingWindowGivesCadence() {
        val calc = PaceWindowCalculator(windowMs = 10_000L)
        // 11 steps, one per second, starting at t=0. Window holds the last 10 s
        // of steps -> ~10 steps over a 10 s span = 60 spm (span 9000 ms + 1 s step).
        var now = 0L
        repeat(11) { i ->
            now = i * 1_000L
            calc.onStep(now)
        }
        // After the 11th step (t=10_000) the oldest (t=0) has fallen out:
        // 10 steps over t=1_000..10_000 (span 9_000 ms) -> 66 spm.
        assertEquals(66, calc.onStep(11_000L))
    }

    @Test
    fun onStep_clampsToSensibleCadence() {
        val calc = PaceWindowCalculator(windowMs = 10_000L)
        // 10 steps within 100 ms -> absurd; must clamp to 240 spm.
        var now = 1_000L
        repeat(10) {
            now += 10L
            calc.onStep(now)
        }
        assertEquals(240, calc.onStep(now))
    }

    @Test
    fun onCumulative_firstSampleBaselines() {
        val calc = PaceWindowCalculator()
        assertNull(calc.onCumulative(1_000L, 100L))
        // +30 steps over 30 s -> 60 spm.
        assertEquals(60, calc.onCumulative(31_000L, 130L))
    }

    @Test
    fun onCumulative_ignoresTinyIntervals() {
        val calc = PaceWindowCalculator()
        calc.onCumulative(1_000L, 100L)
        // 5 steps reported 100 ms later: too fast to trust, keep previous.
        assertEquals(null, calc.onCumulative(1_100L, 105L))
        // The rejected sample did not advance the baseline; the next real sample
        // measures 30 steps from t=1 s to t=31.1 s -> 30 / 30.1 s = ~59 spm.
        assertEquals(59, calc.onCumulative(31_100L, 130L))
    }

    @Test
    fun resetClearsState() {
        val calc = PaceWindowCalculator()
        calc.onStep(1_000L)
        calc.onStep(2_000L)
        calc.reset()
        assertNull(calc.onStep(3_000L))
    }
}