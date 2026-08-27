package com.morkstep.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Persists the user's [IntervalConfig] across launches. */
class ConfigStore(private val context: Context) {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mork_config")

    companion object {
        private val CEIL_PACE = doublePreferencesKey("paceCeiling")
        private val FLOOR_PACE = doublePreferencesKey("paceFloor")
        private val CEIL_HR = intPreferencesKey("hrCeiling")
        private val FLOOR_HR = intPreferencesKey("hrFloor")
        private val AUDIO = booleanPreferencesKey("audioCues")
        private const val DEFAULT_COUNT = 5
    }

    val config: Flow<IntervalConfig> = context.dataStore.data.map { p ->
        val fastSec = p[intPreferencesKey("fastSec")] ?: 180
        val slowSec = p[intPreferencesKey("slowSec")] ?: 180
        val count = p[intPreferencesKey("segments")] ?: DEFAULT_COUNT
        val warm = p[intPreferencesKey("warmSec")] ?: 180
        val cool = p[intPreferencesKey("coolSec")] ?: 120
        IntervalConfig(
            segments = buildList {
                // Zero-length warm-up/cool-down are user intent ("none"), so drop
                // them from the plan. Save writes the raw seconds (see save()).
                if (warm > 0) add(IntervalSegment(PhaseType.WARMUP, warm))
                repeat(count) {
                    add(IntervalSegment(PhaseType.FAST, fastSec))
                    add(IntervalSegment(PhaseType.SLOW, slowSec))
                }
                if (cool > 0) add(IntervalSegment(PhaseType.COOLDOWN, cool))
            },
            paceCeiling = p[CEIL_PACE] ?: 6.5,
            paceFloor = p[FLOOR_PACE] ?: 5.0,
            hrCeiling = p[CEIL_HR] ?: 150,
            hrFloor = p[FLOOR_HR] ?: 120,
            audioCues = p[AUDIO] ?: true,
        )
    }

    suspend fun save(config: IntervalConfig) = context.dataStore.edit { p ->
        val fast = config.segments.firstOrNull { it.type == PhaseType.FAST }?.seconds ?: 180
        val slow = config.segments.firstOrNull { it.type == PhaseType.SLOW }?.seconds ?: 180
        val count = config.segments.count { it.type == PhaseType.FAST }
        // Absent warm-up/cool-down segments mean the user set them to 0; store the
        // raw seconds so a later read keeps "none" instead of resurrecting defaults.
        val warm = config.segments.firstOrNull { it.type == PhaseType.WARMUP }?.seconds ?: 0
        val cool = config.segments.firstOrNull { it.type == PhaseType.COOLDOWN }?.seconds ?: 0
        p[intPreferencesKey("fastSec")] = fast
        p[intPreferencesKey("slowSec")] = slow
        p[intPreferencesKey("segments")] = count
        p[intPreferencesKey("warmSec")] = warm
        p[intPreferencesKey("coolSec")] = cool
        p[CEIL_PACE] = config.paceCeiling
        p[FLOOR_PACE] = config.paceFloor
        p[CEIL_HR] = config.hrCeiling
        p[FLOOR_HR] = config.hrFloor
        p[AUDIO] = config.audioCues
    }
}