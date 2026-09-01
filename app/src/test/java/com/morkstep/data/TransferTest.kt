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
    fun profileExport_roundTripsDarkMode() {
        val dark = WorkoutProfile(id = 8, name = "Night", darkMode = true)
        val backDark = json.decodeFromString<ProfileExport>(
            json.encodeToString(ProfileExport(profiles = listOf(dark)))
        )
        assertEquals(true, backDark.profiles[0].darkMode)

        val light = WorkoutProfile(id = 9, name = "Day", darkMode = false)
        val backLight = json.decodeFromString<ProfileExport>(
            json.encodeToString(ProfileExport(profiles = listOf(light)))
        )
        assertEquals(false, backLight.profiles[0].darkMode)
    }

    @Test
    fun profileExport_absentDarkModeDefaultsToSystem() {
        // A pre-dark-mode export has no darkMode key; null = follow system.
        val legacy = """
            {"version":1,"profiles":[{"id":1,"name":"Legacy"}]}
        """.trimIndent()
        val back = json.decodeFromString<ProfileExport>(legacy)
        assertEquals(null, back.profiles[0].darkMode)
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
        val w = WorkoutEntity(
            id = 3, startTime = 1L, endTime = 2L, durationSec = 60, fastSegments = 1,
            avgFastPace = 4.5f, avgHeartRate = 130, overCeilingSec = 0, distanceMiles = 0.2f,
            avgPushPace = 4.5f, avgRecoveryPace = 3.1f, avgOverallPace = null,
            avgPushHr = 132, avgRecoveryHr = 120, avgOverallHr = null,
        )
        val text = json.encodeToString(WorkoutExport(workouts = listOf(w)))
        val back = json.decodeFromString<WorkoutExport>(text)
        assertEquals(1, back.workouts.size)
        val row = back.workouts[0]
        assertEquals(60, row.durationSec)
        assertEquals(4.5f, row.avgPushPace!!, 0.001f)
        assertEquals(120, row.avgRecoveryHr!!)
    }
}