package com.morkstep.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the profile/history backup layer (pure serialization + merge logic). */
class TransferTest {

    private val json = TransferJson.json

    @Test
    fun mergeProfiles_preservesFreshIds() {
        val existing = listOf(
            WorkoutProfile(id = 1, name = "A"),
            WorkoutProfile(id = 2, name = "B"),
        )
        val imported = listOf(
            WorkoutProfile(id = 10, name = "X"),
            WorkoutProfile(id = 11, name = "Y"),
        )
        val merged = mergeProfiles(existing, imported)
        assertEquals(listOf(10L, 11L), merged.map { it.id })
        assertEquals(listOf("X", "Y"), merged.map { it.name })
    }

    @Test
    fun mergeProfiles_reassignsCollidingIds() {
        val existing = listOf(WorkoutProfile(id = 1, name = "A"))
        val imported = listOf(
            WorkoutProfile(id = 1, name = "X"),
            WorkoutProfile(id = 3, name = "Y"),
        )
        val merged = mergeProfiles(existing, imported)
        // 1 collides -> next free id (2); 3 is free -> stays 3.
        assertEquals(listOf(2L, 3L), merged.map { it.id })
    }

    @Test
    fun mergeProfiles_emptyImportProducesEmptyList() {
        assertEquals(emptyList<WorkoutProfile>(), mergeProfiles(listOf(WorkoutProfile()), emptyList()))
    }

    @Test
    fun mergeProfiles_keepsImportOrder() {
        val existing = listOf(WorkoutProfile(id = 5, name = "A"))
        val imported = listOf(
            WorkoutProfile(id = 12, name = "First"),
            WorkoutProfile(id = 5, name = "Second"),
            WorkoutProfile(id = 13, name = "Third"),
        )
        val merged = mergeProfiles(existing, imported)
        assertEquals(listOf("First", "Second", "Third"), merged.map { it.name })
    }

    @Test
    fun mergeProfiles_reassignsIdsDuplicatedWithinImport() {
        // Two rows in one import sharing an id must not both keep it; the second
        // is moved to a fresh id so every merged id stays unique.
        val existing = listOf(WorkoutProfile(id = 1, name = "A"))
        val imported = listOf(
            WorkoutProfile(id = 10, name = "X"),
            WorkoutProfile(id = 10, name = "Y"),
        )
        val merged = mergeProfiles(existing, imported)
        assertEquals(2, merged.map { it.id }.toSet().size)
        assertEquals(listOf("X", "Y"), merged.map { it.name })
        // First 10 stays; the duplicate is reassigned (existing max is 1 -> next fresh is 2).
        assertEquals(10L, merged[0].id)
        assertEquals(2L, merged[1].id)
    }

    @Test
    fun mergeWorkouts_preservesFreshIds() {
        val existing = listOf(workout(id = 1))
        val imported = listOf(workout(id = 20), workout(id = 21))
        val merged = mergeWorkouts(existing, imported)
        assertEquals(listOf(20L, 21L), merged.map { it.id })
    }

    @Test
    fun mergeWorkouts_reassignsCollidingIds() {
        // A second device starts its ids at 1 too; importing must not replace
        // local rows that collide. id 1 -> fresh 3 (above max existing), and the
        // imported id 3 then collides with that fresh id, so it moves to 4.
        val existing = listOf(workout(id = 1), workout(id = 2))
        val imported = listOf(workout(id = 1), workout(id = 3))
        val merged = mergeWorkouts(existing, imported)
        assertEquals(listOf(3L, 4L), merged.map { it.id })
        assertEquals(2, merged.size)
    }

    @Test
    fun mergeWorkouts_reassignsIdsDuplicatedWithinImport() {
        val existing = listOf(workout(id = 1))
        val imported = listOf(workout(id = 5), workout(id = 5))
        val merged = mergeWorkouts(existing, imported)
        assertEquals(2, merged.map { it.id }.toSet().size)
    }

