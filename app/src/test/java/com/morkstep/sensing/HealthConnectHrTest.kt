package com.morkstep.sensing

import com.morkstep.data.WorkoutLength
import com.morkstep.data.WorkoutProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthConnectHrTest {

    private val roundsProfile = WorkoutProfile(
        id = 1,
        name = "Rounds",
        lengthMode = WorkoutLength.ROUNDS,
        rounds = 2,
        warmupSec = 60,
        pushSec = 60,
        slowSec = 60,
        cooldownSec = 60,
    )

    @Test
    fun buckets_mapOnlyPushAndRecoveryPhases() {
        // Warm-up 0-60, FAST 60-120, SLOW 120-180, FAST 180-240, SLOW 240-300,
        // cool-down 300-360. A bucket per phase start.
        val buckets = listOf(
            0L to 90,   // warm-up
            60L to 140, // push 1
            120L to 100, // recovery 1
            180L to 150, // push 2
            240L to 110, // recovery 2
            300L to 80,  // cool-down
        )
        val (push, recovery) = phaseAveragesFromBuckets(buckets, roundsProfile, totalSeconds = 360)
        assertEquals(145, push!!)
        assertEquals(105, recovery!!)
    }

    @Test
    fun buckets_beyondFinishAreIgnored() {
        val buckets = listOf(
            60L to 140,
            120L to 100,
            500L to 999, // past the 360 s finish
        )
        val (push, recovery) = phaseAveragesFromBuckets(buckets, roundsProfile, totalSeconds = 360)
        assertEquals(140, push!!)
        assertEquals(100, recovery!!)
    }

    @Test
    fun buckets_missingPhase_yieldsNullForThatPhase() {
        val buckets = listOf(
            60L to 140, // push only
        )
        val (push, recovery) = phaseAveragesFromBuckets(buckets, roundsProfile, totalSeconds = 360)
        assertEquals(140, push!!)
        assertNull(recovery)
    }

    @Test
    fun buckets_empty_yieldsNulls() {
        val (push, recovery) = phaseAveragesFromBuckets(emptyList(), roundsProfile, totalSeconds = 360)
        assertNull(push)
        assertNull(recovery)
    }
}