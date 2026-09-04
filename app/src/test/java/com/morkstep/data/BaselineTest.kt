package com.morkstep.data

import com.morkstep.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineTest {

    @Test
    fun calibrationProfile_hasSpecValues() {
        val p = baselineCalibrationProfile(id = 7)
        assertEquals("Baseline", p.name)
        assertTrue(isBaselineProfile(p))
        assertEquals(WorkoutLength.ROUNDS, p.lengthMode)
        assertEquals(3, p.rounds)
        assertEquals(45, p.fastSec) // push
        assertEquals(45, p.slowSec) // recovery
        assertEquals(20, p.warmupSec)
        assertEquals(0, p.cooldownSec)
        // Total = 20 + 3*(45+45) + 0 = 290 s.
        assertEquals(290L, p.totalSeconds)
        assertEquals("3 rounds", p.lengthLabel())
    }

    @Test
    fun isBaselineProfile_matchesByNameOnly() {
        assertTrue(isBaselineProfile(WorkoutProfile(name = Constants.BASELINE_PROFILE_NAME)))
        assertFalse(isBaselineProfile(WorkoutProfile(name = "Default")))
    }

    @Test
    fun updatedBaselineProfile_appliesCalibratedValues() {
        val cal = baselineCalibrationProfile(id = 7)
        val updated = updatedBaselineProfile(cal, pushSpeedMph = 4.0, recoverySpeedMph = 2.5)
        assertEquals(WorkoutLength.TIME, updated.lengthMode)
        assertEquals(30, updated.timeMinutes)
        assertEquals(120, updated.fastSec)
        assertEquals(120, updated.slowSec)
        assertEquals(30, updated.warmupSec)
        assertEquals(30, updated.cooldownSec)
        // Literal spec: ceiling = recovery avg (keep walking slower during recovery),
        // floor = push avg (keep walking faster during push).
        assertEquals(2.5, updated.speedCeilingMph, 1e-9)
        assertEquals(4.0, updated.speedFloorMph, 1e-9)
        assertEquals(30 * 60L, updated.totalSeconds)
        assertEquals("30 min", updated.lengthLabel())
    }

    @Test
    fun updatedBaselineProfile_keepsIdentityAndId() {
        val cal = baselineCalibrationProfile(id = 7)
        val updated = updatedBaselineProfile(cal, 3.5, 2.5)
        assertEquals(7L, updated.id)
        assertEquals(Constants.BASELINE_PROFILE_NAME, updated.name)
        assertTrue(isBaselineProfile(updated))
    }

    @Test
    fun updatedBaselineProfile_keepsSpeedTargetsWithoutAverages() {
        val cal = baselineCalibrationProfile(id = 7).copy(speedCeilingMph = 5.0, speedFloorMph = 3.0)
        val updated = updatedBaselineProfile(cal, pushSpeedMph = null, recoverySpeedMph = null)
        assertEquals(5.0, updated.speedCeilingMph, 1e-9)
        assertEquals(3.0, updated.speedFloorMph, 1e-9)
        // The rest of the calibrated profile still applies.
        assertEquals(WorkoutLength.TIME, updated.lengthMode)
        assertEquals(30, updated.timeMinutes)
    }

    @Test
    fun updatedBaselineProfile_clampsToSliderRanges() {
        val weird = baselineCalibrationProfile(id = 1)
            .copy(speedCeilingMph = 1.0, speedFloorMph = 9.0)
        // Ceiling (recovery avg) clamps to [2.0, 8.0]; floor (push avg) to [1.5, 7.0].
        val withAvg = updatedBaselineProfile(weird, pushSpeedMph = 12.0, recoverySpeedMph = 0.5)
        assertEquals(2.0, withAvg.speedCeilingMph, 1e-9)
        assertEquals(7.0, withAvg.speedFloorMph, 1e-9)
        // Pre-existing out-of-range defaults also clamp when averages are missing.
        val noAvg = updatedBaselineProfile(weird, null, null)
        assertEquals(2.0, noAvg.speedCeilingMph, 1e-9)
        assertEquals(7.0, noAvg.speedFloorMph, 1e-9)
    }
}