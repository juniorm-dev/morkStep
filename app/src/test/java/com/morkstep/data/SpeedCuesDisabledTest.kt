package com.morkstep.data

import com.morkstep.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Speed (mph) must never trigger warning cues. The engine still evaluates the
 * speed targets, so the guarantee lives at the data boundary: every loaded
 * profile is re-pinned to the disabled band (30 mph cap / 0 mph floor) by
 * [WorkoutProfile.withSpeedCuesDisabled], which [ConfigStore] applies on every
 * read. These tests pin that normalization.
 */
class SpeedCuesDisabledTest {

    @Test
    fun legacyProfileWithRealSpeedTargets_isPinnedToDisabledBand() {
        // A profile saved before the speed sliders were hidden carries real,
        // fireable speed targets — the exact case that must never survive a load.
        val legacy = WorkoutProfile(
            name = "Old",
            recoverySpeedCapMph = 4.5,
            pushSpeedFloorMph = 3.2,
        )
        val loaded = legacy.withSpeedCuesDisabled()
        assertEquals(Constants.DISABLED_RECOVERY_SPEED_CAP_MPH, loaded.recoverySpeedCapMph, 1e-9)
        assertEquals(Constants.DISABLED_PUSH_SPEED_FLOOR_MPH, loaded.pushSpeedFloorMph, 1e-9)
        // The disabled band is unreachable: a walking pace never exceeds the
        // 30 mph cap and speed is never below the 0 mph floor.
        assertNotEquals(4.5, loaded.recoverySpeedCapMph, 1e-9)
        assertNotEquals(3.2, loaded.pushSpeedFloorMph, 1e-9)
    }

    @Test
    fun alreadyDisabledProfile_staysDisabled() {
        val p = defaultProfile().withSpeedCuesDisabled()
        assertEquals(Constants.DISABLED_RECOVERY_SPEED_CAP_MPH, p.recoverySpeedCapMph, 1e-9)
        assertEquals(Constants.DISABLED_PUSH_SPEED_FLOOR_MPH, p.pushSpeedFloorMph, 1e-9)
    }
}