package com.morkstep.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists the user's workout profiles and the active profile id. */
class ConfigStore(private val context: Context) {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mork_config")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        private val PROFILES_JSON = stringPreferencesKey("profilesJson")
        private val ACTIVE_ID = longPreferencesKey("activeProfileId")
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