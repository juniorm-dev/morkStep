package com.morkstep.sensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure cadence-derivation math for [PhonePaceSource]. */
class PaceWindowCalculatorTest {

    @Test
    fun onStep_requiresTwoStepsAndSpanFloorBeforeEmit() {
        val calc = PaceWindowCalculator(windowMs = 10_000L)
        assertNull(calc.onStep(0L))
        // One step later is a real cadence signal only once the span clears
        // the floor: 2 steps over 1 s is a sub-floor gap, not 120 spm.
        assertNull(calc.onStep(1_000L))
        // 3 steps across 2.5 s -> 2 intervals over 2.5 s = 48 spm.
        assertEquals(48, calc.onStep(2_500L))
    }

    @Test
    fun onStep_slowPaceConvergesToTrueCadence() {
        val calc = PaceWindowCalculator(windowMs = 10_000L)
        // 1 step / 1500 ms walker: the fixed math reads 40 spm from the
        // second step on, never the old 53-80 overestimates.
        assertNull(calc.onStep(1_500L))
        assertEquals(40, calc.onStep(3_000L))
        assertEquals(40, calc.onStep(4_500L))
        assertEquals(40, calc.onStep(6_000L))
        assertEquals(40, calc.onStep(7_500L))
        assertEquals(40, calc.onStep(9_000L))
    }

    @Test
    fun onStep_shortDoubleStepGapHeld_notSprint() {
        // Regression for the log spike: a double-step 500 ms apart at slow
        // pace used to read 240 spm (the clamp ceiling). The span floor must
        // hold it, and the cadence stays sane around it.
        val calc = PaceWindowCalculator(windowMs = 10_000L)
        assertNull(calc.onStep(0L))
        assertNull(calc.onStep(500L)) // old estimator -> 240
        assertEquals(60, calc.onStep(2_000L))
        assertEquals(51, calc.onStep(3_500L))
        assertEquals(48, calc.onStep(5_000L))
        val last = calc.onStep(6_500L)
        assertTrue(last != null && last < 200)
    }

    @Test
    fun onStep_rollingWindowGivesCadence() {
        val calc = PaceWindowCalculator(windowMs = 10_000L)
        // 11 steps, one per second, starting at t=0 (last at t=10 s).
        var now = 0L
        repeat(11) { i ->
            now = i * 1_000L
            calc.onStep(now)
        }
        // At t=11 s the t=0 step has fallen out: 10 intervals over a 10 s span
        // = 60 spm (the old estimator counted 11 steps over 10 s -> 66).
        assertEquals(60, calc.onStep(11_000L))
    }

    @Test
    fun onStep_burstHeldThenClamped() {
        val calc = PaceWindowCalculator(windowMs = 10_000L)
        // 10 steps within 100 ms: each span is still sub-floor, so nothing emits.
        var now = 1_000L
        repeat(10) {
            now += 10L
            assertNull(calc.onStep(now))
        }
        // The genuinely absurd cadence a later window would infer is capped.
        assertEquals(200, calc.onStep(3_100L))
    }

    @Test
    fun resetClearsState() {
        val calc = PaceWindowCalculator()
        calc.onStep(1_000L)
        calc.onStep(2_500L)
        calc.reset()
        assertNull(calc.onStep(3_500L))
    }
}