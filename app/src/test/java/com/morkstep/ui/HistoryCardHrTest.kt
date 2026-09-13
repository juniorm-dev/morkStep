package com.morkstep.ui

import com.morkstep.data.WorkoutEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The collapsed History card's averages. The card carries the pooled push and
 * recovery values beside the overall — the level a session is actually run
 * against — and a row that recorded heart rate at all has to show it without
 * being opened. A backfilled row can hold only part of what Health Connect was
 * asked for (the whole-window aggregate and the per-minute buckets are separate
 * reads, and a provider that rejects the statistical pair is re-read for the
 * average alone), so the HR line falls back through what the row does hold.
 */
class HistoryCardHrTest {

    @Test
    fun showsThePooledPushAndRecoveryAveragesBesideTheOverall() {
        assertEquals(
            "HR bpm:  push 140 · rec 118 · overall 128",
            hrLine(workout(avgOverallHr = 128, avgPushHr = 140, avgRecoveryHr = 118, minHr = 96, maxHr = 155)),
        )
    }

    @Test
    fun cardReadsAPooledPushAndRecoveryValuePerMetric() {
        assertEquals(
            listOf(
                "speed mph:  push 3.9 · rec 2.3 · overall 3.1",
                "pace spm:  push 117 · rec 96 · overall 105",
                "HR bpm:  push 140 · rec 118 · overall 128",
            ),
            overallAverages(workout(avgPushHr = 140, avgRecoveryHr = 118, avgOverallHr = 128)),
        )
    }

    @Test
    fun cardDrawsNoLineForAMetricTheWorkoutNeverRecorded() {
        // A simulated or short session can miss a whole metric; the card drops
        // that line rather than printing it empty.
        val hrOnly = workout(minHr = 96, maxHr = 155).copy(
            avgPushSpeed = null,
            avgRecoverySpeed = null,
            avgOverallSpeed = null,
            avgPushPace = null,
            avgRecoveryPace = null,
            avgOverallPace = null,
        )
        assertEquals(listOf("HR bpm:  min–max 96–155"), overallAverages(hrOnly))
    }

    @Test
    fun fallsBackToThePooledPairWhenOnlyTheBucketsLanded() {
        assertEquals(
            "HR bpm:  push 140 · rec 118",
            hrLine(workout(avgPushHr = 140, avgRecoveryHr = 118)),
        )
    }

    @Test
    fun namesASinglePooledValueRatherThanDroppingTheLine() {
        assertEquals("HR bpm:  push 140", hrLine(workout(avgPushHr = 140)))
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
        avgPushPace = 117,
        avgRecoveryPace = 96,
        avgOverallPace = 105,
        avgPushHr = avgPushHr,
        avgRecoveryHr = avgRecoveryHr,
        avgOverallHr = avgOverallHr,
        minHr = minHr,
        maxHr = maxHr,
    )
}
