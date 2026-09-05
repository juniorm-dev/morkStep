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
        assertEquals(45, p.pushSec) // push
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
        val updated = updatedBaselineProfile(
            cal, pushSpeedMph = 4.0, recoverySpeedMph = 2.5,
            pushPaceSpm = 120, recoveryPaceSpm = 95,
            pushHrBpm = 158, recoveryHrBpm = 132,
        )
        assertEquals(WorkoutLength.TIME, updated.lengthMode)
        assertEquals(30, updated.timeMinutes)
        assertEquals(120, updated.pushSec)
        assertEquals(120, updated.slowSec)
        assertEquals(30, updated.warmupSec)
        assertEquals(30, updated.cooldownSec)
        // Literal spec: ceiling = recovery avg (keep walking slower during recovery),
        // floor = push avg (keep walking faster during push).
        assertEquals(2.5, updated.recoverySpeedCapMph, 1e-9)
        assertEquals(4.0, updated.pushSpeedFloorMph, 1e-9)
        assertEquals(95, updated.recoveryPaceCapSpm)
        assertEquals(120, updated.pushPaceFloorSpm)
        // Literal spec: Recovery Max = recovery avg HR, Push Min = push avg HR.
        assertEquals(132, updated.hrRecoveryMax)
        assertEquals(158, updated.hrPushMin)
        assertEquals(30 * 60L, updated.totalSeconds)
        assertEquals("30 min", updated.lengthLabel())
    }

    @Test
    fun updatedBaselineProfile_keepsPaceTargetsWithoutAverages() {
        val cal = baselineCalibrationProfile(id = 7).copy(recoveryPaceCapSpm = 105, pushPaceFloorSpm = 118)
        val updated = updatedBaselineProfile(cal, pushSpeedMph = null, recoverySpeedMph = null)
        assertEquals(105, updated.recoveryPaceCapSpm)
        assertEquals(118, updated.pushPaceFloorSpm)
    }

    @Test
    fun updatedBaselineProfile_clampsPaceToSliderRanges() {
        val weird = baselineCalibrationProfile(id = 1)
            .copy(recoveryPaceCapSpm = 200, pushPaceFloorSpm = 10)
        val withAvg = updatedBaselineProfile(weird, null, null, pushPaceSpm = 200, recoveryPaceSpm = 10)
        // Ceiling (recovery pace 10) clamps to [90,140] → 90; floor (push pace 200) to [80,130] → 130.
        assertEquals(90, withAvg.recoveryPaceCapSpm)
        assertEquals(130, withAvg.pushPaceFloorSpm)
        // Pre-existing out-of-range defaults (200 / 10) also clamp when averages are missing.
        val noAvg = updatedBaselineProfile(weird, null, null)
        assertEquals(140, noAvg.recoveryPaceCapSpm)
        assertEquals(80, noAvg.pushPaceFloorSpm)
    }

    @Test
    fun updatedBaselineProfile_keepsHrTargetsWithoutAverages() {
        val cal = baselineCalibrationProfile(id = 7).copy(hrRecoveryMax = 105, hrPushMin = 118)
        val updated = updatedBaselineProfile(cal, pushSpeedMph = null, recoverySpeedMph = null)
        assertEquals(105, updated.hrRecoveryMax)
        assertEquals(118, updated.hrPushMin)
    }

    @Test
    fun updatedBaselineProfile_clampsHrToSliderRanges() {
        val weird = baselineCalibrationProfile(id = 1)
            .copy(hrRecoveryMax = 200, hrPushMin = 10)
        val withAvg = updatedBaselineProfile(weird, null, null, pushHrBpm = 250, recoveryHrBpm = 40)
        // Cap (recovery HR 40) clamps to [70,190] → 70; floor (push HR 250) to [90,200] → 200.
        assertEquals(70, withAvg.hrRecoveryMax)
        assertEquals(200, withAvg.hrPushMin)
        // Pre-existing out-of-range defaults (200 / 10) also clamp when averages are missing.
        val noAvg = updatedBaselineProfile(weird, null, null)
        assertEquals(190, noAvg.hrRecoveryMax)
        assertEquals(90, noAvg.hrPushMin)
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
        val cal = baselineCalibrationProfile(id = 7).copy(recoverySpeedCapMph = 5.0, pushSpeedFloorMph = 3.0)
        val updated = updatedBaselineProfile(cal, pushSpeedMph = null, recoverySpeedMph = null)
        assertEquals(5.0, updated.recoverySpeedCapMph, 1e-9)
        assertEquals(3.0, updated.pushSpeedFloorMph, 1e-9)
        // The rest of the calibrated profile still applies.
        assertEquals(WorkoutLength.TIME, updated.lengthMode)
        assertEquals(30, updated.timeMinutes)
    }

    @Test
    fun updatedBaselineProfile_clampsToSliderRanges() {
        val weird = baselineCalibrationProfile(id = 1)
            .copy(recoverySpeedCapMph = 1.0, pushSpeedFloorMph = 9.0)
        // Ceiling (recovery avg) clamps to [2.0, 8.0]; floor (push avg) to [1.5, 7.0].
        val withAvg = updatedBaselineProfile(weird, pushSpeedMph = 12.0, recoverySpeedMph = 0.5)
        assertEquals(2.0, withAvg.recoverySpeedCapMph, 1e-9)
        assertEquals(7.0, withAvg.pushSpeedFloorMph, 1e-9)
        // Pre-existing out-of-range defaults also clamp when averages are missing.
        val noAvg = updatedBaselineProfile(weird, null, null)
        assertEquals(2.0, noAvg.recoverySpeedCapMph, 1e-9)
        assertEquals(7.0, noAvg.pushSpeedFloorMph, 1e-9)
    }
}