    @Test
    fun profileExport_roundTripsWithIntensityAndMode() {
        val p = WorkoutProfile(
            id = 7, name = "Hill", rounds = 4,
            vibrationMode = VibrationMode.ALL, vibrationIntensity = 0.8f,
        )
        val text = json.encodeToString(ProfileExport(profiles = listOf(p)))
        assertTrue(text.contains("\"vibrationIntensity\""))
        val back = json.decodeFromString<ProfileExport>(text)
        assertEquals(1, back.profiles.size)
        assertEquals("Hill", back.profiles[0].name)
        assertEquals(VibrationMode.ALL, back.profiles[0].vibrationMode)
        assertEquals(0.8f, back.profiles[0].vibrationIntensity, 0.001f)
    }

    @Test
    fun profileExport_ignoresLegacyDarkModeKey() {
        // Dark mode is now a global preference, not a profile field; legacy
        // exports that still carry the key (or a version envelope) must decode
        // and drop it.
        val legacy = """
            {"version":1,"profiles":[{"id":1,"name":"Legacy","darkMode":true}]}
        """.trimIndent()
        val back = json.decodeFromString<ProfileExport>(legacy)
        assertEquals(1, back.profiles.size)
        assertEquals("Legacy", back.profiles[0].name)
    }

    @Test
    fun profileExport_oldPayloadWithoutIntensityDefaultsToHalf() {
        // A pre-0.8 export has no vibrationIntensity key; the field default must apply.
        val legacy = """
            {"version":1,"profiles":[{"id":1,"name":"Legacy","vibrationMode":"PHASE_CHANGE"}]}
        """.trimIndent()
        val back = json.decodeFromString<ProfileExport>(legacy)
        assertEquals(VibrationMode.PHASE_CHANGE, back.profiles[0].vibrationMode)
        assertEquals(0.5f, back.profiles[0].vibrationIntensity, 0.001f)
    }

    @Test
    fun workoutExport_roundTrips() {
        val w = workout(
            id = 3, startTime = 1L, endTime = 2L, durationSec = 60, pushSegments = 1,
            overPushMinSec = 0, distanceMiles = 0.2f, avgPushSpeed = 4.5f,
            avgPushHr = 132, avgRecoveryHr = 120,
            avgPushPace = 122, avgRecoveryPace = 98,
        )
        val text = json.encodeToString(WorkoutExport(workouts = listOf(w)))
        val back = json.decodeFromString<WorkoutExport>(text)
        assertEquals(1, back.workouts.size)
        val row = back.workouts[0]
        assertEquals(60, row.durationSec)
        assertEquals(4.5f, row.avgPushSpeed!!, 0.001f)
        assertEquals(120, row.avgRecoveryHr!!)
        assertEquals(122, row.avgPushPace!!)
        assertEquals(98, row.avgRecoveryPace!!)
    }

    private fun workout(
        id: Long,
        startTime: Long = 1L,
        endTime: Long = 2L,
        durationSec: Int = 60,
        pushSegments: Int = 1,
        overPushMinSec: Int = 0,
        distanceMiles: Float = 0.2f,
        avgPushSpeed: Float? = null,
        avgRecoverySpeed: Float? = null,
        avgOverallSpeed: Float? = null,
        avgPushHr: Int? = null,
        avgRecoveryHr: Int? = null,
        avgOverallHr: Int? = null,
        avgPushPace: Int? = null,
        avgRecoveryPace: Int? = null,
        avgOverallPace: Int? = null,
    ) = WorkoutEntity(
        id = id, startTime = startTime, endTime = endTime, durationSec = durationSec,
        pushSegments = pushSegments, overPushMinSec = overPushMinSec,
        distanceMiles = distanceMiles, avgPushSpeed = avgPushSpeed,
        avgRecoverySpeed = avgRecoverySpeed, avgOverallSpeed = avgOverallSpeed,
        avgPushHr = avgPushHr, avgRecoveryHr = avgRecoveryHr, avgOverallHr = avgOverallHr,
        avgPushPace = avgPushPace, avgRecoveryPace = avgRecoveryPace, avgOverallPace = avgOverallPace,
    )
}