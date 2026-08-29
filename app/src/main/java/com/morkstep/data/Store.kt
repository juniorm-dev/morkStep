package com.morkstep.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists the user's workout profiles, active profile id, and sensor mode. */
class ConfigStore(private val context: Context) {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mork_config")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        private val PROFILES_JSON = stringPreferencesKey("profilesJson")
        private val ACTIVE_ID = longPreferencesKey("activeProfileId")
        private val SIMULATED = booleanPreferencesKey("simulatedSensors")
        private val WEAR_HR = booleanPreferencesKey("wearHeartRate")
    }

    /** Whether to use simulated sensor readings (developer testing). Default OFF. */
    val simulatedSensors: Flow<Boolean> = context.dataStore.data.map { it[SIMULATED] ?: false }

    suspend fun setSimulatedSensors(value: Boolean) = context.dataStore.edit { p ->
        p[SIMULATED] = value
    }

    /** Whether to take heart rate from the paired Wear companion instead of BLE. Default OFF. */
    val wearHr: Flow<Boolean> = context.dataStore.data.map { it[WEAR_HR] ?: false }

    suspend fun setWearHr(value: Boolean) = context.dataStore.edit { p ->
        p[WEAR_HR] = value
    }
    val profiles: Flow<List<WorkoutProfile>> = context.dataStore.data.map { p ->
        val raw = p[PROFILES_JSON]
        if (raw.isNullOrBlank()) listOf(defaultProfile()) else runCatching {
            json.decodeFromString<List<WorkoutProfile>>(raw)
        }.getOrDefault(listOf(defaultProfile()))
    }

    val activeId: Flow<Long> = context.dataStore.data.map { p ->
        p[ACTIVE_ID] ?: 0L
    }

    suspend fun saveProfiles(list: List<WorkoutProfile>) = context.dataStore.edit { p ->
        p[PROFILES_JSON] = json.encodeToString(list)
    }

    suspend fun setActive(id: Long) = context.dataStore.edit { p ->
        p[ACTIVE_ID] = id
    }
}