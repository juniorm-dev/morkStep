package com.morkstep.ui

import com.morkstep.data.WorkoutEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The collapsed History card's HR line. A backfilled row can hold only part of
 * the heart rate Health Connect was asked for — the whole-window aggregate and
 * the per-minute buckets are separate reads — and a workout that recorded HR at
 * all has to show it on the card rather than only in the expanded detail.
 */
class HistoryCardHrTest {

    @Test
    fun showsTheOverallAverageWhenTheAggregateLanded() {
        assertEquals(
            "HR bpm:  overall 128",
            hrLine(workout(avgOverallHr = 128, avgPushHr = 140, avgRecoveryHr = 118, minHr = 96, maxHr = 155)),
        )
    }

    @Test
    fun fallsBackToThePooledPairWhenOnlyTheBucketsLanded() {
        assertEquals(
            "HR bpm:  push 140 · rec 118",
            hrLine(workout(avgPushHr = 140, avgRecoveryHr = 118)),
        )
    }

    @Test
    fun namesASinglePooledValueRatherThanDroppingThePair() {
        assertEquals("HR bpm:  push 140 · rec –", hrLine(workout(avgPushHr = 140)))
    }

    @Test
    fun fallsBackToTheMinMaxPairWhenThatIsAllHealthConnectHeld() {
        assertEquals("HR bpm:  min–max 96–155", hrLine(workout(minHr = 96, maxHr = 155)))
    }

    @Test
    fun staysSilentForAWorkoutThatRecordedNoHeartRate() {
        assertNull(hrLine(workout()))
    }

    /** A phone-only session summary: no HR unless a test fills a field in. */
    private fun workout(
        avgOverallHr: Int? = null,
        avgPushHr: Int? = null,
        avgRecoveryHr: Int? = null,
        minHr: Int? = null,
        maxHr: Int? = null,
    ) = WorkoutEntity(
        id = 7,
        startTime = 1_700_000_000_000,
        endTime = 1_700_000_600_000,
        durationSec = 600,
        pushSegments = 2,
        overPushMinSec = 30,
        distanceMiles = 0.5f,
        avgPushSpeed = 3.9f,
        avgRecoverySpeed = 2.3f,
        avgOverallSpeed = 3.1f,
        avgPushHr = avgPushHr,
        avgRecoveryHr = avgRecoveryHr,
        avgOverallHr = avgOverallHr,
        minHr = minHr,
        maxHr = maxHr,
    )
}
