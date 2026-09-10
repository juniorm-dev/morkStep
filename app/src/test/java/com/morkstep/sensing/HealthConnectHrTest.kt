package com.morkstep.sensing

import com.morkstep.data.PhaseAverages
import com.morkstep.data.PhaseType
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

    @Test
    fun occurrenceBuckets_splitOneEntryPerPhaseOccurrence() {
        // Warm-up 0-60, FAST 60-120, SLOW 120-180, FAST 180-240, SLOW 240-300,
        // cool-down 300-360. Two buckets land in the first push, one elsewhere.
        val buckets = listOf(
            0L to 90,    // warm-up
            60L to 140,  // push 1
            90L to 160,  // push 1
            120L to 100, // recovery 1
            180L to 150, // push 2
            240L to 110, // recovery 2
            300L to 80,  // cool-down
        )
        val phases = phaseAveragesPerOccurrence(buckets, roundsProfile, totalSeconds = 360)

        assertEquals(
            listOf(
                PhaseType.WARMUP, PhaseType.FAST, PhaseType.SLOW,
                PhaseType.FAST, PhaseType.SLOW, PhaseType.COOLDOWN,
            ),
            phases.map { it.phase },
        )
        assertEquals(90, phases[0].avgHrBpm!!)
        assertEquals(150, phases[1].avgHrBpm!!) // (140 + 160) / 2
        assertEquals(100, phases[2].avgHrBpm!!)
        assertEquals(150, phases[3].avgHrBpm!!)
        assertEquals(110, phases[4].avgHrBpm!!)
        assertEquals(80, phases[5].avgHrBpm!!)
        // HR is all Health Connect supplies here; no live speed/pace to report.
        assertNull(phases[0].avgSpeedMph)
        assertNull(phases[0].avgPaceSpm)
    }

    @Test
    fun occurrenceBuckets_ignoreBucketsPastTheFinish() {
        val phases = phaseAveragesPerOccurrence(
            listOf(60L to 140, 500L to 999),
            roundsProfile,
            totalSeconds = 360,
        )
        assertEquals(1, phases.size)
        assertEquals(PhaseType.FAST, phases[0].phase)
        assertEquals(140, phases[0].avgHrBpm!!)
    }

    @Test
    fun occurrenceBuckets_skipPhasesNoBucketLandedIn() {
        // Only push buckets: the short warm-up/cooldown have no per-minute
        // bucket, so they are absent rather than backfilled with a guess.
        val phases = phaseAveragesPerOccurrence(
            listOf(60L to 140, 180L to 150),
            roundsProfile,
            totalSeconds = 360,
        )
        assertEquals(listOf(PhaseType.FAST, PhaseType.FAST), phases.map { it.phase })
        assertEquals(listOf(140, 150), phases.map { it.avgHrBpm })
    }

    @Test
    fun merge_fillsMissingPhaseHrOnly() {
        val recorded = listOf(
            PhaseAverages(PhaseType.WARMUP, avgSpeedMph = 2.4f, avgPaceSpm = 85),
            PhaseAverages(PhaseType.FAST, avgSpeedMph = 3.9f, avgPaceSpm = 117),
            PhaseAverages(PhaseType.SLOW, avgSpeedMph = 2.3f, avgPaceSpm = 96, avgHrBpm = 133),
        )
        val backfilled = listOf(
            PhaseAverages(PhaseType.WARMUP, avgHrBpm = 99),
            PhaseAverages(PhaseType.FAST, avgHrBpm = 132),
            PhaseAverages(PhaseType.SLOW, avgHrBpm = 111),
        )

        val merged = mergeBackfilledPhaseAverages(recorded, backfilled)

        assertEquals(listOf(99, 132, 133), merged.map { it.avgHrBpm })
        // Live speed/pace survive, and the real-time SLOW HR is not overwritten.
        assertEquals(2.3f, merged[2].avgSpeedMph!!, 0.001f)
        assertEquals(96, merged[2].avgPaceSpm!!)
    }

    @Test
    fun merge_alignsByPhaseOccurrenceNotListPosition() {
        // The session recorded no warm-up phase; the backfill has none for the
        // second recovery. Matching is per phase type + occurrence, so push 1
        // still takes push 1's backfill and the missing phases are the ones
        // actually missing.
        val recorded = listOf(
            PhaseAverages(PhaseType.FAST, avgSpeedMph = 3.9f),
            PhaseAverages(PhaseType.SLOW, avgSpeedMph = 2.3f),
            PhaseAverages(PhaseType.FAST, avgSpeedMph = 3.8f),
        )
        val backfilled = listOf(
            PhaseAverages(PhaseType.WARMUP, avgHrBpm = 99),
            PhaseAverages(PhaseType.FAST, avgHrBpm = 132),
            PhaseAverages(PhaseType.SLOW, avgHrBpm = 111),
        )

        val merged = mergeBackfilledPhaseAverages(recorded, backfilled)

        assertEquals(
            listOf(PhaseType.WARMUP, PhaseType.FAST, PhaseType.SLOW, PhaseType.FAST),
            merged.map { it.phase },
        )
        assertEquals(listOf(99, 132, 111, null), merged.map { it.avgHrBpm })
        assertEquals(3.8f, merged[3].avgSpeedMph!!, 0.001f)
    }

    @Test
    fun merge_withoutRecordedPhases_usesTheBackfill() {
        val backfilled = listOf(
            PhaseAverages(PhaseType.WARMUP, avgHrBpm = 99),
            PhaseAverages(PhaseType.FAST, avgHrBpm = 132),
        )
        assertEquals(backfilled, mergeBackfilledPhaseAverages(emptyList(), backfilled))
        assertEquals(backfilled, mergeBackfilledPhaseAverages(backfilled, emptyList()))
    }
}